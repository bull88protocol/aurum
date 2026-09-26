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

## ▶ Release in flight — nothing. v2.9.0 is live and both feeds are publishing
**v2.9.0 / versionCode 17 is live on Google Play Production** (approved and confirmed installed
from the store 2026-09-25). Code on `master`, tagged **`v2.9.0`**; **17 is claimed**. Notes:
**`release-2.9/RELEASE_NOTES.md`**.

Confirmed working in production, from the owner's own install: the Gold Index shows all five
components **with no FRED key**, and since 2026-09-26 the AI Brief and News tabs fill from the
hosted brief feed **with no Gemini key**. That is the whole point of the release, true on a Play
build rather than a debug one.

**v2.8.0 / versionCode 16 was SKIPPED**, superseded by v2.9.0, the same way v2.1.1 and v2.2.0
were. **Do not upload versionCode 16.**

### Built but deliberately not shipped — v2.9.1 / versionCode 18
Committed on `master`, 86 tests green, **no AAB built**. Deferred by the owner 2026-09-26: "if one
[run] is lost it won't matter." It carries two things, neither urgent:
- `BriefFeedClient.MAX_STALE_HOURS` 12 → 26, so a single missed feed run does not empty the tab.
- The `MODELS` fallback (pinned id, then the `-latest` alias), which survives both a model
  retirement and an overloaded newest model.

**Ship it with whatever goes next.** Nothing is broken without it; the version is already bumped,
so the next release either goes out as 2.9.1 or gets renumbered.

## Open items (nothing here is blocking; reviewed 2026-09-23)

The maintained answer to "what is pending". Ordered by what actually matters. Keep it current —
when an item is done, delete it rather than leaving it ticked.

0. **Watch the brief feed for a week** now that it publishes (first ever publish 2026-09-26
   16:55 UTC). Three runs a day at 01:17 / 09:17 / 17:17 ET. Check
   `aws logs tail /aws/lambda/aurum-brief-feed --region us-east-1 --since 24h` and
   `git log -1 FETCH_HEAD` on `brief-data`. What could go wrong: the Gemini free tier throttling
   plain generation the way it refuses grounding, the GitHub PAT expiring silently, or RSS
   returning too few gold items to pass validation.
1. **Ship v2.9.1 whenever something else needs a release** — see §Built but deliberately not
   shipped. Deferred by the owner 2026-09-26, with the reasoning recorded so it is not
   re-litigated: a lost feed run leaves the tab empty for a few hours and nothing else, which
   does not justify a Play review on its own. Deferred, not forgotten.
2. **Store listing/screenshots** still don't mention the 20 Days tab or the keyless data feeds.
3. **Watch Play vitals for v2.9.0**, live since 2026-09-25. Two reasons it is worth a look
   rather than a glance: it restructured `refresh()` into parallel market and brief jobs, and
   refresh is exactly what v2.7.0 was fixing; and 2.7.0, 2.8.0-skipped and 2.9.0 landed close
   enough together that a new signal cannot be cleanly attributed to one of them. **ANR rate
   first.**
4. **Did the FRED cron respread work?** The old question is **answered, and the answer was no**:
   six days of runs (2026-09-17..23) showed 2 of 10 slots a day, the 12:30 UTC slot firing 4-5¾h
   late and the nine bunched evening slots collapsing into one run at ~22:5x UTC — *after* the
   22:00 UTC report, every weekday. Keyless users' reports carried the previous publish the whole
   time. Reading: GitHub coalesces a burst into one run. v2.9.0 respreads the cron to four slots an
   hour apart (`15 20-23 * * 1-5`). **Now check whether an evening run lands before 22:00 UTC**
   (`gh run list --workflow fred-feed.yml --limit 60`). If not, the trigger has to leave GitHub's
   scheduler. Softening it: at that hour DFII10 and DGS2 only reach the previous business day
   anyway, so T10YIE is what's actually missed — the real loss is redundancy.
5. **PDF "Open" tile on a gap day.** The one v2.6.0 fix never confirmed in the wild — the old bug
   (previous close shown as the open) was only visible when the previous close fell outside the
   day's range. One look, next time gold gaps.
6. **Duplicate "Aurum Market Data" spreadsheets in Drive.** v2.7.0 stops new ones; it does **not**
   clean up existing ones. Delete strays by hand.
7. **`resolveOpen` and the refresh-timeout paths have no unit tests** — `MainViewModel` needs a
   context. (The other blocker, `org.json` being a throwing stub in unit tests, is fixed on
   `feat/20-day-drivers` by `testImplementation("org.json:json:20180813")`.) Moving the pure logic
   into `:shared` still fixes both; folded into `api-37/API_37_UPGRADE_PLAN.md` §4.
8. **API 37 / Android 17** — the next *forced* work, and the only item with a deadline. Needs
   AGP 9.1.1 + Gradle 9.3.1 + Kotlin 2.x (three major migrations; JDK 17 still fine). No Play
   deadline published; the annual pattern points at **August 2027**. Revisit Q1-Q2 2027.
   Plan, with a trial run behind it: `api-37/API_37_UPGRADE_PLAN.md`.
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
  **v2.9.1 / versionCode 18** — committed, **not built**, deferred by the owner 2026-09-26:
  `MAX_STALE_HOURS` 12 → 26 and the `MODELS` fallback. Ship with whatever goes next.
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
- `aws/brief-feed/` — **what actually runs the brief feed**: `lambda_function.py`,
  `github_publish.py` (Git Data API, orphan commit), `deploy.sh` (idempotent, creates the IAM
  role, Lambda and EventBridge rule), `README.md` (setup, cost, key handling), and
  `DEEP_RESEARCH_PROMPT.md` + `deep_research_prompt.txt` (the not-yet-wired upgrade path)
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

### Hosted FRED feed (GitHub Action → `fred-data` branch)
Why: the app scores the FRED components for every user without shipping the owner's key (FRED's
terms make the key holder "solely responsible" for all use, and every install's worker fires at
18:00 ET: ~40 phones × 3 calls hits the ~120 req/min per-key limit). The feed is a static file on
GitHub, not an API call, so serving it to everyone costs FRED nothing.
**Scope changed in v2.9.0:** it was report-only (the owner's call, 2026-09-16), which left a keyless
user's Gold Index blank everywhere except the PDF. `DataRepository` now downloads it too, lazily —
a user whose own key works never fetches it. Revert by dropping the `fromFeed` fallback in
`buildSymbolState`.
- **Runs by itself** on GitHub: weekdays every 20 min 20:10–22:50 UTC (covers the 4:15 PM ET H.15
  post → 6 PM report in both EDT and EST) + 12:30 UTC. Publishes only when data changed; each
  publish force-pushes a single orphan commit (`fred_daily.json` + README) to `fred-data`.
  App URL: `https://raw.githubusercontent.com/bull88protocol/aurum/fred-data/fred_daily.json`.
- **Secret:** `FRED_API_KEY` (repo Settings → Secrets and variables → Actions). Never in the app,
  never in the repo. The builder never prints request URLs (they carry the key). **Set 2026-09-17**
  by the owner signed in as `bull88protocol`. The `gh` CLI on the Linux box is logged in as
  `CoinTranscend`, which has read access only: it can read runs and the branch but can't manage
  secrets or start runs. Git pushes go over SSH and work.
- **Live since 2026-09-17** (first run 20:42 ET). At that hour DFII10 and DGS2 reached the previous
  business day and T10YIE the same day; the "FRED feed through <date>" commit shows the latest.
- **Order:** the user's own key first, the feed only when that fetch comes back empty (no key, or
  it failed). The first build had the feed first; fixed 2026-09-17 (`2b9b7df`) because GitHub delays
  or skips scheduled runs. Note this is the **opposite** of the AI brief feed's order, and the
  reason is that here the key is the fresher source while there it is the slower one.
- **Failure = safe:** bad key / FRED down / short, stale or out-of-range data → the run fails,
  nothing is published, GitHub emails the owner. The app drops any series older than 10 days, and a
  dropped series shows as "No FRED data — pull to refresh" rather than as a missing key (the
  `keyRequired` flag now means "neither the key nor the feed produced data").
- **Run-page notices (2026-09-17), both harmless:** `actions/checkout@v4` targets Node 20 (GitHub
  forces Node 24; bump to `@v7`, see Open items), and `ubuntu-latest` moves to Ubuntu 26 from
  2026-10-19 (nothing to do: `build_feed.py` uses only the Python standard library).
- **Watch:** GitHub auto-disables scheduled workflows in public repos after 60 days with no repository
  activity (unclear whether the bot's pushes count). If the feed goes stale, check Actions → "FRED
  feed" → Enable / Run workflow.
- **Required notice** (FRED API ToU, applies with or without a key): "This product uses the FRED® API
  but is not endorsed or certified by the Federal Reserve Bank of St. Louis." + a link to the ToU and
  users agreeing to it. Lives in Settings (DATA SOURCES card — deliberately *outside* the collapsible
  keys section, so collapsing it never hides the notice), the 20 Days tab, TERMS.md §3a, PRIVACY.md §3.
- Local test without a key: `FRED_API_BASE=http://127.0.0.1:PORT FRED_API_KEY=x python3
  .github/fred-feed/build_feed.py --out /tmp/f.json` against a mock (see research log for how it was done).

### Hosted AI brief feed (AWS Lambda → `brief-data` branch)
Why hosted: the Gemini call takes 15-60s, which made it the slowest thing in the app, and without a
key the AI Brief and News tabs were simply empty. Generating it centrally means every install reads
a ~200ms static file instead. **Live since 2026-09-26.**
- **Trigger: AWS Lambda + EventBridge, not GitHub.** `cron(17 5,13,21 * * ? *)` — 01:17 / 09:17 /
  17:17 ET, three a day. The last lands before the 6 PM ET report, which reads this same feed.
  GitHub's scheduler was tried first and cannot do it: ~2 dispatches/day for this repo and **zero**
  for a newly added hourly workflow over six hours across two cron variants, while `fred-feed.yml`
  kept getting its usual two. See [[aurum-github-cron-unreliable]].
- **Code:** `aws/brief-feed/` — `lambda_function.py`, `github_publish.py`, `deploy.sh`, `README.md`.
  It packages `.github/brief-feed/build_brief.py` **verbatim**, so there is one prompt, one
  validator and one output shape whichever side runs. `.github/workflows/brief-feed.yml` survives
  as a manual `workflow_dispatch` escape hatch with **no schedule**.
- **Publishing:** the same single orphan commit the workflow made, through the Git Data API
  (blob → tree → parentless commit → forced ref update), because Lambda has no git. Keeps
  `brief-data` at one commit instead of ~1,100 a year.
- **Redeploy:** `GEMINI_API_KEY=… GITHUB_TOKEN=… ./aws/brief-feed/deploy.sh`. Idempotent. Keys are
  Lambda env vars; `tok/` holds them locally and is gitignored. **Cost: inside the always-free
  tier** (~90 requests and ~1.3k GB-seconds a month).
- **No Search grounding — headlines come from RSS.** Grounding is unavailable on this key's tier
  entirely: 429 RESOURCE_EXHAUSTED on every model for three days while plain generation answered
  fine. It was never load-bearing anyway — prices come from Yahoo and the prompt defers to them.
  `fetch_news` pulls three Google News RSS queries (~195 deduped items), and **the model picks
  headlines by index and never writes a URL**; `to_brief` maps indices back to real RSS metadata,
  so a hallucinated link is structurally impossible. Out-of-range or repeated indices are dropped.
- **Two retry layers once cost 9 grounded calls an hour instead of 1** — `generate()` retried 429
  three times, and Lambda's default async policy retried the invocation twice. Both fixed: all 4xx
  fail fast, `MaximumRetryAttempts=0` (set by `deploy.sh`, so a fresh deploy cannot inherit the
  default), and `MIN_AGE_MINUTES` is 400 as a backstop under the 480-minute schedule gap.
- **Model:** `MODELS = ("gemini-3.6-flash", "gemini-flash-latest")`, tried in order — a pinned id
  first, the alias behind it. Both failure modes are real and pull opposite ways: a pinned id gets
  **retired** (2.5-flash did, and 404'd for every new user of the shipped app), while the `-latest`
  alias tracks the newest model and is the most **overloaded** (503 on repeated attempts the same
  week). Keep in step between `build_brief.py` and `GeminiClient.kt`.
- **Failure = safe:** a bad key, an exhausted quota or a brief that fails validation (missing
  prose, fewer than 2 news items, an unusable summary) raises, publishes nothing and logs to
  CloudWatch; the last good brief stays up until the app ages it out.
- **Watch:** the GitHub PAT expiring is the likeliest silent failure — fine-grained, Contents
  read/write on this repo only, and nothing warns you when it lapses.
- **Upgrade path:** `aws/brief-feed/DEEP_RESEARCH_PROMPT.md` + `deep_research_prompt.txt` — a daily
  Gemini Deep Research prompt, pinned to the app's own five drivers, that emits the feed's exact
  JSON schema. Not wired up; its URLs are model-written and would need validating first.
- Local test without a key: `python3 .github/brief-feed/mock_server.py &` then
  `GEMINI_API_BASE=http://127.0.0.1:8731 YAHOO_API_BASE=http://127.0.0.1:8731 GEMINI_API_KEY=x
  python3 .github/brief-feed/build_brief.py --out /tmp/b.json`. The mock asserts the key stays in
  the header; `--thin` returns a brief that should fail validation. NB `fetch_news` is not mocked —
  it hits Google News RSS for real, which is free and keyless.

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
