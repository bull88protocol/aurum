# AI brief feed — AWS Lambda

The thing that actually runs the hourly brief. `.github/workflows/brief-feed.yml` stays as a
manual escape hatch, but its schedule is gone.

## Why this is not just the GitHub Action

GitHub's shared scheduler gives this repo about **two dispatches a day**, clustered near 17:00 and
22:50 UTC, with consistent **15-18 hour overnight gaps**. Measured 2026-09-17..24 on `fred-feed.yml`:

| gap | |
|---|---|
| 09-18 00:50 → 16:34 | 15.7h |
| 09-21 23:14 → 09-22 17:04 | 17.8h |
| 09-22 22:54 → 09-23 17:17 | 18.4h |

And a newly added hourly workflow (`brief-feed.yml`, added 04:10 UTC on 09-24) received **zero**
dispatches in six hours across two cron variants, while `fred-feed.yml` kept getting its two. An
hourly brief is not something that scheduler will deliver. EventBridge fires on time.

The generator is unchanged and shared: this packages `.github/brief-feed/build_brief.py` verbatim,
so there is one prompt, one validator and one output shape no matter which side runs. The publish
is the same single-orphan-commit force-push to `brief-data`, done through the Git Data API because
Lambda has no git. The app cannot tell which one wrote a given brief.

## What you need first

1. **An AWS account** and the CLI logged in: `aws configure`. Any region; `us-east-1` is the default.
2. **A GitHub fine-grained PAT** — github.com → Settings → Developer settings → Personal access
   tokens → Fine-grained tokens:
   - Resource owner: `bull88protocol`, repository access: **only `aurum`**
   - Repository permissions: **Contents: Read and write**. Nothing else.
   - Expiry: set a reminder — the feed stops silently when it lapses (see Watch below).
3. **The Gemini key**, the same one in the repo secret.

## Deploy

```bash
export GEMINI_API_KEY=...
export GITHUB_TOKEN=github_pat_...
./aws/brief-feed/deploy.sh
```

Idempotent — run it again for a code change or to move the schedule
(`SCHEDULE="cron(17 * * * ? *)" ./aws/brief-feed/deploy.sh`).

Then test, bypassing the 50-minute guard:

```bash
aws lambda invoke --function-name aurum-brief-feed \
  --payload '{"force":true}' --cli-binary-format raw-in-base64-out /dev/stdout
git fetch origin brief-data && git show FETCH_HEAD:brief_daily.json | python3 -m json.tool | head -20
```

## Cost

Free, and not marginally. 24 invocations a day at ~60s and 256 MB is ~730 requests and ~11,000
GB-seconds a month, against an always-free tier of 1M requests and 400,000 GB-seconds. The Gemini
grounded calls are the real cost and they are capped by `MIN_AGE_MINUTES` (50) in `build_brief.py`
at ~24/day, exactly as before — moving the trigger changed the timing, not the spend.

## Key handling

Both keys are Lambda environment variables, encrypted at rest with an AWS-managed key. That means
anyone with `lambda:GetFunctionConfiguration` on the account can read them, so this is only
appropriate because it is a single-owner account. Secrets Manager would scope it properly at
$0.40/secret/month; if the account ever gains other users, move them.

Neither key ever reaches a URL: Gemini's travels in the `x-goog-api-key` header, GitHub's in
`Authorization`. `github_publish.py` deliberately strips the URL out of error messages for that
reason. Nothing is logged but the generated timestamp, the signal and the commit sha.

## Failure behaviour

A failure raises, so the invocation is recorded as an error and **nothing is published** — the last
good brief stays up until the app ages it out (`BriefFeedClient.MAX_STALE_HOURS`). Causes worth
knowing apart: a bad or quota-limited Gemini key, a brief that fails validation (missing prose,
fewer than two news items, a news item with no URL), and an expired GitHub PAT.

```bash
aws logs tail /aws/lambda/aurum-brief-feed --follow
```

## Watch

- **The PAT expiry.** This is the likeliest silent failure. Set a calendar reminder.
- **CloudWatch alarm on Errors** — not set up by `deploy.sh`, because a couple of failures a day
  would be normal while the Gemini quota is unproven. Worth adding once the baseline is known.
- The GitHub workflow still exists with `workflow_dispatch` only. If Lambda is ever down, running
  it by hand publishes exactly the same thing.
