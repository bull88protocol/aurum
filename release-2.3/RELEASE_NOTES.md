# Aurum88 Protocol v2.3 — Release Notes

## v2.3.0 (versionCode 10)

**Why this release exists:** Google Play flagged the app as non-compliant with its target API
level policy — *"App must target Android 16 (API level 36) or higher."* Highest non-compliant
target was **Android 15 (API 35)**. From **Aug 30, 2026** an app whose target API level is not
within one year of the latest Android release can no longer be updated on Play. This release
raises the target and, unavoidably, the build toolchain with it.

**Supersedes v2.2.0 (code 9), which is skipped** — its signed AAB (built 2026-07-12 from
`cafbb5e`) was never uploaded and targets API 35, so it is stale either way. Its two audit
adjustments plus the v2.1.1 (code 8) Clear-Cache fix are all contained here. Same precedent as
code 8; Play has no problem with skipped versionCodes (last uploaded release is still 7 / 2.1.0).

## What changed

| | Before | After |
|---|---|---|
| `targetSdk` / `compileSdk` (`:app`) | 35 | **36** |
| `compileSdk` (`:shared`) | 35 | **36** |
| Android Gradle Plugin | 8.6.0 | **8.10.1** |
| Gradle wrapper | 8.7 | **8.11.1** |
| Kotlin | 1.9.24 | 1.9.24 (unchanged) |
| JDK | 17 | 17 (unchanged) |

AGP 8.6.0 builds against `compileSdk 36` but prints *"This Android Gradle plugin was tested up to
compileSdk = 35 … you are strongly encouraged to update"*. **AGP 8.10 is the lowest version whose
maximum supported API level is 36**, and it requires Gradle 8.11.1 + JDK 17. That combination is
what shipped here, so the release is built on a supported toolchain rather than on a suppressed
warning (`android.suppressUnsupportedCompileSdk` was deliberately *not* used). Kotlin stayed at
1.9.24 — it drives the KMP `:shared` module and needed no bump, keeping the iOS Phase 1 core
untouched.

New local SDK requirement: `platforms;android-36`. `build-tools;36.0.0` was installed alongside
it but is not required (AGP 8.10 defaults to 35.0.0, minimum 35.0.0).

## Android 16 behaviour changes — audit

Each targetSdk-36 behaviour change was checked against this app. **No code changes were needed:**

- **Edge-to-edge enforced, opt-out ignored.** The manifest never set
  `windowOptOutEdgeToEdgeEnforcement`, so the app has been edge-to-edge since it targeted 35.
  `MainActivity` already applies insets — `systemBars().top` padding on the app bar,
  `systemBars().bottom` on the tagline. No regression path.
- **Adaptive apps: orientation / resizability / aspect-ratio restrictions ignored on displays
  ≥ 600dp.** The manifest declares no `screenOrientation`, `resizableActivity` or
  `maxAspectRatio` on either activity, so nothing was being relied on.
- **Predictive back on by default.** No `onBackPressed()` override and no `OnBackPressedCallback`
  anywhere in `app/src` — the app uses stock back behaviour, which the system animates.
- **16 KB page size.** The AAB contains **no** `.so` files (pure Kotlin/Java + AndroidX), so the
  native-alignment requirement does not apply.
- **JobScheduler quota tightening.** Background work goes through WorkManager 2.9.1, which
  absorbs this; the only job is the daily refresh worker.
- `Build.VERSION` is consulted in exactly one place (`MainActivity.kt:119`, the `TIRAMISU`
  POST_NOTIFICATIONS gate) — unaffected.

`android:statusBarColor` / `navigationBarColor` in `Theme.Aurum` are already inert under
edge-to-edge enforcement (since API 35) and were left in place as harmless.

## Verification performed

- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**, no `compileSdk` warning, no
  AGP↔Kotlin-plugin compatibility warning.
- `./gradlew :app:testDebugUnitTest --rerun-tasks` — **tests=30 failures=0 errors=0**
  (Gold Index 19 + HMAI 10 + 1 research history dump).
- `./gradlew clean :app:bundleRelease` — **BUILD SUCCESSFUL** from clean, including
  `lintVitalRelease` and `minifyReleaseWithR8`.
- Merged release manifest asserts `versionCode="10" versionName="2.3.0" minSdkVersion="26"
  **targetSdkVersion="36"**`.
- `jarsigner -verify app/build/outputs/bundle/release/app-release.aab` → **jar verified**
  (upload key, cert valid to 2053-10-19). The "signed in JarFile but not in JarInputStream"
  lines are the usual benign AAB/jarsigner notices.

**Not yet done — no Android device was attached to this machine.** An on-device smoke test is the
one outstanding check; see step 4 below. It matters more than usual for a targetSdk bump because
edge-to-edge and predictive back are runtime-only behaviours.

## What's new (Play "What's new" copy)

> • Updated for Android 16.
>
> • Central-bank data: the built-in 2025 figure now uses the published WGC actual (863 t), for
> installs running without the live feed.
>
> • The Forward Signal card shows a caution note whenever current conditions read HOT (index ≥ 70)
> — historically a sign of stretched conditions, not strength.
>
> • Clear Cache now also refreshes the central-bank data feed on demand.

## Carried forward from v2.2.0 (code 9, skipped)

Full detail in `release-2.2/RELEASE_NOTES.md` — unchanged by this release:

- **CB bundled fallback 2025 → 863 t actual** (`GoldIndexEngine.kt`); `CB_ESTIMATE_FROM_YEAR`
  moved to 2026. No-feed path only.
- **Spot-HOT caution chip** (`fragment_quote.xml` + `QuoteFragment`), visible only at spot ≥ 70.
- From v2.1.1 (code 8, skipped): **Clear Cache also invalidates the 7-day CB feed cache.**

## Play upload checklist

1. Play Console → Aurum88 Protocol → Test and release → **Internal testing** → Create new release.
2. Upload `app/build/outputs/bundle/release/app-release.aab` (release name auto-fills
   "10 (2.3.0)").
3. Paste the "What's new" copy above → Save → Review → Roll out to internal testing.
4. **On-device smoke test** (internal opt-in link) — the outstanding verification:
   - App launches; confirm version **2.3.0** in settings.
   - Nothing is drawn under the status bar or nav bar (edge-to-edge insets still correct).
   - Back gesture works from both tabs and from Settings (predictive back is now on by default).
   - Biometric unlock and Google Sign-In still work.
   - Gold tab CB row still shows a live-feed label ("as of 2026-Q1"). As in 2.2.0, the 863 t
     fallback and the caution chip are both dormant while the feed is healthy and spot is low —
     "nothing looks different" plus a working index **is** the expected result.
5. Promote to **Production** when satisfied. Play only clears the target-API warning once a
   **production** release targets 36 — internal testing alone will not dismiss it.

## Follow-ups (not blocking)

- Play's notice is annual: API 37 will carry the same one-year rule. AGP 8.10 tops out at API 36,
  so the next bump needs AGP ≥ 8.13 / 9.x, which is likely to force Kotlin 2.x and a real KMP
  migration — worth doing on a feature branch, not under a deadline.
- `SettingsActivity.kt:128/139` still uses the deprecated `startActivityForResult` /
  `onActivityResult` pair (compiler warnings, not errors). Migrate to the Activity Result API.
- Store screenshot `store/screenshots/02_*.png` still shows the v1 forward card (carried from 2.2).
- `research/results/monthly_final.csv` predates the 2026-07-03 CB feed update (carried from 2.2).
