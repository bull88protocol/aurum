#!/usr/bin/env bash
# Deploys a feed to AWS Lambda + EventBridge. Idempotent: run it again to ship a code change or
# move a schedule.
#
#   ./aws/deploy.sh brief    # the AI brief feed  -> brief-data
#   ./aws/deploy.sh fred     # the FRED feed      -> fred-data
#
# Both read their keys from the environment:
#   export GITHUB_TOKEN=...            # fine-grained PAT, Contents: read and write, this repo
#   export GEMINI_API_KEY=...          # brief only
#   export FRED_API_KEY=...            # fred only
#   export DEEP_RESEARCH_FOLDER_ID=... # brief, optional; link-shared folder of Gold Brief <date>
#
# One script rather than one per feed: they differ in four lines of config and share ninety of
# deployment, and a duplicated deploy script is how one of the two quietly stops matching.
#
# See aws/brief-feed/README.md for setup, cost and key handling.
set -euo pipefail

FEED="${1:-}"
case "$FEED" in
  brief)
    FUNCTION=${FUNCTION:-aurum-brief-feed}
    BUILDER=build_brief.py
    BRANCH=brief-data
    # Three a day. The evening slot is 18:45 ET, after the Deep Research report is written
    # (~18:20) — it was 17:17 and could only ever publish the RSS brief.
    SCHEDULE=${SCHEDULE:-"cron(45 5,13,22 * * ? *)"}
    : "${GEMINI_API_KEY:?set GEMINI_API_KEY in the environment}"
    ENV_VARS="GEMINI_API_KEY=${GEMINI_API_KEY},GITHUB_TOKEN=${GITHUB_TOKEN:-},GITHUB_REPO=${GITHUB_REPO:-bull88protocol/aurum},DEEP_RESEARCH_DOC_ID=${DEEP_RESEARCH_DOC_ID:-},DEEP_RESEARCH_FOLDER_ID=${DEEP_RESEARCH_FOLDER_ID:-}"
    ;;
  fred)
    FUNCTION=${FUNCTION:-aurum-fred-feed}
    BUILDER=build_feed.py
    BRANCH=fred-data
    # Five a weekday, which is affordable here in a way it was not on GitHub: FRED's series are
    # daily, a run that finds nothing new publishes nothing, and each invocation is three API
    # calls against a ~120/min limit. 20:25-23:25 UTC covers the 4:15 PM ET H.15 post in both
    # EDT and EST, with retries; 12:25 catches late revisions.
    SCHEDULE=${SCHEDULE:-"cron(25 12,20,21,22,23 ? * MON-FRI *)"}
    : "${FRED_API_KEY:?set FRED_API_KEY in the environment}"
    ENV_VARS="FRED_API_KEY=${FRED_API_KEY},GITHUB_TOKEN=${GITHUB_TOKEN:-},GITHUB_REPO=${GITHUB_REPO:-bull88protocol/aurum}"
    ;;
  *)
    echo "usage: $0 <brief|fred>" >&2; exit 2 ;;
esac

ROLE=${ROLE:-${FUNCTION}-role}
RULE=${RULE:-${FUNCTION}-schedule}
REGION=${REGION:-$(aws configure get region || echo us-east-1)}
REPO=${GITHUB_REPO:-bull88protocol/aurum}

: "${GITHUB_TOKEN:?set GITHUB_TOKEN in the environment}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERE="$ROOT/aws/$FEED-feed"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT

echo "==> packaging"
# The generator is shared verbatim with the GitHub workflow — one brief, one prompt, one validator.
cp "$HERE/lambda_function.py" "$ROOT/aws/brief-feed/github_publish.py" "$BUILD/"
cp "$ROOT/.github/$FEED-feed/$BUILDER" "$BUILD/"
( cd "$BUILD" && zip -q -r function.zip . )
echo "    $(du -h "$BUILD/function.zip" | cut -f1) — stdlib only, nothing vendored"

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
ROLE_ARN="arn:aws:iam::${ACCOUNT}:role/${ROLE}"

if ! aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  echo "==> creating IAM role $ROLE"
  aws iam create-role --role-name "$ROLE" --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]
  }' >/dev/null
  # Logs only. The function talks to Gemini, Yahoo and GitHub over the internet and touches
  # nothing in the account, so it needs no other AWS permission.
  aws iam attach-role-policy --role-name "$ROLE" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
  echo "    waiting for the role to propagate"; sleep 15
fi

# DEEP_RESEARCH_DOC_ID is optional. Set it and the day's Deep Research report supplies the
# analysis while the RSS pass keeps supplying the headlines; leave it empty and nothing changes.
ENV="Variables={$ENV_VARS}"

if aws lambda get-function --function-name "$FUNCTION" --region "$REGION" >/dev/null 2>&1; then
  echo "==> updating function code"
  aws lambda update-function-code --function-name "$FUNCTION" --region "$REGION" \
    --zip-file "fileb://$BUILD/function.zip" --no-cli-pager >/dev/null
  aws lambda wait function-updated --function-name "$FUNCTION" --region "$REGION"
  echo "==> updating configuration"
  aws lambda update-function-configuration --function-name "$FUNCTION" --region "$REGION" \
    --timeout 300 --memory-size 256 --environment "$ENV" --no-cli-pager >/dev/null
else
  echo "==> creating function $FUNCTION"
  # 300s because a grounded Gemini call can take a minute and the generator retries twice; the
  # inner urlopen timeout (180s) is what actually bounds a single call.
  aws lambda create-function --function-name "$FUNCTION" --region "$REGION" \
    --runtime python3.12 --handler lambda_function.handler --role "$ROLE_ARN" \
    --timeout 300 --memory-size 256 --environment "$ENV" \
    --zip-file "fileb://$BUILD/function.zip" --no-cli-pager >/dev/null
fi
aws lambda wait function-updated --function-name "$FUNCTION" --region "$REGION"
FN_ARN=$(aws lambda get-function --function-name "$FUNCTION" --region "$REGION" \
         --query Configuration.FunctionArn --output text)

echo "==> scheduling: $SCHEDULE"
aws events put-rule --name "$RULE" --region "$REGION" \
  --schedule-expression "$SCHEDULE" --state ENABLED --no-cli-pager >/dev/null
aws lambda add-permission --function-name "$FUNCTION" --region "$REGION" \
  --statement-id "${RULE}-invoke" --action lambda:InvokeFunction \
  --principal events.amazonaws.com \
  --source-arn "arn:aws:events:${REGION}:${ACCOUNT}:rule/${RULE}" \
  --no-cli-pager >/dev/null 2>&1 || true      # already present on a re-run
aws events put-targets --rule "$RULE" --region "$REGION" \
  --targets "Id=1,Arn=${FN_ARN}" --no-cli-pager >/dev/null

# EventBridge invokes asynchronously, and Lambda's default async policy retries a FAILED
# invocation twice. For this function that is exactly wrong: a failure is a bad key, an exhausted
# quota or a rejected brief, none of which a retry 60 seconds later fixes, and each retry spends
# another grounded Gemini call. Left at the default it turned one scheduled brief into three
# invocations (and, with the old in-process 429 retry, nine grounded calls an hour). The hourly
# schedule is the retry.
aws lambda put-function-event-invoke-config --function-name "$FUNCTION" --region "$REGION" \
  --maximum-retry-attempts 0 --maximum-event-age-in-seconds 900 --no-cli-pager >/dev/null

cat <<EOF

Deployed.
  function  $FUNCTION  ($REGION)
  schedule  $SCHEDULE
  publishes to  $REPO  branch $BRANCH

Test it now, ignoring the 50-minute freshness guard:
  aws lambda invoke --function-name $FUNCTION --region $REGION \\
    --payload '{"force":true}' --cli-binary-format raw-in-base64-out /dev/stdout

Then confirm what landed:
  git fetch origin brief-data && git show FETCH_HEAD:brief_daily.json | python3 -m json.tool | head -20

Logs:
  aws logs tail /aws/lambda/$FUNCTION --region $REGION --follow
EOF
