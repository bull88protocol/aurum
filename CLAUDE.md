# Aurum88 Protocol — project context for Claude Code

A bring-your-own-keys **gold-macro app**: a single 0–100 Gold Index (real yields, USD, central-bank
demand, inflation, technicals) + a forward signal, history chart, AI brief, news, and a second
instrument (the Dollar / DXY via the HMAI engine). No backend; runs on-device.

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

## Platforms & status
- **Android** — live on Google Play: v2.0.0 / versionCode 6. **v2.1.0 / versionCode 7** (Forward
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
  already correct and are untouched. Built + 30/30 tests green; **AAB and Play upload still to do**.
  See `release-2.4/RELEASE_NOTES.md`.
- **iOS** — parked for now (Apple App Store). Architecture + phased plan in **`ios/APPLE_RELEASE_PLAN.md`**.
  Decision: **Kotlin Multiplatform shared core + native SwiftUI**. Needs a Mac (Xcode is macOS-only).
  **Phase 1 code is on `master`** (rode the v2.1.0 merge): `:shared` KMP module with **the entire
  domain — `model` + both engines (`GoldIndexEngine` + HMAI) — in `commonMain`**; 29/29 tests green.
  When iOS resumes: network clients → Ktor, storage/biometric → expect/actual, tests → commonTest,
  then iOS targets + SwiftUI on the Mac (Phase 2).

## Repo layout
- `app/` — Android app (Kotlin). Holds `network/`, `data/`, `ui/` (engines now live in `:shared`).
- `shared/` — KMP module (added on `ios-port`). `commonMain` now has the **full domain** (`model/`,
  `domain/gold/` + `domain/hmai/`) and `util/formatDecimals` (expect/actual); deps: kotlinx-datetime.
  `androidTarget` only for now; iOS targets get enabled on the Mac (Phase 2). The app depends on `:shared`.
- `data/cb_quarterly.json` (hosted CB feed) · `release-2.0/` (v2.0 docs) · `ios/` (Apple plan) ·
  `cb-data/` (CB feed tool) · `research/` (Gold Index backtest: scripts + results; `cache/` gitignored,
  regenerate via `research/README.md`).
- **Target:** all engines + `model/` + `network/` in `shared/commonMain` (one source of truth);
  `app/` (Android UI) and `ios/` (SwiftUI UI) on top.

## Build / test the shared module
```bash
./gradlew :shared:assembleDebug          # build the KMP android artifact
./gradlew :app:testDebugUnitTest         # 29 engine tests (still run from :app for now)
```

## Branch model
- `master` — stable mainline for **both** platforms; always shippable.
- Big/risky work goes on a **temporary feature branch**, validated, then merged to `master`
  (e.g. `release-2.0` did this for v2.0; the iOS port uses **`ios-port`**).
- One repo, one `master` — never split Android and iOS onto separate long-lived branches (it would
  fork the shared core).

## Build / test (Android, from repo root)
```bash
source /home/sun/option_android/android_env.sh   # this Linux box only
./gradlew :app:assembleDebug                      # debug build
./gradlew :app:testDebugUnitTest                  # 29 engine tests (Gold Index 19 + HMAI 10)
./gradlew :app:bundleRelease                       # signed Play AAB (needs keystore.properties)
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
warning. AGP 8.10 tops out at API 36, so the next target bump (API 37) will need AGP ≥ 8.13/9.x
and probably Kotlin 2.x — treat that as a feature-branch job.

## Conventions
- **Commits are attributed to `aurum88p`. Do NOT add a `Co-Authored-By:` / Claude trailer.**
- **Distribution is store-only** (Google Play / App Store). **Never commit an APK/AAB** — `*.apk` /
  `*.aab` are gitignored. Testers onboard by email → see `TESTING.md`.
- **Secrets** live in `keystore.properties` (gitignored); never commit keys/keystores.
- The CB feed is a **git-pushed data file** read from `master` — update via
  `cb-data/cb_update.py … --push` (no app release). See `cb-data/README.md`.

## Key docs
- `ios/APPLE_RELEASE_PLAN.md` · `ios/APP_STORE_SUBMISSION_CHECKLIST.md` · `ios/MAC_SETUP.md`
- `release-2.0/RESUME.md` (v2.0 handoff) · `release-2.0/CHANGELOG.md` · `release-2.0/NEXT_RELEASE_PLAN.md`
- `TESTING.md` (tester onboarding) · `README.md` · `PRIVACY.md` · `TERMS.md`
