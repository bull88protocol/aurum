# Aurum88 Protocol — project context for Claude Code

A bring-your-own-keys **gold-macro app**: a single 0–100 Gold Index (real yields, USD, central-bank
demand, inflation, technicals) + a forward signal, history chart, AI brief, news, and a **20 Days**
tab (what real yields and the dollar did to gold over the last 20 trading days; it replaced the
Dollar / DXY HMAI tab, see below). No backend; runs on-device. Since v2.5.0 the
6 PM ET weekday report is delivered as a **PDF straight from the notification** — see `app/report/`.

> **Forward Signal v2 (2026-07, ships in v2.1.0):** the 3-6M outlook was rebuilt after a full
> backtest vs real 2005-2026 history — now 0.55 Real-Rate Regime (DFII10 level, HIGH = bullish)
> + 0.25 12M Trend + 0.20 Fed Cycle (DGS2 Δ); needs FRED DGS2 + a 6y DFII10 fetch (wired in
> DataRepository). The old delta-based signal measured IC ≈ −0.05 (its BEARISH months out-returned
> its BULLISH ones); v2 measures IC +0.30/+0.38 train/test. The spot index was validated as a
> *nowcast* and deliberately left unchanged. Methodology, numbers and reproduction:
> **`research/README.md`**. Independently re-audited 2026-07-10 vs fresh LBMA data — all claims
> reproduced, no math changes; v2.2.0 ships the audit's two adjustments (CB 2025 fallback 863 t,
> spot-HOT caution chip). See **`research/VALIDATION_2026-07-10.md`** (incl. live watch-item:
> v2 stayed BULLISH through the 2026 −24% crash).

> **20-Day Drivers + hosted FRED feed (2026-09-16; app code on `feat/20-day-drivers`, ships as v2.8.0;
> the feed workflow is live on `master` and publishing since 2026-09-17):**
> an outside "Core Gold Signal" (20-day change in real yields + the dollar) was backtested as
> written: same-window +0.56, next 20 days +0.04, next 3M -0.02, i.e. a nowcast, not a signal.
> It ships as the descriptive **20 Days** tab (`GoldDriversEngine`: tailwind/headwind read, 1y
> chart, gold's 20-day move broken down into real yields / dollar / everything else) and
> **replaces the Dollar tab; the HMAI engine and the VIX fetch are deleted.** The Gold Index and
> Forward Signal math are untouched. The same branch adds the **hosted FRED feed**: a GitHub
> Action fetches DFII10/T10YIE/DGS2 with the owner's key (repo secret) and publishes
> `fred_daily.json` to the `fred-data` branch. As shipped in v2.8.0 only the 6 PM **report** read
> it (owner's decision) — **v2.9.0 extends it to the whole app**, so a keyless user's Gold Index
> works too; a user's own key comes first either way, the feed only filling in (2026-09-17).
> It also adds the FRED® notice FRED's API terms require (Settings, 20 Days tab, TERMS.md,
> PRIVACY.md) — the app had never shown it. Research: **`research/DRIVERS_20D_2026-09-16.md`**.
> Feed operations: §Hosted FRED feed below.

> **Hosted AI brief, live since 2026-09-26 (shipped in v2.9.0):** the AI brief was the slowest
> thing in the app for two reasons and only one was Gemini's. The call takes 15-60s, *and* it ran
> inside `DataRepository`'s per-symbol loop, so the Gold Index, the chart and the 20 Days tab —
> none of which use it — waited on it. Now `MainViewModel.refresh()` runs the market fetch and the
> brief fetch as two parallel jobs, only the market job drives the spinner, and the brief itself
> comes from a hosted feed the app reads in ~200ms. **The feed is the default and a user's own key
> is the upgrade — the opposite order to the FRED feed**, because here their key is the *slow*
> path, not the fresher one. The AI Brief and News tabs work with no key at all.
>
> Getting it live took three corrections worth remembering. **GitHub's scheduler cannot run it** —
> ~2 dispatches/day for this repo, and zero for a new hourly workflow — so the trigger is AWS
> Lambda + EventBridge (`aws/brief-feed/`). **`gemini-2.5-flash` was retired** mid-flight and
> answers 404 to any key created after the cutoff, which had silently emptied the tab for every
> new user of the *shipped* app. **Search grounding is not available on the free tier at all** —
> three days of 429 on every model while plain generation worked — so headlines now come from free
> Google News RSS and the model picks them by index and never writes a URL. Operations:
> §Hosted AI brief feed below. Notes: **`release-2.9/RELEASE_NOTES.md`**.

> **This file is the cross-machine source of truth.** Claude Code's memory is per-machine and does
> **not** sync. When working from a different computer (e.g. a Mac for the iOS build), this committed
> file — plus the docs it points to — is the context. Keep it current.

## ▶ "What is pending?" — answer from the Open items list below
If the user asks **"what is pending"** / "what's left" / "where were we", read **§Open items** below
and show them **all** of it, most-actionable first, with a one-line status on the release in flight.
Do not improvise a list from git log — that section is the maintained answer. Verify anything
time-sensitive (Play status, whether a build is stale) before repeating it.

## ▶ Release in flight — v2.9.2 submitted 2026-09-28, awaiting review
**v2.9.2 / versionCode 19 was submitted to Google Play on 2026-09-28.** AAB
`08724d7bffbb1081e1dd72adc765caf7bbdfdaa4cdf50e9a5ce1251c791f9ece`. It carries one fix: the AI
Brief and News tabs took every downward drag as a pull-to-refresh, so you could not scroll back
up. Verified on a Pixel 11 before submitting, both directions.

**v2.9.1 / versionCode 18 is live** (submitted and released the same day, 2026-09-28) and has that
scroll bug in it. **v2.9.0 / versionCode 17** is the release before it. 17, 18 and 19 are all
claimed.

This app's review times, since it keeps coming up: 2.9.1 same day, 2.9.0 next day, 2.6.0 next day.
Fast is normal here, never guaranteed.

### ▶ Pick up here (2026-09-28 evening)

Everything is committed, pushed and deployed. Nothing is half-done. Two things are simply waiting.

**1. Confirm v2.9.2 cleared review.** Play Console → Production. Nothing to do if it did.

**2. The first fully unattended feed day is 2026-09-29.** Every Deep Research pickup so far has
been a forced `aws lambda invoke`; the 18:45 ET run on the 29th is the first one nobody triggers.
Check after ~19:00 ET:

```bash
git fetch origin brief-data && git show FETCH_HEAD:brief_daily.json \
  | python3 -m json.tool | grep -E 'analysis_source|generated_utc|"sig"'
```

**Good** is `analysis_source: deep-research` and a `generated_utc` from that evening. `rss` means
the report was not picked up, and the log says why in one line:

```bash
aws logs tail /aws/lambda/aurum-brief-feed --region us-east-1 --since 24h
```

The likely reasons, in order: nothing named `Gold Brief 2026-09-29` in the folder (Gemini's
scheduled action did not write one — **still the one unverified link in the chain**); the doc lost
its link-sharing; or Docs mangled the JSON a third new way, which
`python3 aws/brief-feed/check_doc.py <id>` will show.

Also worth a glance that day: the FRED feed should now stay current. It was two business days
behind Treasury on the 28th, which is why it moved to Lambda.

## Open items (nothing here is blocking; reviewed 2026-09-23)

The maintained answer to "what is pending". Ordered by what actually matters. Keep it current —
when an item is done, delete it rather than leaving it ticked.

0. **Confirm the first unattended feed run**, 2026-09-29 after 19:00 ET — see §Pick up here for
   the commands and what the failure modes look like. Every pickup so far has been forced.
1. **Confirm v2.9.2 cleared review** (submitted 2026-09-28). Nothing to do if it did.
2. **The daily PDF misses the deep analysis.** The app's report worker fires at 18:00 ET, the
   Deep Research report is written ~18:20, the feed picks it up at 18:45. So the tabs get the
   deep sections and the PDF does not. Closing it needs an earlier report or a later worker, and
   the worker time is compiled into the shipped app — so it is a v2.9.2 decision, not config.
3. **Watch Play vitals for v2.9.0**, live since 2026-09-25, and v2.9.1 once it lands. Two reasons it is worth a look
   rather than a glance: it restructured `refresh()` into parallel market and brief jobs, and
   refresh is exactly what v2.7.0 was fixing; and 2.7.0, 2.8.0-skipped and 2.9.0 landed close
   enough together that a new signal cannot be cleanly attributed to one of them. **ANR rate
   first.**
4. **PDF "Open" tile on a gap day.** The one v2.6.0 fix never confirmed in the wild — the old bug
   (previous close shown as the open) was only visible when the previous close fell outside the
   day's range. One look, next time gold gaps.
5. **Duplicate "Aurum Market Data" spreadsheets in Drive.** v2.7.0 stops new ones; it does **not**
   clean up existing ones. Delete strays by hand.
6. **`resolveOpen` and the refresh-timeout paths have no unit tests** — `MainViewModel` needs a
   context. (The other blocker, `org.json` being a throwing stub in unit tests, is fixed on
   `feat/20-day-drivers` by `testImplementation("org.json:json:20180813")`.) Moving the pure logic
   into `:shared` still fixes both; folded into `api-37/API_37_UPGRADE_PLAN.md` §4.
7. **API 37 / Android 17** — the next *forced* work, and the only item with a deadline. Needs
   AGP 9.1.1 + Gradle 9.3.1 + Kotlin 2.x (three major migrations; JDK 17 still fine). No Play
   deadline published; the annual pattern points at **August 2027**. Revisit Q1-Q2 2027.
   Plan, with a trial run behind it: `api-37/API_37_UPGRADE_PLAN.md`.
8. **Optional: `enableEdgeToEdge()` for Android 8-14.** The only Play recommendation that is
   actually true — see §Play Console recommended actions. Cosmetic, and it disturbs the inset
   handling v2.6.0 fixed, so it wants a device to test on rather than a quick edit.
9. **Store polish — consciously skipped 2026-09-04, not forgotten.** No screenshot shows the PDF
   report; `store/screenshots/02_*.png` still pictures the v1 forward card (stale since 2.2); the
   live full description was never confirmed against `store/STORE_LISTING.md`; the Play R8
   recommendation card was never read (the build already runs R8 full mode, so it is almost
   certainly generic). All store-side, no release needed, can land any time.
11. 12. **iOS Phase 2** — parked, needs a Mac. `ios/APPLE_RELEASE_PLAN.md`. Do **not** run it in
   parallel with the API 37 work; both touch `shared/build.gradle.kts` and the Kotlin version.

## Platforms & status
- **Android** — **live on Google Play production: v2.7.0 / versionCode 15** (confirmed 2026-09-18).
  History: **v2.5.0 / versionCode 13** approved 2026-08-20 (the previous production build was
  v2.0.0 / versionCode 6, so upgrading users jumped five releases).
  **v2.6.0 / versionCode 14 — approved and live on Production 2026-09-04** (submitted 09-03).
  Edge-to-edge fixes + a real Settings toolbar, the GLD open mapping (the "Open" tile had always
  shown the previous close), and the AI brief anchored to the app's own market data. Verified on
  device 2026-09-04 **after** release: Settings toolbar, "Navigate up" button and status-bar inset
  all render correctly. See `release-2.6/RELEASE_NOTES.md`.
  **v2.7.0 / versionCode 15** — **live on Production** (submitted 2026-09-04, rolled out to 100%;
  confirmed live 2026-09-18: 177 countries / regions, 9 installs). Bounds an unbounded refresh that
  could spin forever:
  `callTimeout` on all five HTTP clients, Yahoo retries 3→2, `fetchAll`'s pre-loop work guarded (the
  Sheets sync runs only when signed in, which is why the hang looked login-specific), a 180s ceiling
  on `refresh()`, and a Retry button. Also stops `fetchLiveQuotes` minting a duplicate Drive
  spreadsheet on any transient failure. Verified on a Pixel 8a: radios off → error + RETRY button
  instead of a spinner, recovers when tapped. See `release-2.7/RELEASE_NOTES.md`.
  **v2.9.0 / versionCode 17** — **LIVE on Production** (approved 2026-09-25). The AI brief moved
  off the refresh critical path and onto a hosted feed; the hosted FRED feed extended from the
  report to the whole app, so a keyless Gold Index scores all five components; the key fields
  collapsed behind "Use your own API keys" in Settings; and `gemini-flash-latest` replaced the
  retired `gemini-2.5-flash`. Confirmed in production with no keys: FRED components 2026-09-25,
  AI brief and news 2026-09-26. See `release-2.9/RELEASE_NOTES.md`.
  **v2.9.1 / versionCode 18** — **live on Production**, submitted and released the same day
  2026-09-28. `MAX_STALE_HOURS` 12 → 26, the `MODELS` fallback, and the three deep sections on the
  AI Brief tab and in the PDF. Shipped with a scroll bug found straight after release, fixed in
  2.9.2.
  **v2.9.2 / versionCode 19** — **submitted 2026-09-28, awaiting review.** One fix: pull-to-refresh
  on the AI Brief and News tabs was eating upward scrolls, because `SwipeRefreshLayout` asks its
  direct child — a `FrameLayout` there — whether the content can scroll up, and a FrameLayout never
  can. Pre-existing; 2.9.1's three new sections made the tab long enough to expose it.
  **v2.8.0 / versionCode 16** — **SKIPPED**, superseded by v2.9.0. Do not upload 16. Its AAB was
  built and all three gates were met (including the on-device pass on a Pixel 11, 2026-09-24);
  it was dropped because v2.9.0 is a strict superset and also fixes the retired-model bug 2.8.0
  would have shipped. Its content — the 20 Days tab replacing the Dollar tab (HMAI + VIX deleted),
  the report reading the hosted FRED feed, the FRED® notice — all rides in v2.9.0. Historical
  detail: `release-2.8/RELEASE_NOTES.md`.
  **v2.1.0 / versionCode 7** (Forward
  Signal v2 + conditions labels; carries the KMP `:shared` core) is on Play **internal testing**.
  v2.1.1 / versionCode 8 (Clear Cache also busts the 7-day CB feed cache) was never uploaded —
  **skipped, superseded by v2.2.0** (decision 2026-07-12; the fix is contained in it).
  v2.2.0 / versionCode 9 (2026-07-10 audit adjustments: bundled CB 2025 fallback 1000→863 t
  WGC actual + spot-HOT caution chip near the Forward Signal) was likewise never uploaded —
  **skipped, superseded by v2.3.0** (decision 2026-07-31); its AAB targeted API 35 and is stale.
  **v2.3.0 / versionCode 10** — **targets Android 16 (API 36)**, Play's compliance deadline being
  **2026-08-30**; carries the 2.2.0 + 2.1.1 changes. Signed AAB built 2026-07-31, **uploaded to Play
  internal testing and installed on-device 2026-08-01**; smoke-tested 2026-08-08 (incl. Google
  Sign-In, after the OAuth fix below). See `release-2.3/RELEASE_NOTES.md` (toolchain table,
  Android 16 behaviour audit, paste-ready "What's new", upload checklist). Note: Play only clears
  the target-API warning once a **production** release targets 36.
  **v2.4.0 / versionCode 11** (2026-08-08) — Settings-screen key hardening found by that smoke
  test: fields masked with a reveal toggle, the stored key never re-populated into the view tree
  (masked `•••• 11d2` summary instead), `FLAG_SECURE` on `SettingsActivity`. Display layer only —
  at-rest crypto (`SecurePrefs`/`Crypto`, Keystore AES-256-GCM) and `allowBackup="false"` were
  already correct and are untouched. Built + 30/30 tests green; **uploaded to Play internal testing
  and installed on-device 2026-08-08** (confirmed 2026-08-09 from the phone: versionCode 11,
  `installerPackageName=com.android.vending`). See `release-2.4/RELEASE_NOTES.md` — note its
  "AAB and Play upload still to do" line predates the upload and is stale.
  **v2.5.0 / versionCode 13** (2026-08-09) — **the daily report is now a PDF, and it moved from
  9 AM to 6 PM ET, weekdays only.** The worker
  renders the day's data to an A4 PDF and the notification hands that file over: tap opens it in a
  viewer, actions save it to Downloads or share it — **no app launch, no refetch** (previously the
  tap opened `MainActivity`, which re-ran the entire fetch to redraw data the worker already had).
  The notification also carries the numbers now (`Index 63/100 MIXED · Outlook BULLISH · Gold
  $398.47 +2.26%`). New `app/report/` package; no new dependencies (`android.graphics.pdf`) and no
  new permissions. **Why 6 PM:** GLD's daily bar sets at the 4 PM equity close and the Fed's H.15
  (DGS2 / DFII10 / T10YIE) posts at 4:15 PM, so a 9 AM send shipped an index and four FRED-backed
  components that were all a full day stale; 6 PM is also when CME gold reopens. Weekends are
  skipped — they close no US session. `WORK_NAME` is deliberately still `aurum_9am_refresh`:
  renaming the unique work would leave the old 9 AM job enqueued on upgrades, i.e. two reports a
  day. **versionCode 12 was consumed and skipped** — an AAB of this same release, but with the old
  9 AM schedule, had already been uploaded under 12 before the send time moved, and Play never lets
  a code be reused (it is claimed on upload, even into a draft that is never rolled out). 13 is the
  first code carrying the 6 PM weekday schedule; **do not roll out 12**. Signed AAB built + verified
  2026-08-09, and it ships v2.4.0's Settings hardening onward unchanged. **Promoted to Production
  and approved by Play 2026-08-20** — full rollout, 177 countries / regions. This is the release
  that finally clears Play's target-API-36 warning (testing tracks never did). Its production
  release dashboard raised three recommendations, two of which are real and are the reason v2.6.0
  exists — see `release-2.5/NEXT_RELEASE_PLAN.md`. See also `release-2.5/RELEASE_NOTES.md` and
  `store/PLAY_STORE_v2.5.0.md` §6.
  **Still open from this release:** no store screenshot shows the PDF report, and
  `store/screenshots/02_*.png` still pictures the v1 forward card (stale since 2.2).
- **iOS** — parked for now (Apple App Store). Architecture + phased plan in **`ios/APPLE_RELEASE_PLAN.md`**.
  Decision: **Kotlin Multiplatform shared core + native SwiftUI**. Needs a Mac (Xcode is macOS-only).
  **Phase 1 code is on `master`** (rode the v2.1.0 merge): `:shared` KMP module with **the entire
  domain — `model` + both engines (`GoldIndexEngine` + HMAI) — in `commonMain`**; 29/29 tests green.
  (`feat/20-day-drivers` deletes HMAI and adds `GoldDriversEngine`, also pure `commonMain`.)
  When iOS resumes: network clients → Ktor, storage/biometric → expect/actual, tests → commonTest,
  then iOS targets + SwiftUI on the Mac (Phase 2).

## Repo layout
- `app/` — Android app (Kotlin). Holds `network/`, `data/`, `ui/`, `worker/`, `report/` (engines
  now live in `:shared`). **`report/`** (v2.5.0) builds the daily PDF: `ReportContent.kt` assembles
  it as pure Kotlin `Block`s (JVM-testable — `PdfDocument` is a stub in unit tests, so keep the
  content logic out of the renderer), `GoldReportPdf.kt` paints them onto A4,
  `ReportDelivery.kt` opens / shares / saves-to-Downloads, `ReportActionActivity.kt` is the
  invisible notification trampoline.
- `shared/` — KMP module (added on `ios-port`). `commonMain` now has the **full domain** (`model/`,
  `domain/gold/` — `GoldIndexEngine` + `GoldDriversEngine`; `domain/hmai/` was deleted with the
  Dollar tab) and `util/formatDecimals` (expect/actual); deps: kotlinx-datetime.
  `androidTarget` only for now; iOS targets get enabled on the Mac (Phase 2). The app depends on `:shared`.
- `.github/workflows/fred-feed.yml` + `.github/fred-feed/build_feed.py` (hosted FRED feed, see below)
- `.github/workflows/brief-feed.yml` + `.github/brief-feed/` (the AI brief *generator*:
  `build_brief.py`, and `mock_server.py`, a local stand-in for Yahoo + Gemini so it runs
  without a key. The workflow is a manual escape hatch only — the schedule is in AWS.)
- `aws/` — **what actually runs both feeds.** `deploy.sh <brief|fred>` (one script; creates the
  IAM role, Lambda and EventBridge rule, idempotent), `README.md` (setup, cost, key handling, and
  the table of what a run publishes when), `brief-feed/` and `fred-feed/` (a `lambda_function.py`
  each), `brief-feed/github_publish.py` (Git Data API, orphan commit — shared by both),
  `brief-feed/deep_research_prompt.txt` + `DEEP_RESEARCH_PROMPT.md` (the daily Deep Research
  prompt and the record of eight test runs), `brief-feed/check_report.py` and `check_doc.py`
  (verify a report and its doc before trusting either), `brief-feed/sync_latest_report.gs`
  (an Apps Script bridge, now unnecessary — the folder lists without credentials)
- `data/cb_quarterly.json` (hosted CB feed) · `release-2.0/` (v2.0 docs) · `ios/` (Apple plan) ·
  `release-2.0/cb-data/` (CB feed tool) · `research/` (Gold Index backtest: scripts + results; `cache/` gitignored,
  regenerate via `research/README.md`).
- **Target:** all engines + `model/` + `network/` in `shared/commonMain` (one source of truth);
  `app/` (Android UI) and `ios/` (SwiftUI UI) on top.

## Build / test the shared module
```bash
./gradlew :shared:assembleDebug          # build the KMP android artifact
./gradlew :app:testDebugUnitTest         # 86 tests (still run from :app for now)
```

## Branch model
- `master` — stable mainline for **both** platforms; always shippable.
- Big/risky work goes on a **temporary feature branch**, validated, then merged to `master`
  (e.g. `release-2.0` for v2.0, `fix/refresh-timeouts` for v2.7.0; the iOS port uses **`ios-port`**,
  and the API 37 work gets **`api-37`**).
- One repo, one `master` — never split Android and iOS onto separate long-lived branches (it would
  fork the shared core).

## Build / test (Android, from repo root)
```bash
source /home/sun/option_android/android_env.sh   # this Linux box only
./gradlew :app:assembleDebug                      # debug build
./gradlew :app:testDebugUnitTest                  # 86 tests (Gold Index 19 + drivers 12 + FRED feed 6 + brief feed 11 + brief JSON 5 + brief merge 6 + report 17 + schedule 8 + research dumps 2)
./gradlew :app:bundleRelease                       # signed Play AAB (needs keystore.properties)
```

### On-device testing without destroying the Play install (v2.5.0)
Debug builds carry `applicationIdSuffix = ".debug"`, so `com.sun.aurum.debug` installs **alongside**
the Play build. Before this, a debug APK could not be installed over a Play-signed install, and
uninstalling to make room would have destroyed the stored API keys (`allowBackup="false"` — nothing
comes back). Google Sign-In does not work in the debug variant (no OAuth client for that package);
everything else does. Useful adb recipes, all non-destructive:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# skip the biometric gate: BiometricAuth reads a plain long from shared_prefs/biometric_session.xml
adb shell run-as com.sun.aurum.debug sh -c 'cat shared_prefs/biometric_session.xml'
# force-run the daily worker (job id rotates on each launch — REPLACE policy; re-read it every time)
adb shell dumpsys jobscheduler | grep -oE "JOB #[^ ]+ com.sun.aurum.debug[^ ]*"
adb shell cmd jobscheduler run -f com.sun.aurum.debug <jobId>
adb shell dumpsys notification --noredact | grep -A6 "pkg=com.sun.aurum.debug"
adb pull /sdcard/Android/data/com.sun.aurum.debug/files/reports/   # the generated PDFs
# seed synthetic state (e.g. Gemini brief + news without a key) — app must be force-stopped first
adb shell run-as com.sun.aurum.debug cat files/symbol_cache.json
```

### The two hosted feeds (AWS Lambda → `fred-data` and `brief-data`)
**Both feeds run on AWS Lambda + EventBridge. Neither runs on GitHub any more.** The workflows
survive as manual `workflow_dispatch` escape hatches with no schedules. Code, setup, cost and key
handling: **`aws/README.md`**. Deploy: `./aws/deploy.sh brief` or `./aws/deploy.sh fred`.

| | function | schedule (ET) | branch |
|---|---|---|---|
| AI brief | `aurum-brief-feed` | 01:45 / 09:45 / 18:45, **daily** | `brief-data` |
| FRED | `aurum-fred-feed` | 08:25 / 16:25 / 17:25 / 18:25 / 19:25, **weekdays** | `fred-data` |

- **Why they left GitHub.** Its scheduler fires ~2 of the day's slots, hours late, across two cron
  layouts tried. On 2026-09-28 the FRED workflow ran once, at 19:44 UTC, missing three evening
  slots — and the published feed was **two business days behind Treasury's own numbers**, so every
  install's Gold Index was scoring stale yields while the app looked healthy. Moving it to Lambda
  advanced the feed a full day within one invocation. See [[aurum-github-cron-unreliable]].
- **What a run does, in order:** RSS (~190 headlines, free, no key) → look for a Deep Research
  report → Gemini. The report is looked up *before* Gemini so an outage has a fallback.
- **Three outcomes**, and `analysis_source` in the feed always says which: `deep-research` (report
  applies), `rss` (none does), `deep-research-only` (Gemini down but a report applies — its
  analysis plus bare RSS headlines, no summaries). Gemini down with no report publishes nothing
  and the last good feed stays.
- **The Deep Research report** comes from a **link-shared Drive folder** (`DEEP_RESEARCH_FOLDER_ID`),
  which lists through `embeddedfolderview` with **no credentials** — no Drive API, no OAuth, no
  Apps Script. Matches `Gold Brief <YYYY-MM-DD>` exactly, falling back to the newest dated report
  so yesterday's carries into this morning; `fetch_deep_research`'s `lsl` check then decides
  whether it still covers the current session.
- **Docs mangles the JSON on export, two ways so far** — newlines to literal `\n`, and unescaped
  quotes inside prose. `_loads_forgiving` tries the raw text first and repairs only on failure,
  logging which repair it used. No prompt can fix this; the damage happens after the model is done.
- **Model:** `MODELS = ("gemini-3.6-flash", "gemini-flash-latest")`, tried in order — a pinned id
  first, the alias behind it. Pinned ids get **retired** (2.5-flash did, 404ing for every new user
  of the shipped app); the `-latest` alias tracks the most **overloaded** model. Keep in step
  between `build_brief.py` and `GeminiClient.kt`.
- **Spend** is capped by `MIN_AGE_MINUTES` (400) in `build_brief.py`, not by the cron. Both
  Lambdas sit inside the always-free tier, and `MaximumRetryAttempts=0` is set by `deploy.sh` —
  AWS defaults to 2, which once turned one tick into nine Gemini calls.
- **Likeliest silent failure: the GitHub PAT expiring.** Fine-grained, Contents read/write on this
  repo only. Nothing warns you.
- **Verify a report before trusting it:** `python3 aws/brief-feed/check_report.py <file>` and
  `python3 aws/brief-feed/check_doc.py <id>`. Local test without keys:
  `python3 .github/brief-feed/mock_server.py &` then the CLI with `*_API_BASE` overrides.

### Google Sign-In / OAuth (Cloud Console — the SHA-1 trap)
Sign-In powers only the **optional** Sheets sync (`GoogleAuthManager`, scope `drive.file`); quotes
use Yahoo Finance either way. Google matches **package name + the SHA-1 of the signing cert of the
running APK** against an *Android* OAuth client. One client per key, so all three are registered in
the Cloud project (`com.sun.aurum` each time):

| Key | SHA-1 | Covers |
|---|---|---|
| **Play app signing** | `BE:E7:3F:45:B3:8A:11:A6:A3:6A:FA:82:83:16:81:23:4E:25:49:05` | anything installed from Play |
| Upload (`bull88-upload.jks`) | `51:24:2A:9E:A3:91:20:55:8A:38:86:1A:20:DF:6F:BD:2A:C4:B8:F3` | locally-installed release builds |
| Debug (`~/.android/debug.keystore`) | `84:F3:B9:70:D3:62:FD:86:31:72:D2:0D:A3:71:DE:D0:74:53:E4:29` | `assembleDebug` |

**Play App Signing re-signs the AAB**, so a Play build presents *none* of your local keys — register
the Play cert (Play Console → Test and release → Setup → App integrity) or Play builds fail while
debug builds work. Symptom: `ApiException` **code 10 / `DEVELOPER_ERROR`** — handled at
`SettingsActivity.kt:151`; visible as `ConnectionResult{statusCode=DEVELOPER_ERROR}` in logcat.
It means "app not authorized", never a bad account. This bit v2.3.0 and was fixed 2026-08-08 by
adding the Play-cert client — **console-side only, no rebuild, no versionCode bump**. The consent
screen carries no key and is project-wide; publishing it to Production is right (Testing mode caps
sign-in to listed test users and expires refresh tokens after 7 days) but cannot fix a code 10.

### Play Console "recommended actions" — checked 2026-09-28, all four declined
They reappear on every release, so here is the reasoning rather than a fresh investigation each
time. Recheck if the app starts decoding images, or if `minifyEnabled` ever changes.

- **"Edge-to-edge may not display for all users"** — *true, and deliberately not acted on.* The app
  never calls `enableEdgeToEdge()`. On targetSdk 36 the system enforces it, so Android 15+ gets it;
  `minSdk` 26 means Android 8-14 users see conventional system bars instead. Cosmetic only. Adding
  the call would re-run the inset handling v2.6.0 specifically fixed, on devices that are hard to
  test — a poor trade.
- **"Uses deprecated APIs or parameters for edge-to-edge"** — *cannot reproduce.* No
  `statusBarColor`, `navigationBarColor`, `setSystemUiVisibility`, `SYSTEM_UI_FLAG` or
  `setDecorFitsSystemWindows` anywhere in code or themes; the theme omits the first two with a
  comment saying why. Only `windowLightStatusBar` / `windowLightNavigationBar`, neither deprecated.
  Most likely a library's contribution to the merged manifest.
- **"Bitmap downsampling"** — *not applicable.* No `BitmapFactory` or decode call in the app at
  all; the charts are drawn, not loaded. 15 raster drawables, all launcher and notification icons.
- **"R8 optimization"** — *already on.* `isMinifyEnabled` + `isShrinkResources` +
  `proguard-android-optimize.txt`, and R8 full mode is AGP 8.x's default and is not disabled in
  `gradle.properties`. Generic.

### Toolchain (as of v2.3.0 — do not downgrade)
`targetSdk`/`compileSdk` **36** · AGP **8.10.1** · Gradle wrapper **8.11.1** · Kotlin **1.9.24** ·
JDK **17** · `minSdk` 26. Requires SDK `platforms;android-36` installed
(`sdkmanager "platforms;android-36"`). AGP 8.10 is the *lowest* version supporting API 36 and it
needs Gradle ≥ 8.11.1 — bumping targetSdk without bumping AGP only earns an "untested compileSdk"
warning. AGP 8.10 tops out at API 36. The next target bump (API 37 / Android 17) was
trial-run 2026-09-03 and needs **AGP ≥ 9.1.1 + Gradle ≥ 9.3.1 + Kotlin 2.x** — three major-version
migrations, not a targetSdk edit. JDK 17 still suffices. No Play deadline published; the annual
pattern points at **August 2027**. Full plan, with measured findings: **`api-37/API_37_UPGRADE_PLAN.md`**.
Feature-branch job (`api-37`); do not start it while a release is in review.

## Conventions
- **Commits are attributed to `aurum88p`. Do NOT add a `Co-Authored-By:` / Claude trailer.**
- **Distribution is store-only** (Google Play / App Store). **Never commit an APK/AAB** — `*.apk` /
  `*.aab` are gitignored. Testers onboard by email → see `TESTING.md`.
- **Secrets** live in `keystore.properties` (gitignored); never commit keys/keystores.
- The CB feed is a **git-pushed data file** read from `master` — update via
  `release-2.0/cb-data/cb_update.py set <YYYY-QN> <tonnes> --push` (no app release).
  See `release-2.0/cb-data/README.md`. NB: `set` regenerates the `method` field from a generic
  template — restore the provenance note by hand afterwards.

## Key docs
- `ios/APPLE_RELEASE_PLAN.md` · `ios/APP_STORE_SUBMISSION_CHECKLIST.md` · `ios/MAC_SETUP.md`
- `release-2.0/RESUME.md` (v2.0 handoff) · `release-2.0/CHANGELOG.md` · `release-2.0/NEXT_RELEASE_PLAN.md`
- `release-2.9/RELEASE_NOTES.md` (hosted AI brief, code complete) ·
  `release-2.8/RELEASE_NOTES.md` (next release, built) · `release-2.7/RELEASE_NOTES.md` (live) · `release-2.6/RELEASE_NOTES.md`
- `research/DRIVERS_20D_2026-09-16.md` (20 Days tab: why it is a nowcast, the shipped spec, parity)
- `api-37/API_37_UPGRADE_PLAN.md` (next forced Android work — AGP 9 / Gradle 9 / Kotlin 2)
- `TESTING.md` (tester onboarding) · `README.md` · `PRIVACY.md` · `TERMS.md`
