# Aurum88 Protocol v2.8 — Release Notes

## v2.8.0 (versionCode 16)

**Why this release exists:** the owner brought an outside proposal for a "Core Gold Signal" and asked
whether it should replace the Dollar tab: 50% the 20-day change in the 10Y TIPS real yield, 50% the
20-day change in the dollar, labelled as gold's macro direction. Backtested as written, it explains
gold's last 20 days well and predicts nothing. So it ships as a **20 Days** tab that says exactly
that, replacing the Dollar tab and its HMAI engine. The same release makes the 6 PM report complete
for users without a FRED key, through a data file the repo refreshes each weekday with the owner's
key. It also adds the notice FRED's API terms require, which the app had never shown.

**The Gold Index and the Forward Signal are unchanged.** Not one line of `GoldIndexEngine` changed.

> **Status 2026-09-16:** signed AAB built and verified (below). **Not uploaded, and must not be
> until the three gates in the upload checklist are met:** 2.7.0 approved, the FRED feed live, and
> an on-device pass. versionCode 16 is not claimed until upload, so the AAB can still be rebuilt
> if anything changes.

## What changed

| | 2.7.0 | 2.8.0 |
|---|---|---|
| Fourth tab | **Dollar**: DXY through the HMAI 6-pillar engine (RISK ON / CAUTION / RISK OFF) | **20 Days**: what real yields and the dollar did to gold over the last 20 trading days |
| 6 PM report, user without a FRED key | Real Yield, Inflation, Real-Rate Regime, Fed Cycle all "add a FRED key" | all scored, from the hosted FRED feed |
| FRED data for interactive refreshes | the user's own key | the user's own key (unchanged, owner's decision) |
| FRED API terms notice | missing | Settings, the 20 Days tab, TERMS.md §3a, PRIVACY.md §3 |
| Yahoo calls per refresh | 5 (GLD ×2, DXY intraday, DXY daily, VIX) | 3 (GLD ×2, DXY daily) |
| Unit tests | 55 | 64 |

## The 20 Days tab

A tailwind/headwind score from -100 to +100 with a one-year chart. It shows both drivers with
their 20-day moves (e.g. "2.62% ↑ +18 bp in 20 days") and splits gold's own 20-day move into a
real-yield part, a dollar part and **everything else**. The dollar half needs no key; real yields
use the user's FRED key. The copy says in two places that it explains and does not forecast, and
points to the Forward Signal for the outlook.

**Why it is framed that way.** `research/DRIVERS_20D_2026-09-16.md` has every number and the
scripts.
- **The proposal as written** (2007 to 2026-09-15): Spearman **+0.56** with gold's return over the
  same 20 days, **+0.04** with the next 20, **-0.02** with the next 3 months.
- **The shipped spec** (the app's own inputs, GLD and DXY): +0.51 / +0.06 / -0.01.
- **Robustness:** weights, window length, the Fed's broad dollar vs DXY and the scaling curve all
  measured the same.
- **The dropped Confidence flag:** none of its measurable stand-ins carry forward information.
- **Where this came from before:** it is the retired v1 forward signal's two biggest sleeves again.

**Engine:** `shared/…/domain/gold/GoldDriversEngine.kt`, pure `commonMain`.
- **Legs.** Each leg is the 20-observation change on the series' own spine, over the sample std of
  its last 252 such changes, mapped to -100 × clamp(z/2, -1, 1).
- **Headline.** The mean of the available legs.
- **Breakdown.** Fixed betas from a 2007-2026 regression: -6.6% gold per +1 pt real yield, -0.76%
  per +1% DXY, R² 0.29. The split is approximate and the tab says so.

**The HMAI engine is deleted:** all nine files in `shared/…/domain/hmai/`, its models, its cache
serialization, its card in `fragment_quote.xml`, its test class (10 tests) and the VIX fetch that
only fed it. GLD is now the only symbol. `MainViewModel` ignores `DX-Y.NYB` entries left in the
cache by older builds: `refresh()` would mark such an entry loading and never clear it, which is
the endless spinner 2.7.0 fixed.

## The hosted FRED feed

- **What publishes it.** `.github/workflows/fred-feed.yml`, **live on `master` since 2026-09-16**,
  fetches DFII10 (6y), T10YIE (3y) and DGS2 (3y) with the owner's key. The key lives in the repo
  secret `FRED_API_KEY`. The workflow publishes `fred_daily.json` as a single force-pushed commit
  on the `fred-data` branch.
- **When it runs.** Weekdays every 20 minutes from 20:10 to 22:50 UTC, which covers the Fed's
  4:15 PM ET release to the 6 PM report in both summer and winter time, plus 12:30 UTC. It
  publishes only when the data changed.
- **Who reads it.** Only `DailyRefreshWorker`, through `FredFeedClient`, from
  `https://raw.githubusercontent.com/bull88protocol/aurum/fred-data/fred_daily.json`.
- **Fallbacks.** Any series the feed lacks, or whose latest print is over 10 days old, falls back to
  the user's key.
- **Failure is safe.** Until the secret exists, runs skip with a warning. With a bad key, FRED
  down, or short, stale or out-of-range data, the run fails, publishes nothing, and GitHub emails
  the owner. The key is never printed.
- **Why not ship the owner's key in the app.** A key in an APK can be extracted. FRED makes the
  key holder "solely responsible" for all use. Every install fires the report at 18:00 ET, so
  about 40 phones × 3 calls would hit the ~120 requests/minute per-key limit.

## Files

| File | Change |
|---|---|
| `shared/…/domain/gold/GoldDriversEngine.kt` | **new**: the 20-day drivers engine |
| `shared/…/model/Models.kt` | `DriverLeg`, `DriversReport`, `SymbolState.driversReport`; HMAI models removed |
| `shared/…/domain/hmai/*` (9 files) | **deleted** |
| `app/…/network/FredFeedClient.kt` | **new**: fetch, parse and freshness rules for the hosted feed |
| `app/…/data/DataRepository.kt` | computes the drivers report; `fredFeed` param; VIX and shared-DXY pre-fetch removed |
| `app/…/worker/DailyRefreshWorker.kt` | fetches the feed for the report; `hasFredKey` counts the feed |
| `app/…/MainViewModel.kt` | `SYMBOLS = ["GLD"]`; filters retired cache entries |
| `app/…/ui/DriversFragment.kt` · `DriversChartView.kt` · `layout/fragment_drivers.xml` | **new**: the tab |
| `app/…/ui/QuotePagerAdapter.kt` | tab 4 is "20 Days" |
| `app/…/ui/QuoteFragment.kt` · `layout/fragment_quote.xml` | HMAI card removed |
| `app/…/data/DataCache.kt` | drivers report serialized; HMAI removed |
| `app/…/network/YahooFinanceClient.kt` | `fetchVix` removed |
| `app/…/layout/activity_settings.xml` · `MainActivity.kt` | FRED notice + ToU link; key copy mentions the report |
| `app/build.gradle.kts` | versionCode 16 / 2.8.0; `testImplementation("org.json:json:20180813")` |
| `app/src/test/…` | `GoldDriversEngineTest` (12), `FredFeedClientTest` (6), drivers dump in `EngineHistoryDumpTest`; `HmaiEngineTest` deleted |
| `.github/workflows/fred-feed.yml` · `.github/fred-feed/build_feed.py` | **new**: the feed (commit `487116f`, on `master`) |
| `TERMS.md` · `PRIVACY.md` · `README.md` · `CLAUDE.md` | FRED terms, the data file, the new tab |
| `research/DRIVERS_20D_2026-09-16.md` · `research/scripts/drivers-2026-09/` | **new**: the research |

## Verification performed

- **Unit tests.** `:app:testDebugUnitTest` ran **64 tests, 0 failures**: Gold Index 19, drivers 12,
  FRED feed 6, report 17, schedule 8, research dumps 2. The feed-parser tests run on the real
  org.json; android.jar's copy is a throwing stub.
- **Engine parity.** `GoldDriversEngine` replayed over the full research history
  (`EngineHistoryDumpTest.dumpDriversHistory`) against the Python replica the backtest used:
  **5,438 days, max |diff| 1.1e-5, 0 availability mismatches**.
- **Feed builder against a local mock of the FRED API**, serving real FRED data:
  - normal publish: DFII10 1,499, T10YIE 748 and DGS2 748 observations, 54 KB;
  - an unchanged re-run publishes nothing;
  - a bad key fails fast with HTTP 400;
  - stale data is refused;
  - a missing key gives a clear message;
  - the key never appears in any log or output.
- **End to end.** The builder's output went through the Kotlin `FredFeedClient.parse` into
  `GoldDriversEngine`. Parsed as of 16 days later, every series is dropped.
- **Publish step.** Replayed against a local bare repo: the first run creates `fred-data`, the second
  force-replaces it (still one commit), and the workflow's read-back step reads it.
- **GitHub.** The workflow is registered and `active` (checked via the API, 2026-09-16). The
  missing-secret skip was verified locally only.
- **Release builds.** `:app:assembleRelease` and `:app:bundleRelease` are clean, with R8 full mode
  and lint vital.
- **Signature.** `jarsigner -verify` → **jar verified**, `CN=Bull88 Protocol, O=CoinTranscend,
  C=US`, SHA-1 `51:24:2A:…:C4:B8:F3`, the upload key.
- **AAB manifest** (aapt2 on the proto manifest): **`com.sun.aurum` · versionCode 16 ·
  versionName 2.8.0** · minSdk 26 · targetSdk and compileSdk 36. No debug suffix.
- **The AAB file.** `app/build/outputs/bundle/release/app-release.aab`, 4,419,817 bytes, sha256
  `328de905e6b19307f427da80abfda284a62dcc22c033d023be5c956d71433f6c`, built from **`79cb47c`** on
  `feat/20-day-drivers`.

**Not verified:**
- **On a device.** None was connected. The tab has never been seen rendered: layout, chart,
  colours, the no-key state.
- **A real run of the workflow.** It needs the secret. Nor has the app read the real raw URL.
- **The keyless 6 PM report on a phone.**

## What's new (Play "What's new" copy)

Paste-ready, 386 characters — within Play's 500-character limit. The second bullet is only true
once the feed is live (gate 2).

> What's new in 2.8:
> • New 20 Days tab, replacing the Dollar tab: see what real yields and the US dollar did to gold over the last 20 trading days, with a one-year chart and gold's move broken down into rates, the dollar and everything else. It explains recent moves; it is not a forecast.
> • The 6 PM daily report now scores real yields and inflation even if you haven't added a FRED key.

## Play upload checklist

1. ~~`./gradlew :app:bundleRelease`~~ — **done 2026-09-16**, verified above.
2. ~~`jarsigner -verify`~~ — **done**, jar verified with the Bull88 upload key.
3. **[gate] v2.7.0 approved.** It was submitted 2026-09-04 and CLAUDE.md still shows it in review.
   A newer release on the same track replaces the one under review and restarts the wait, so check
   the Play Console first.
4. **[gate] FRED feed live.**
   - Add the repo secret `FRED_API_KEY` under GitHub → Settings → Secrets and variables → Actions.
   - Go to Actions → "FRED feed" → **Run workflow**. The run should end with "Published FRED feed
     through <date>".
   - Open the raw URL above and check the dates are current.
   - If this is skipped, keyless users' reports stay incomplete, and the Settings copy, the welcome
     dialog and the "What's new" all say otherwise.
5. **[gate] On-device pass.** The debug variant installs alongside the Play build; the recipe is
   below. Check:
   - the 20 Days tab renders with and without a FRED key;
   - pull to refresh works;
   - a force-run worker with **no key** in the debug app produces a PDF whose Real Yield,
     Inflation, Real-Rate Regime and Fed Cycle rows are scored.
6. **Merge and tag.** Merge `feat/20-day-drivers` into `master`, tag **`v2.8.0`**, push.
7. **Upload.** Play Console → Production → Create new release → upload **`app-release.aab`
   (~4.4 MB)**, never the ~9 MB debug APK, which fails as a package-name error. It should show as
   "16 (2.8.0)". Paste the "What's new" copy and roll out. The upload claims versionCode 16 for
   good.
8. **After rollout.** Check the crash rate on the new tab, and that a keyless install's first 6 PM
   report has the FRED rows scored.

**On-device recipe** (all non-destructive; the debug build is `com.sun.aurum.debug`):
```bash
source /home/sun/option_android/android_env.sh
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
# open the app → 20 Days tab. Screencap is blacked out by the biometric overlay; read values with:
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
# keyless report via the feed: clear the FRED key in the debug app's Settings, then force-run the worker
adb shell dumpsys jobscheduler | grep -oE "JOB #[^ ]+ com.sun.aurum.debug[^ ]*"
adb shell cmd jobscheduler run -f com.sun.aurum.debug <jobId>
adb pull /sdcard/Android/data/com.sun.aurum.debug/files/reports/
```

## Follow-ups (not blocking)

- **Store listing and screenshots don't mention the 20 Days tab.** This joins the store items already
  open (no PDF screenshot, v1 forward card in `02_*.png`).
- **`ios/APPLE_RELEASE_PLAN.md` still lists HMAI in the shared core.** The engine is gone; the iOS
  port now has one fewer engine to carry.
- **GitHub auto-disables scheduled workflows in public repos after 60 days without repository
  activity.** It is unclear whether the bot's pushes count. If reports ever show old yields, check
  Actions → "FRED feed".
- **A keyless user sees full FRED rows after opening the app on the report's cached data**, and "needs
  a FRED key" again after pulling to refresh. That is the reports-only decision working as designed.
