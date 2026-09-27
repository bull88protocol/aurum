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

## Optional: feeding in the Deep Research report

Set `DEEP_RESEARCH_DOC_ID` and the day's Deep Research report supplies the **analysis** while the
RSS pass keeps supplying the **headlines**.

It is a merge and not a replacement, for a concrete reason: the app's News tab and the PDF's news
section both render `brief.news`, and `deep_research_prompt.txt` bans the model from writing URLs,
so a Deep Research report always has `news: []`. Dropping it in whole would empty both.

`sig`, `score`, `desc`, `yr`, `to` and `kf` come from the report; `news` stays from RSS. The
published feed records which it used in `analysis_source`.

**Setup** — no OAuth, no service account, no new Lambda dependencies:

1. Make **one** Google Doc and keep reusing it. Name it whatever you like; only the ID matters.
2. Share → General access → **Anyone with the link → Viewer**. That is what makes the
   credential-free plain-text export work. It also means anyone with the link can read it, so
   nothing private goes in that doc.
3. Each day, paste **the whole report** into it — prose and the fenced JSON block. The JSON is
   what the feed reads; the prose is for you. **Append or replace, either works**: the extractor
   scans every schema-1 block in the doc and takes the one with the newest `as_of_utc`.
4. ID from the URL: `docs.google.com/document/d/`**`<ID>`**`/edit`
5. Check it before deploying — this fetches the doc exactly the way the Lambda will:
   ```bash
   python3 aws/brief-feed/check_doc.py <ID>
   ```
6. `DEEP_RESEARCH_DOC_ID=<ID> GEMINI_API_KEY=… GITHUB_TOKEN=… ./aws/brief-feed/deploy.sh`

### Making it hands-off

If the scheduled Gemini action writes a new doc each day — `gold_report_09272026` and so on —
nothing above has to change. **`sync_latest_report.gs`** (this directory) is an Apps Script that
finds the newest doc matching that prefix and copies its text into the one fixed inbox doc the
Lambda already reads.

The search has to happen somewhere, and doing it in Apps Script rather than in Lambda is the whole
point: finding a doc by name needs the Drive API, which needs OAuth and a client library — exactly
the two things the plain-text export route avoids. Inside Google, Drive access is free and already
authenticated as you, so no credential leaves Google and the AWS side is untouched.

    Gemini (~16:00 ET)  ->  gold_report_<date>
    Apps Script (~16:30 ET)  ->  copies newest into the inbox doc
    Lambda (17:17 ET)  ->  reads the inbox doc, merges, publishes
    App report (18:00 ET)  ->  reads the merged feed

Setup is in the file's header comment. Two details worth knowing: it picks the newest by Drive's
**creation time**, not by the date in the filename, because a mistyped name would otherwise win or
lose silently where a timestamp cannot be wrong; and it refuses to copy a doc with no JSON block,
so a failed Deep Research run leaves the inbox intact rather than blanking it.

`DEEP_RESEARCH_DOC_ID` also accepts a full URL, so if you would rather have Apps Script serve the
report from a web app than copy it into a doc, that works with no code change.

**When the doc is ignored**, each logging a line and falling back to the RSS brief rather than
failing: the doc is unreachable or not shared; it has no schema-1 JSON block; its `as_of_utc` is
more than 24 hours old; or its `lsl` names a different session than the run is covering. That last
check is the one that matters — a report written Sunday evening is still right on Monday morning
and wrong by Monday evening, and the session label says so exactly where an age in hours only
approximates it.

**Timing.** Deep Research at ~16:00 ET → the doc → the 17:17 ET feed run merges and publishes →
the 6 PM ET report reads the merged feed. The 01:17 and 09:17 runs will reuse the same report
while it still covers the current session, then fall back on their own.

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
