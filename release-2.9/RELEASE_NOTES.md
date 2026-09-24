# Aurum88 Protocol v2.9 — Release Notes

## v2.9.0 (versionCode 17)

**Status: code complete on `feat/hosted-brief`, 85 tests green. Not built, not uploaded.**
Depends on v2.8.0 shipping first — see the gate list in `release-2.8/RELEASE_NOTES.md`.

The app stopped waiting on Gemini. The AI brief now arrives from a hosted feed in about 200ms
instead of a 15-60 second grounded call, it no longer blocks the rest of the screen, and it works
without an API key. The key fields moved behind a collapsed section, because a key is now an
optional upgrade rather than a setup step.

---

## Why

Two separate things made the app slow to load, and only one of them was Gemini's fault.

1. **The call is slow.** `GeminiClient.fetchAnalysisAndNews` is a `generateContent` grounded with
   Google Search that returns a five-item news list. 15-60 seconds is normal; its `callTimeout` is
   150s.
2. **It was in the critical path of everything else.** It ran *inside* `DataRepository`'s
   per-symbol loop, between the Yahoo fetch and the FRED fetch. `MainViewModel.refresh()` marks
   every symbol `loading`, and all four tabs read that flag — so the Gold Index, the price chart
   and the 20 Days tab, none of which use the brief, sat behind it on every refresh.

A hosted feed fixes (1) for users with no key. Splitting the fetch fixes (2) for everyone,
including users who add a key — which a feed alone can never do.

## What changed

- **Hosted AI brief feed.** A GitHub Action regenerates the gold brief about hourly with the
  maintainer's Gemini key (repo secret `GEMINI_API_KEY`, never in the app) and publishes it to the
  `brief-data` branch. The app reads it anonymously from raw.githubusercontent.com.
- **The brief is now the default source, with a user's own key as the upgrade.** This is the
  *opposite* order to the FRED feed, deliberately: there a user's key is both fast and fresher, so
  it comes first. Here their key is the slow path, so the feed paints first and their own brief
  replaces it when it arrives.
- **The brief fetch left the market path.** `fetchAll` no longer takes `geminiKey`/`forceGemini`;
  `MainViewModel.refresh()` runs a market job and a brief job in parallel. Only the market job
  drives the pull-to-refresh spinner.
- **The AI Brief and News tabs work with no key at all.** Their empty states used to say "Add a
  free Gemini key to unlock…", which is no longer true.
- **Provenance on the brief.** A feed brief is written against the gold price at generation time,
  which can be an hour behind the quote on the Gold tab — so the tab says "Shared brief · written
  6:54 PM, 23 Sep" rather than implying it is live. This keeps the consistency guarantee v2.6.0
  established (the brief's numbers must not silently disagree with the app's own).
- **Settings: the keys moved behind "Use your own API keys",** collapsed by default, under a new
  DATA SOURCES card that states in one line what the app is actually reading. The FRED® notice
  stays outside the collapsed section, always visible, as its terms require.
- **The hosted FRED feed stops being report-only.** Through v2.8.0 only the 6 PM report read it
  (the decision of 2026-09-16), so a keyless user's Gold Index was blank everywhere except the PDF
  — which made the new "no API keys needed" summary a lie. `DataRepository` now downloads it too,
  **lazily**: a user whose own key works never fetches it, and a user's own key still comes first.
  This costs FRED nothing, because the feed is a static file on GitHub rather than an API call.
  *This reverses a recorded decision* — to put it back, drop the `fromFeed` fallback in
  `buildSymbolState`.
- **"Needs a FRED key" copy corrected.** The engines set `keyRequired` whenever a FRED series is
  empty, and they cannot tell why. With the feed behind the app that now means *both* the key and
  the feed came back empty — a network failure far more often than a missing key. So
  "FRED API key required → Settings" became "No FRED data — pull to refresh", and the Gold Index
  banner, the 20 Days leg and the report note now lead with retrying and mention a key as the
  backup. Affects `GoldIndexEngine` (×4), `QuoteFragment`, `DriversFragment`, `ReportContent`,
  the Settings FRED card and the onboarding sheet.
- **FRED feed cron respread** — see below. Not cosmetic: it fixes a real miss.
- `actions/checkout@v4` → `@v7` in `fred-feed.yml` (closes the long-standing follow-up; the new
  workflow uses v7 from the start).

## The FRED feed was missing its own report window

Six days of run history (2026-09-17..23), checked while building this release, answered the open
question about GitHub's scheduler — badly:

| Day | Runs (of 10 scheduled) | Times (UTC) |
|---|---|---|
| Thu 09-17 | 2 | 17:09, 22:49 |
| Fri 09-18 | 3 | 16:34, 22:32, 00:40 (Sat) |
| Mon 09-21 | 2 | 18:14, 23:14 |
| Tue 09-22 | 2 | 17:04, 22:54 |
| Wed 09-23 | 2 | 17:17, 22:54 |

Two of ten slots a day. The lone 12:30 UTC slot fired every day but 4-5¾ hours late, and the nine
bunched evening slots (20:10-22:50) collapsed into a single run at ~22:5x — **after** the 6 PM ET
report (22:00 UTC) on every single weekday. Keyless users' reports have been carrying the previous
publish since the feed went live.

Reading: GitHub coalesces a burst into one run, so density buys nothing. The cron is now four slots
an hour apart (`15 20-23 * * 1-5`) instead of nine every twenty minutes, so each can be late on its
own. **This is a hypothesis from six days of data — watch whether an evening run now lands before
22:00 UTC.** If it doesn't, the trigger has to move off GitHub's scheduler.

Mitigating, and worth knowing before treating this as urgent: at 22:54 UTC, `DFII10` and `DGS2`
still only reach the previous business day, so a perfectly timed run would publish the same values
for those two. `T10YIE` is the one actually being missed, and the bigger cost is redundancy — a
design meant to have ten chances a day has one.

## Cost and cadence of the brief feed

An EventBridge rule fires the `aurum-brief-feed` Lambda at :17 every hour (every day — gold
trades Sunday evening ET). `build_brief.py` still skips any run that finds a published brief
younger than 50 minutes and returns before the Gemini call, so the cap is ~24 grounded calls a day
whatever the trigger — moving off GitHub changed the timing, not the spend.

**The trigger moved to AWS on 2026-09-24**, after `brief-feed.yml` got zero dispatches in six
hours across two cron variants while `fred-feed.yml` kept its usual two. GitHub gives this repo
~2 scheduled runs a day with 15-18h overnight gaps. The Lambda packages the same generator
verbatim and publishes the same shape; the workflow keeps `workflow_dispatch` as a manual escape
hatch. Setup: `aws/brief-feed/README.md`.
That guard, not the cron, is what bounds spend: at most ~24 grounded calls a day however often
GitHub fires. **Check the Gemini free-tier allowance for Search-grounded requests against that
number before enabling the secret** — `MIN_AGE_MINUTES` in `build_brief.py` is the one constant to
raise if it needs dialling back.

## Files

New:
- `.github/workflows/brief-feed.yml` — the hourly job.
- `.github/brief-feed/build_brief.py` — fetches the GLD quote, asks Gemini, validates, publishes.
- `.github/brief-feed/mock_server.py` — local stand-in for Yahoo + Gemini, so the generator can be
  run end-to-end without a key.
- `app/.../network/BriefFeedClient.kt` — reads the feed, drops anything over 12 hours old.
- `app/.../data/GeminiResultJson.kt` — the brief wire format, shared by the disk cache and the feed.
- `app/src/test/resources/brief_daily_sample.json` — real generator output, pinned as a fixture.
- Tests: `BriefFeedClientTest` (10), `GeminiResultJsonTest` (5), `BriefMergeTest` (6).

Changed:
- `DataRepository` — `loadBrief` (fast: feed, else cache) and `refreshBriefWithOwnKey` (slow);
  `fetchAll`/`fetchSymbol` lost their Gemini parameters; `buildSymbolState` falls back to the
  hosted FRED feed for every caller, not just the worker, fetching it lazily.
- `GoldIndexEngine`, `QuoteFragment`, `DriversFragment`, `ReportContent`, `MainActivity`
  (onboarding) — the "needs a FRED key" copy.
- `MainViewModel` — `refresh()` splits into a market job and a brief job; new `BRIEF_TIMEOUT_MS`.
- `Models.kt` — `briefLoading` / `briefGeneratedUtc` / `briefFromFeed` on `SymbolState`, plus
  `carryingBriefFrom` and `withBrief`, the two merge helpers that keep the parallel writers from
  clobbering each other.
- `GeminiCache` — records origin and generation time; entries written before this release are
  read as own-key.
- `DataCache` — persists the two durable brief fields (not `briefLoading`).
- `DailyRefreshWorker` — fetches the brief explicitly, awaiting *both* stages (nothing is on
  screen, and a report is worth the extra minute). The PDF's "has a brief" flag is now
  `key || feed`, matching how `hasFredKey` already worked.
- `AiBriefFragment` / `NewsFragment` — empty-state copy, provenance line.
- `SettingsActivity` + `activity_settings.xml` — the DATA SOURCES card and the collapsible section.
- `fred-feed.yml` — cron respread, checkout v7.
- `PRIVACY.md` §Third-party services, `TERMS.md` §3 and new §3b.

## Verification performed

- `:app:assembleDebug` clean; `:app:testDebugUnitTest` **85 tests, 0 failures** (was 64).
- `build_brief.py` run end-to-end against `mock_server.py`: code fences stripped, the stale news
  item dropped (4 → 3), ET session dates matching `GeminiClient.getTradingSessionDates` across
  mid-session / after-close / weekend / pre-open, and the key asserted to travel in the
  `x-goog-api-key` header and never in a URL.
- Guard paths exercised: a 0-minute-old previous brief skips (exit 0); `--force` overrides it; a
  2-hour-old one republishes; a missing key exits 1; Yahoo unreachable still publishes, unanchored.
- Validation rejects: empty outlook, one news item, a news item with no URL, one key factor, an
  empty model response. Normalisation checked: `"somewhat bearish"` → `BEARISH`, score 999 → 100,
  a non-numeric score → 50.
- Cross-language contract: `BriefFeedClientTest.parses_real_output_from_the_feed_generator` parses
  the committed fixture, which is genuine `build_brief.py` output.

**Not yet verified: anything on a device.** The 20 Days tab from v2.8.0 is also still unseen.

## Before uploading

**The feeds do not wait for this release.** GitHub runs scheduled workflows from the default
branch alone, so `brief-feed.yml` and the respread `fred-feed.yml` were committed straight to
`master` on 2026-09-24 (`3f1684b`) and are live there, independent of any app upload — the same
way the FRED feed went live on 2026-09-17 while its app code sat on a branch. The owner added the
`GEMINI_API_KEY` secret the same day. Only this branch's *app* code is gated below.

1. **v2.8.0 must ship first** — it is one gate (an on-device pass) from done, and this branch is
   built on top of it.
2. **Confirm the brief feed is actually publishing** before shipping an app that depends on it:
   `gh run list --workflow brief-feed.yml` for green runs, then
   `git fetch origin brief-data && git show FETCH_HEAD:brief_daily.json`. A manual run needs the
   `bull88protocol` login — the box's `gh` is `CoinTranscend` and gets HTTP 403 on dispatch.
3. **Rebase onto the merged `master`.** Phase A's `.github/` commit is already there, so those
   files should drop out of the rebase as duplicates.
4. **On-device pass.** Specifically, with **no keys saved at all**: the AI Brief tab filling from
   the feed and showing the "Shared brief · written …" line; the **Gold Index scoring all five
   components** from the FRED feed (this is the part that never worked before); the Gold tab
   rendering before the brief does; Settings showing the collapsed section and the keyless summary
   line, with the FRED® notice visible above it.
5. Merge, tag `v2.9.0`, build, check the AAB sha256, upload.

## Follow-ups (not blocking)

- Watch whether an evening FRED run now lands before 22:00 UTC (see above).
- Store listing/screenshots still don't mention the 20 Days tab, the PDF report, or this.
- `GeminiClient`'s prompt and `build_brief.py`'s prompt are maintained by hand in two languages.
  They are *allowed* to drift — the app parses the generator's output, not the model's, so drift
  costs quality and never correctness — but a real improvement to one should be copied to the other.
