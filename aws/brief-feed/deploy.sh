#!/usr/bin/env bash
# Creates or updates everything the hosted AI brief feed needs in AWS. Idempotent: run it again
# to ship a code change or move the schedule.
#
#   export GEMINI_API_KEY=...        # the maintainer's Gemini key
#   export GITHUB_TOKEN=...          # fine-grained PAT, Contents: read and write, this repo only
#   ./aws/brief-feed/deploy.sh
#
# Needs the AWS CLI logged in (`aws configure`) with rights to create an IAM role, a Lambda and an
# EventBridge rule. See README.md in this directory for the whole setup, cost and key handling.
set -euo pipefail

FUNCTION=${FUNCTION:-aurum-brief-feed}
ROLE=${ROLE:-${FUNCTION}-role}
RULE=${RULE:-${FUNCTION}-schedule}
REGION=${REGION:-$(aws configure get region || echo us-east-1)}
REPO=${GITHUB_REPO:-bull88protocol/aurum}
# :17 past every hour, UTC. EventBridge cron is 6 fields and needs ? for one of day-of-month /
# day-of-week. Unlike GitHub's scheduler this actually fires, so once an hour is once an hour.
SCHEDULE=${SCHEDULE:-"cron(17 * * * ? *)"}

: "${GEMINI_API_KEY:?set GEMINI_API_KEY in the environment}"
: "${GITHUB_TOKEN:?set GITHUB_TOKEN in the environment}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT

echo "==> packaging"
# The generator is shared verbatim with the GitHub workflow — one brief, one prompt, one validator.
cp "$HERE/lambda_function.py" "$HERE/github_publish.py" "$BUILD/"
cp "$ROOT/.github/brief-feed/build_brief.py" "$BUILD/"
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

ENV="Variables={GEMINI_API_KEY=${GEMINI_API_KEY},GITHUB_TOKEN=${GITHUB_TOKEN},GITHUB_REPO=${REPO}}"

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
  publishes to  $REPO  branch brief-data

Test it now, ignoring the 50-minute freshness guard:
  aws lambda invoke --function-name $FUNCTION --region $REGION \\
    --payload '{"force":true}' --cli-binary-format raw-in-base64-out /dev/stdout

Then confirm what landed:
  git fetch origin brief-data && git show FETCH_HEAD:brief_daily.json | python3 -m json.tool | head -20

Logs:
  aws logs tail /aws/lambda/$FUNCTION --region $REGION --follow
EOF
