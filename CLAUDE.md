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

> **Hosted AI brief + the slow-load fix (2026-09-23; on `feat/hosted-brief`, ships as v2.9.0):**
> the AI brief was the slowest thing in the app for two reasons, and only one was Gemini's fault.
> The grounded `generateContent` call takes 15-60s, *and* it ran inside `DataRepository`'s
> per-symbol loop, so the Gold Index, the chart and the 20 Days tab — none of which use it — all
> waited on it. Now: a GitHub Action regenerates the brief about hourly with the owner's Gemini key
> (repo secret `GEMINI_API_KEY`) and publishes `brief_daily.json` to the `brief-data` branch, which
> the app reads in ~200ms; and `MainViewModel.refresh()` runs the market fetch and the brief fetch
> as two parallel jobs, with only the market job driving the spinner. **The feed is the default
> source and a user's own key is the upgrade — the opposite order to the FRED feed**, because here
> the user's key is the *slow* path, not the fresher one; their own brief replaces the feed's when
> it arrives. The AI Brief and News tabs now work with no key at all, and the key fields moved
> behind a collapsed "Use your own API keys" section in Settings. Index and Forward Signal math
> untouched. 85 tests. Notes: **`release-2.9/RELEASE_NOTES.md`**. Feed operations: §Hosted AI brief
> feed below.

> **This file is the cross-machine source of truth.** Claude Code's memory is per-machine and does
> **not** sync. When working from a different computer (e.g. a Mac for the iOS build), this committed
> file — plus the docs it points to — is the context. Keep it current.

## ▶ "What is pending?" — answer from the Open items list below
If the user asks **"what is pending"** / "what's left" / "where were we", read **§Open items** below
and show them **all** of it, most-actionable first, with a one-line status on the release in flight.
Do not improvise a list from git log — that section is the maintained answer. Verify anything
time-sensitive (Play status, whether a build is stale) before repeating it.

## ▶ Release in flight — nothing. v2.9.0 is LIVE on Production
**v2.9.0 / versionCode 17 was approved and is live on Google Play Production** (owner confirmed
2026-09-25, installed from the store on their own device). Code on `master`, tagged **`v2.9.0`**;
**17 is claimed**. Notes: **`release-2.9/RELEASE_NOTES.md`**.

Confirmed working in production from the owner's install: **the Gold Index shows its FRED-backed
components with no FRED key**, off the hosted `fred-data` feed. That is the release's central
claim, now true on a Play build and not just a debug one.

**v2.8.0 / versionCode 16 was SKIPPED**, superseded by v2.9.0 (decision 2026-09-24), the same way
v2.1.1 and v2.2.0 were: v2.9.0 is a strict superset and also fixes the retired-Gemini-model bug
that v2.8.0 would have shipped. **Do not upload versionCode 16.**

**Not yet working in production: the AI Brief and News tabs**, because the brief feed has never
published — see Open items. That is server-side and needs no app release; the app degrades
correctly meanwhile.

## Open items (nothing here is blocking; reviewed 2026-09-23)

The maintained answer to "what is pending". Ordered by what actually matters. Keep it current —
when an item is done, delete it rather than leaving it ticked.

0. **The AI brief feed has never published — Google Search grounding quota.** The Lambda is
   correct and EventBridge fires it hourly, reliably (verified in CloudWatch 2026-09-25). Every
   grounded call returns **429 RESOURCE_EXHAUSTED**, while *plain* generation on the same key
   works — so the binding limit is the **Search grounding** allowance, which is far smaller than
   the generation one. Until one brief publishes, the AI Brief and News tabs are empty for
   everyone in production.
   **Owner action:** check usage and the grounding allowance at <https://ai.dev/rate-limit> and
   billing at <https://ai.studio/projects>. Grounding may need a paid tier.
   **Two bugs of ours made it worse and are fixed** (`5652312`, `0f536b2`): `generate()` retried
   429 three times with backoff, and Lambda's default async policy retried the invocation twice,
   so each hourly tick spent **nine** grounded calls and three 120-second invocations instead of
   one. Now: 4xx fail fast, `MaximumRetryAttempts=0`, and `MIN_AGE_MINUTES` 50 → 230 so the
   steady state is ~6 briefs/day rather than 24.
1. **`BriefFeedClient.MAX_STALE_HOURS` is 12, and the feed now publishes every 8h.** Fine while
   runs succeed — a brief is at most 8h old. But **one missed run makes the gap 16h, past the
   limit, and the app drops the brief and shows the empty state** until the next success. That is
   honest behaviour, not a crash, and it needs no urgent release; but **raise it to ~26h in the
   next version that ships for any reason**, so a single failed run is survivable. It is a
   one-constant change in `BriefFeedClient.kt` and cannot be done server-side.
2. **Store listing/screenshots** still don't mention the 20 Days tab or the keyless data feeds.
3. **LIVE BUG: the shipped app asks Gemini for a retired model.** v2.7.0 (production) calls
   `gemini-2.5-flash`, which Google retired on/before 2026-09-24 to *"no longer available to new
   users"*. A key created **after** that date answers **404**, `fetchAnalysisAndNews` swallows it
   into null, and the AI Brief and News tabs sit empty with nothing explaining why. Existing keys
   whose project already used 2.5-flash are grandfathered, so this hits **new users only** —
   which is also why no one has reported it. Fixed on `feat/hosted-brief` (v2.9.0) by moving both
   the app and the feed to the alias `gemini-flash-latest`. **This is now the strongest reason to
   get v2.8.0 out and v2.9.0 behind it.** Discovered by the first real Gemini call ever made from
   the feed; every mock in the repo had been answering happily.
4. **Watch ANR rate now that 2.7.0 is live** (confirmed 2026-09-18) — see the caveat above about
   overlapping vitals. This is the highest-value thing to look at, and the reason is specific: the
   fix changed cancellation and timeout behaviour on every screen.
5. **Did the FRED cron respread work?** The old question is **answered, and the answer was no**:
   six days of runs (2026-09-17..23) showed 2 of 10 slots a day, the 12:30 UTC slot firing 4-5¾h
   late and the nine bunched evening slots collapsing into one run at ~22:5x UTC — *after* the
   22:00 UTC report, every weekday. Keyless users' reports carried the previous publish the whole
   time. Reading: GitHub coalesces a burst into one run. v2.9.0 respreads the cron to four slots an
   hour apart (`15 20-23 * * 1-5`). **Now check whether an evening run lands before 22:00 UTC**
   (`gh run list --workflow fred-feed.yml --limit 60`). If not, the trigger has to leave GitHub's
   scheduler. Softening it: at that hour DFII10 and DGS2 only reach the previous business day
   anyway, so T10YIE is what's actually missed — the real loss is redundancy.
6. **PDF "Open" tile on a gap day.** The one v2.6.0 fix never confirmed in the wild — the old bug
   (previous close shown as the open) was only visible when the previous close fell outside the
   day's range. One look, next time gold gaps.
7. **Duplicate "Aurum Market Data" spreadsheets in Drive.** v2.7.0 stops new ones; it does **not**
   clean up existing ones. Delete strays by hand.
8. **`resolveOpen` and the refresh-timeout paths have no unit tests** — `MainViewModel` needs a
   context. (The other blocker, `org.json` being a throwing stub in unit tests, is fixed on
   `feat/20-day-drivers` by `testImplementation("org.json:json:20180813")`.) Moving the pure logic
   into `:shared` still fixes both; folded into `api-37/API_37_UPGRADE_PLAN.md` §4.
9. **API 37 / Android 17** — the next *forced* work, and the only item with a deadline. Needs
   AGP 9.1.1 + Gradle 9.3.1 + Kotlin 2.x (three major migrations; JDK 17 still fine). No Play
   deadline published; the annual pattern points at **August 2027**. Revisit Q1-Q2 2027.
   Plan, with a trial run behind it: `api-37/API_37_UPGRADE_PLAN.md`.
10. **Store polish — consciously skipped 2026-09-04, not forgotten.** No screenshot shows the PDF
   report; `store/screenshots/02_*.png` still pictures the v1 forward card (stale since 2.2); the
   live full description was never confirmed against `store/STORE_LISTING.md`; the Play R8
   recommendation card was never read (the build already runs R8 full mode, so it is almost
   certainly generic). All store-side, no release needed, can land any time.
11. **BLOCKED: the Gemini account has no credits.** Every `generateContent` call on the owner's
   key returns **402 "Your prepayment credits are depleted"** — account-wide, not specific to
   grounding or to a model (verified 2026-09-24 against `gemini-flash-latest` with and without
   tools). The Lambda is deployed and correct; it fails at exactly this point and publishes
   nothing. Fix at **https://ai.studio/projects** → billing. Until then `brief-data` does not
   exist and the app's AI Brief tab has no feed to read. Spend, once funded, is bounded by
   `MIN_AGE_MINUTES` (50) in `.github/brief-feed/build_brief.py` at ~24 grounded calls/day.
12. **iOS Phase 2** — parked, needs a Mac. `ios/APPLE_RELEASE_PLAN.md`. Do **not** run it in
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
  **v2.9.0 / versionCode 17** — **code complete on `feat/hosted-brief`, not built** (2026-09-23).
  The AI brief moves to a hosted hourly feed and off the critical path: `refresh()` now runs the
  market fetch and the brief fetch as two parallel jobs, so the Gold Index and charts no longer
  wait on a 15-60s grounded Gemini call, and the AI Brief and News tabs work with no key at all.
  The key fields are collapsed behind "Use your own API keys" in Settings under a new DATA SOURCES
  card. **The hosted FRED feed also stops being report-only** — the app reads it too, so a keyless
  Gold Index works, which is what makes "no API keys needed" true. Also respreads the FRED cron
  after six days of runs showed GitHub firing 2 of 10 slots and never before the 6 PM report, and
  bumps `checkout@v4` → `@v7`. 85 tests. Stacked on v2.8.0.
  See `release-2.9/RELEASE_NOTES.md`.
  **v2.8.0 / versionCode 16** — **signed AAB rebuilt 2026-09-17, not uploaded** (gate left: an
  on-device pass; 2.7.0 and the FRED feed are both live). The 20 Days tab replaces the Dollar tab
  (HMAI + VIX deleted); the 6 PM report reads the hosted FRED feed so keyless users get every FRED row (a
  user's own key still comes first); FRED® terms notice added. 64 tests. See
  `release-2.8/RELEASE_NOTES.md`.
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
- `.github/workflows/brief-feed.yml` + `.github/brief-feed/` (hosted AI brief: `build_brief.py`
  and `mock_server.py`, a local stand-in for Yahoo + Gemini so it runs without a key)
- `data/cb_quarterly.json` (hosted CB feed) · `release-2.0/` (v2.0 docs) · `ios/` (Apple plan) ·
  `release-2.0/cb-data/` (CB feed tool) · `research/` (Gold Index backtest: scripts + results; `cache/` gitignored,
  regenerate via `research/README.md`).
- **Target:** all engines + `model/` + `network/` in `shared/commonMain` (one source of truth);
  `app/` (Android UI) and `ios/` (SwiftUI UI) on top.

## Build / test the shared module
```bash
./gradlew :shared:assembleDebug          # build the KMP android artifact
./gradlew :app:testDebugUnitTest         # 85 tests (still run from :app for now)
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
./gradlew :app:testDebugUnitTest                  # 85 tests (Gold Index 19 + drivers 12 + FRED feed 6 + brief feed 10 + brief JSON 5 + brief merge 6 + report 17 + schedule 8 + research dumps 2)
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

### Hosted AI brief feed (GitHub Action → `brief-data` branch)
Why: the grounded Gemini call takes 15-60s, which made it the slowest thing in the app, and without
a key the AI Brief and News tabs were simply empty. The Action generates the brief centrally with
the owner's key so every install reads a ~200ms static file instead.
- **The schedule is in AWS, not GitHub** (decided 2026-09-24, after the evidence below). An
  EventBridge rule fires the `aurum-brief-feed` Lambda at **:17 every hour**; the Lambda packages
  `.github/brief-feed/build_brief.py` **verbatim**, so there is one prompt, one validator and one
  output shape whichever side runs. It publishes the same single orphan commit (`brief_daily.json`
  + README) to `brief-data`, through the Git Data API (blob → tree → parentless commit → forced
  ref update) because Lambda has no git. Setup, cost and key handling: **`aws/brief-feed/README.md`**.
  The GitHub workflow keeps `workflow_dispatch` as a manual escape hatch and has **no schedule**.
- **Why it left GitHub.** `brief-feed.yml` went live on `master` at 2026-09-24 04:10 UTC and got
  **zero** dispatches in six hours across two cron variants (`5 * * * *`, then `17,47 * * * *`) —
  not a failed run, not a skipped one, with the workflow showing `active` — while `fred-feed.yml`
  kept getting its usual two a day. That matches the wider pattern: ~2 dispatches a day for this
  repo, clustered near 17:00 and 22:50 UTC, with **15-18 hour overnight gaps**. An hourly brief
  cannot live with that, and neither could the app's 12-hour staleness limit. See
  [[aurum-github-cron-unreliable]].
- **Spend did not change** — `build_brief.py` still skips any run that finds a published brief
  younger than **50 minutes** (`MIN_AGE_MINUTES`), so the cap is ~24 grounded calls a day whatever
  the trigger. The Lambda is inside the always-free tier (~730 requests, ~11k GB-seconds a month
  against 1M and 400k).
- **Likeliest silent failure: the GitHub PAT expiring.** Fine-grained, Contents read/write on this
  repo only. Nothing warns you; the feed just stops and the app ages the brief out.
  App URL: `https://raw.githubusercontent.com/bull88protocol/aurum/brief-data/brief_daily.json`.
- **Secret:** `GEMINI_API_KEY` (repo Settings → Secrets and variables → Actions). Never in the app,
  never in the repo. The key travels in the `x-goog-api-key` **header**, never in a URL, so it
  cannot leak into a run log. **Set 2026-09-24** by the owner as `bull88protocol`. A manual run
  (Actions → "AI brief feed" → Run workflow, which passes `--force`) also needs that login: the
  box's `gh` CLI is `CoinTranscend` and gets **HTTP 403 "Must have admin rights"** on dispatch, so
  it can read runs and branches but never start one.
- **Order in the app — the reverse of FRED, on purpose.** The feed comes first and a user's own key
  second. FRED's rule (user's key first) is right there because a live fetch is *fresher* than the
  last GitHub run. Here the user's key is the *slow* path, so the app paints the feed brief at once
  and replaces it with their own when the 15-60s call returns. A user's own brief less than an hour
  old (`DataRepository.OWN_KEY_FRESH_MS`) is kept as-is and no call is made at all.
- **Two jobs, not one.** `MainViewModel.refresh()` runs the market fetch and the brief fetch in
  parallel; only the market job drives the pull-to-refresh spinner. The brief job's stage two waits
  on the market job so the prompt is anchored to the freshly fetched quote (the v2.6.0 consistency
  rule). `SymbolState.carryingBriefFrom` / `withBrief` keep the two writers off each other's fields.
- **Provenance is shown.** A feed brief was written against the price at generation time, which can
  be an hour behind the quote on the Gold tab, so the tab prints "Shared brief · written 6:54 PM,
  23 Sep" rather than implying it is live.
- **Failure = safe:** bad key / Gemini down / a brief that fails validation (missing prose, fewer
  than 2 news items, a news item with no URL) → the run fails, nothing is published, GitHub emails
  the owner. The app drops any brief older than 12 hours (`BriefFeedClient.MAX_STALE_HOURS`).
- **The wire format is a *parsed* brief, not a model response** — `GeminiResultJson`'s field names,
  which the disk cache also uses. So `build_brief.py` and `GeminiClient.kt` may word their prompts
  differently without the app ever mis-parsing a feed: prompt drift costs quality, never
  correctness. `BriefFeedClientTest.parses_real_output_from_the_feed_generator` pins that contract
  against a committed fixture of real generator output.
- Local test without a key: `python3 .github/brief-feed/mock_server.py &` then
  `GEMINI_API_BASE=http://127.0.0.1:8731 YAHOO_API_BASE=http://127.0.0.1:8731 GEMINI_API_KEY=x
  python3 .github/brief-feed/build_brief.py --out /tmp/b.json`. The mock asserts the key stays in
  the header; `--thin` makes it return a brief that should fail validation.

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
