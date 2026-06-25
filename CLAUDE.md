# Aurum88 Protocol — project context for Claude Code

A bring-your-own-keys **gold-macro app**: a single 0–100 Gold Index (real yields, USD, central-bank
demand, inflation, technicals) + a forward signal, history chart, AI brief, news, and a second
instrument (the Dollar / DXY via the HMAI engine). No backend; runs on-device.

> **This file is the cross-machine source of truth.** Claude Code's memory is per-machine and does
> **not** sync. When working from a different computer (e.g. a Mac for the iOS build), this committed
> file — plus the docs it points to — is the context. Keep it current.

## Platforms & status
- **Android** — live: **v2.0.0 / versionCode 6** on `master`, merged 2026-06-24, on Google Play.
- **iOS** — in progress (Apple App Store). Architecture + phased plan in **`ios/APPLE_RELEASE_PLAN.md`**.
  Decision: **Kotlin Multiplatform shared core + native SwiftUI**. Needs a Mac (Xcode is macOS-only).
  **Phase 1 underway on branch `ios-port`:** `:shared` KMP module stood up; `model` + the HMAI engine
  migrated to `commonMain`; 23/23 tests green. Next: migrate `GoldIndexEngine` (→ `kotlinx-datetime`,
  replace `TreeMap`, lift `FredClient.Obs` into `model`), then network (Ktor) + storage (expect/actual).

## Repo layout
- `app/` — Android app (Kotlin). Still holds `GoldIndexEngine`, `network/`, `data/`, `ui/`.
- `shared/` — KMP module (added on `ios-port`). `commonMain` now has `model/`, the HMAI engine
  (`domain/hmai/`), and a `util/` `formatDecimals` expect/actual. `androidTarget` only for now; iOS
  targets get enabled on the Mac (Phase 2). The Android app depends on `:shared`.
- `data/cb_quarterly.json` (hosted CB feed) · `release-2.0/` (v2.0 docs) · `ios/` (Apple plan) ·
  `cb-data/` (CB feed tool).
- **Target:** all engines + `model/` + `network/` in `shared/commonMain` (one source of truth);
  `app/` (Android UI) and `ios/` (SwiftUI UI) on top.

## Build / test the shared module
```bash
./gradlew :shared:assembleDebug          # build the KMP android artifact
./gradlew :app:testDebugUnitTest         # 23 engine tests (still run from :app for now)
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
./gradlew :app:testDebugUnitTest                  # 23 engine tests (Gold Index 13 + HMAI 10)
./gradlew :app:bundleRelease                       # signed Play AAB (needs keystore.properties)
```

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
