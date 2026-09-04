# Aurum88 Protocol — project context for Claude Code

A bring-your-own-keys **gold-macro app**: a single 0–100 Gold Index (real yields, USD, central-bank
demand, inflation, technicals) + a forward signal, history chart, AI brief, news, and a second
instrument (the Dollar / DXY via the HMAI engine). No backend; runs on-device. Since v2.5.0 the
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

> **This file is the cross-machine source of truth.** Claude Code's memory is per-machine and does
> **not** sync. When working from a different computer (e.g. a Mac for the iOS build), this committed
> file — plus the docs it points to — is the context. Keep it current.

## ▶ "What is pending?" — answer from the Open items list below
If the user asks **"what is pending"** / "what's left" / "where were we", read **§Open items** below
and show them **all** of it, most-actionable first, with a one-line status on the release in flight.
Do not improvise a list from git log — that section is the maintained answer. Verify anything
time-sensitive (Play status, whether a build is stale) before repeating it.

## ▶ Release in flight — v2.7.0 submitted to Production, awaiting review
**v2.7.0 / versionCode 15 was uploaded to Google Play Production on 2026-09-04, rolling out to
100%, and is in review.** Code merged to `master`, tagged **`v2.7.0`**, pushed. Do not rebuild, do
not bump the version, do not re-upload — **15 is claimed**. Start-here doc:
**`release-2.7/RELEASE_NOTES.md`**.

It fixes the refresh hang reported the same day. It ships days after 2.6.0 rather than after a soak
week, at the owner's direction, so the two **overlap in Play vitals** — a new signal in the next
fortnight cannot be cleanly attributed to one release. **Watch ANR rate first**: this release
changes network timeout and cancellation behaviour app-wide.

## Open items (nothing here is blocking; reviewed 2026-09-04)

The maintained answer to "what is pending". Ordered by what actually matters. Keep it current —
when an item is done, delete it rather than leaving it ticked.

1. **Confirm v2.7.0 clears Play review**, then update the status block above and tick the checklist
   in `release-2.7/RELEASE_NOTES.md`. Submitted 2026-09-04; 2.6.0 took ~1 day, 2.5.0 took ~11.
2. **Watch ANR rate once 2.7.0 rolls out** — see the caveat above about overlapping vitals. This is
   the highest-value thing to look at, and the reason is specific: the fix changed cancellation and
   timeout behaviour on every screen.
3. **PDF "Open" tile on a gap day.** The one v2.6.0 fix never confirmed in the wild — the old bug
   (previous close shown as the open) was only visible when the previous close fell outside the
   day's range. One look, next time gold gaps.
4. **Duplicate "Aurum Market Data" spreadsheets in Drive.** v2.7.0 stops new ones; it does **not**
   clean up existing ones. Delete strays by hand.
5. **`resolveOpen` and the refresh-timeout paths have no unit tests** — `org.json` is stubbed in
   Android unit tests and `MainViewModel` needs a context. Moving the pure logic into `:shared`
   fixes both; folded into `api-37/API_37_UPGRADE_PLAN.md` §4.
6. **API 37 / Android 17** — the next *forced* work, and the only item with a deadline. Needs
   AGP 9.1.1 + Gradle 9.3.1 + Kotlin 2.x (three major migrations; JDK 17 still fine). No Play
   deadline published; the annual pattern points at **August 2027**. Revisit Q1-Q2 2027.
   Plan, with a trial run behind it: `api-37/API_37_UPGRADE_PLAN.md`.
7. **Store polish — consciously skipped 2026-09-04, not forgotten.** No screenshot shows the PDF
   report; `store/screenshots/02_*.png` still pictures the v1 forward card (stale since 2.2); the
   live full description was never confirmed against `store/STORE_LISTING.md`; the Play R8
   recommendation card was never read (the build already runs R8 full mode, so it is almost
   certainly generic). All store-side, no release needed, can land any time.
8. **iOS Phase 2** — parked, needs a Mac. `ios/APPLE_RELEASE_PLAN.md`. Do **not** run it in
   parallel with the API 37 work; both touch `shared/build.gradle.kts` and the Kotlin version.

## Platforms & status
- **Android** — **live on Google Play production: v2.5.0 / versionCode 13** (approved 2026-08-20;
  previous production build was v2.0.0 / versionCode 6, so upgrading users jump five releases).
  **v2.6.0 / versionCode 14 — approved and live on Production 2026-09-04** (submitted 09-03).
  Edge-to-edge fixes + a real Settings toolbar, the GLD open mapping (the "Open" tile had always
  shown the previous close), and the AI brief anchored to the app's own market data. Verified on
  device 2026-09-04 **after** release: Settings toolbar, "Navigate up" button and status-bar inset
  all render correctly. See `release-2.6/RELEASE_NOTES.md`.
  **v2.7.0 / versionCode 15** — **submitted to Production 2026-09-04, rolling out to 100%, awaiting
  review.** Bounds an unbounded refresh that could spin forever:
  `callTimeout` on all five HTTP clients, Yahoo retries 3→2, `fetchAll`'s pre-loop work guarded (the
  Sheets sync runs only when signed in, which is why the hang looked login-specific), a 180s ceiling
  on `refresh()`, and a Retry button. Also stops `fetchLiveQuotes` minting a duplicate Drive
  spreadsheet on any transient failure. Verified on a Pixel 8a: radios off → error + RETRY button
  instead of a spinner, recovers when tapped. See `release-2.7/RELEASE_NOTES.md`.
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
  `domain/gold/` + `domain/hmai/`) and `util/formatDecimals` (expect/actual); deps: kotlinx-datetime.
  `androidTarget` only for now; iOS targets get enabled on the Mac (Phase 2). The app depends on `:shared`.
- `data/cb_quarterly.json` (hosted CB feed) · `release-2.0/` (v2.0 docs) · `ios/` (Apple plan) ·
  `release-2.0/cb-data/` (CB feed tool) · `research/` (Gold Index backtest: scripts + results; `cache/` gitignored,
  regenerate via `research/README.md`).
- **Target:** all engines + `model/` + `network/` in `shared/commonMain` (one source of truth);
  `app/` (Android UI) and `ios/` (SwiftUI UI) on top.

## Build / test the shared module
```bash
./gradlew :shared:assembleDebug          # build the KMP android artifact
./gradlew :app:testDebugUnitTest         # 55 tests (still run from :app for now)
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
./gradlew :app:testDebugUnitTest                  # 55 tests (Gold Index 19 + HMAI 10 + report 17 + schedule 8 + 1)
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
- `release-2.7/RELEASE_NOTES.md` (current release) · `release-2.6/RELEASE_NOTES.md`
- `api-37/API_37_UPGRADE_PLAN.md` (next forced Android work — AGP 9 / Gradle 9 / Kotlin 2)
- `TESTING.md` (tester onboarding) · `README.md` · `PRIVACY.md` · `TERMS.md`
