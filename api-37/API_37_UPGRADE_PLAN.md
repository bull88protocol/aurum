# API 37 (Android 17) upgrade — plan

Written 2026-09-03, while v2.6.0 / versionCode 14 was in Play review. **Nothing here is urgent.**
This is the next *forced* piece of work on the Android side; everything else outstanding is
optional store polish that was consciously skipped.

> **Branch:** do this on **`api-37`**, per the repo branch model — big/risky work goes on a
> temporary feature branch, is validated, then merges to `master`. Do **not** do it on `master`.
> `git checkout -b api-37`

---

## 0. START HERE — what this actually is

This is **not** a targetSdk bump. It is **three simultaneous major-version migrations**, and the
API level is the smallest part of it:

| | now | needed | jump |
|---|---|---|---|
| AGP | **8.10.1** | **≥ 9.1.1** | major (8.x → 9.x) |
| Gradle | **8.11.1** | **≥ 9.3.1** | major (8.x → 9.x) |
| Kotlin | **1.9.24** | **2.x** (AGP 9.4 pairs with 2.3.21) | major (1.x → 2.x) |
| JDK | 17 | **17 — no change** ✅ | none |
| compileSdk / targetSdk | 36 | 37 | the easy part |

Budget it as a multi-session job with a real chance of dependency fallout, not an afternoon.

### Deadline

**No official Play deadline for API 37 has been published.** Google's annual pattern (API 34 →
Aug 2024, 35 → Aug 2025, 36 → Aug 2026) and a Google DevRel comment from Feb 2026 both point at
**August 2027**. That is ~11 months out from this writing. There is no reason to rush, and good
reason to wait: AGP 9.x and Kotlin 2.x both get more stable every month.

**Revisit this plan around Q1–Q2 2027**, or sooner if a dependency forces the Kotlin bump first.

---

## 1. Findings from an actual trial run (2026-09-03)

These are measured, not assumed. A throwaway `api-37` branch was created, `compileSdk` set to 37,
and the build run. **The previous guess in `CLAUDE.md` — "AGP ≥ 8.13/9.x" — was directionally right
but too optimistic.**

**1a. API 37 is already GA, not a preview.** `sdkmanager --list` offers `platforms;android-37.0`
and `platforms;android-37.1` as normal stable packages; only `37.2-beta*` are betas. (The
`developer.android.com/about/versions/17/setup-sdk` page still says "Cinnamon Bun Preview" and is
stale — and its claim that **AGP 8.9.0-rc01+** suffices is wrong for the GA SDK. Do not trust it.)

**1b. `platforms;android-37.1` and `build-tools;37.0.0` are already installed on the Linux box.**
Installing them is done; no need to repeat it.

**1c. AGP 8.10.1 cannot build against compileSdk 37.** With `compileSdk = 37` it emits:

```
Android Gradle plugin ... has not been tested with compile SDK version 37.0
...
> Failed to find target with hash string 'android-37' in: .../android-sdk
```

AGP 8.10.1 resolves `compileSdk = 37` to the hash `android-37`, but Android 17 ships under the new
**minor-version scheme** (`android-37.0`, `android-37.1`). AGP 8.10.1 predates that scheme — it even
auto-installed `android-37.0` and then failed to look it up. **This is not suppressible.**
`android.suppressUnsupportedCompileSdk` silences the warning, not the lookup failure.

**1d. AGP 8.13 tops out at API 36.1** — so there is no 8.x escape hatch. Per Google's own
compatibility table, **AGP 9.1.1 is the minimum for API 37.0.**

**1e. JDK 17 is enough for AGP 9.** No JDK migration. The box has OpenJDK 17.0.20 and nothing else,
which is fine.

---

## 2. What will actually break

### 2a. AGP 9 has built-in Kotlin — the root plugin block changes

AGP 9.0 bundles Kotlin support and enables it by default; you no longer apply
`org.jetbrains.kotlin.android`. The current root `build.gradle.kts` applies it explicitly:

```kotlin
plugins {
    id("com.android.application")           version "8.10.1" apply false
    id("org.jetbrains.kotlin.android")      version "1.9.24" apply false   // ← goes away
    id("com.android.library")               version "8.10.1" apply false
    id("org.jetbrains.kotlin.multiplatform") version "1.9.24" apply false
}
```

### 2b. ✅ The KMP structure is ALREADY correct — this is the good news

AGP 9's new KMP integration **does not support the KMP plugin and the Android Application plugin in
the same Gradle subproject**; projects that mix them must extract the app into its own subproject.

**This project already has that shape.** `:shared` applies `kotlin("multiplatform")` +
`com.android.library`; `:app` applies `com.android.application`. They are separate modules. The
`ios-port` work in v2.1.0 accidentally pre-paid the single biggest AGP 9 migration cost. **Do not
undo that separation.**

### 2c. ✅ No legacy variant APIs

AGP 9 removes `applicationVariants` / `libraryVariants` in favour of `androidComponents`. A grep
across all `.kts` files finds **zero** uses. Nothing to migrate here.

### 2d. Kotlin 1.9 → 2.x is the real risk

The K2 compiler is the default in Kotlin 2.0. Specific exposure:

- **`:shared` is a KMP module.** KMP is where Kotlin major-version migrations hurt most. It is small
  (model + two engines + one expect/actual) and has one dependency, which limits the blast radius.
- **`kotlinx-datetime 0.5.0`** must move to a Kotlin-2.x-compatible release, in lockstep with the
  Kotlin version.
- **`kotlinx-coroutines-android 1.8.1`** likewise.
- `kotlinOptions { jvmTarget = "17" }` in both modules is deprecated in favour of the
  `compilerOptions` DSL; expect warnings, then removal.

### 2e. Dependencies that will need a sweep

All are ordinary AndroidX/Play bumps, none exotic, but each is a chance for a surprise:

| dependency | current | note |
|---|---|---|
| `androidx.security:security-crypto` | **1.1.0-alpha06** | An **alpha**, and already marked migration-only in `app/build.gradle.kts` (reads legacy `EncryptedSharedPreferences` into the Keystore-backed `SecurePrefs`). **Try to delete it during this work** rather than bump it — see §4. |
| `play-services-auth` | 21.0.0 | Google Sign-In; verify against AGP 9 |
| `work-runtime-ktx` | 2.9.1 | the 6 PM report worker rides on this |
| `biometric` | 1.1.0 | old; the gate on app launch |
| core-ktx / appcompat / material / activity / fragment / lifecycle | 2024-era | routine |

---

## 3. Order of operations

Do these as **separate commits**, each independently green. Do not do them as one change — if the
build breaks you want to know which migration did it.

1. **Gradle wrapper 8.11.1 → 9.x** *(alone)*. `./gradlew wrapper --gradle-version 9.3.1`.
   Build + test with AGP still at 8.10.1 if it will tolerate it; fix deprecations.
2. **Kotlin 1.9.24 → 2.x** *(alone)*, with `kotlinx-datetime` and `kotlinx-coroutines` bumped in the
   same commit since they are version-locked. **Run the full 55 tests.** This is the commit most
   likely to fail; the engines are pure Kotlin and well covered, which is the safety net.
3. **AGP 8.10.1 → 9.1.1+** *(alone)*. Remove the `kotlin.android` plugin per §2a. Expect the
   Upgrade Assistant to be useful here.
4. **compileSdk 36 → 37** *(alone)*, `targetSdk` still 36. Confirms the toolchain without changing
   any runtime behaviour. Also bump `shared/build.gradle.kts` — it pins `compileSdk = 36`
   **separately** and is easy to miss.
5. **targetSdk 36 → 37** — the only step that changes runtime behaviour. Read the Android 17
   behaviour-change list and audit against this app the way `release-2.3/RELEASE_NOTES.md` did for
   Android 16. Pay attention to: background work (the 6 PM `WorkManager` job), notifications (the
   report delivery + its actions), `FLAG_SECURE` / `SettingsActivity`, file sharing via
   `FileProvider` (Save to Downloads / Share), and biometric.
6. **Device pass, then ship** as its own release (2.7.0 / versionCode 15+).

## 4. Opportunistic cleanups to fold in

- **Delete `androidx.security:security-crypto`.** It is an alpha kept only to migrate legacy
  `EncryptedSharedPreferences` into `SecurePrefs`. Production has been on Keystore-backed storage
  since v2.0.0 / versionCode 6, and users have since jumped to 13. Anyone still holding legacy
  prefs is on a build from before that. Verify in `Crypto.kt` / `SecurePrefs.kt`, then drop it —
  removing an alpha dependency is worth more than bumping it.
- **Introduce a version catalog** (`gradle/libs.versions.toml`). Versions are currently inline
  strings across three build files; a catalog makes this migration and the next one far easier.
  There is no catalog today.
- **Move the tests to `:shared`/`commonTest`.** They still run from `:app` (see `CLAUDE.md`), which
  is a leftover from before the KMP split and a blocker for the iOS Phase 2 work anyway.

## 5. Do not

- **Do not** bump `targetSdk` and the toolchain in the same commit — you will not know what broke.
- **Do not** merge to `master` until the full 55 tests pass **and** a device pass is done. `master`
  is "always shippable" for both platforms.
- **Do not** collapse `:shared` and `:app` into one module (§2b).
- **Do not** trust `developer.android.com/about/versions/17/setup-sdk` on the AGP requirement — it
  says 8.9.0-rc01 and that is wrong for the GA SDK (§1a). The compatibility table on
  `developer.android.com/build/releases/about-agp` is authoritative: **AGP 9.1.1**.
- **Do not** start this while a release is in review.

## 6. Coordination with the iOS port

`ios/APPLE_RELEASE_PLAN.md` Phase 2 enables the iOS targets in `:shared` on a Mac. Both that work
and this one touch `shared/build.gradle.kts` and the Kotlin version. **Do not run them in
parallel** — a Kotlin 2.x migration and first-time Kotlin/Native target enablement colliding in the
same file would be miserable to debug. Land API 37 first; it is deadline-bearing and iOS is not.

## 7. Sources

- AGP ↔ API level and AGP ↔ Gradle compatibility tables — <https://developer.android.com/build/releases/about-agp>
- AGP 9.0 release notes (built-in Kotlin, KMP subproject rule, variant API removal) — <https://developer.android.com/build/releases/agp-9-0-0-release-notes>
- Play target API requirements — <https://developer.android.com/google/play/requirements/target-sdk>
