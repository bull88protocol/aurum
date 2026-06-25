# Aurum88 Protocol — Apple App Store Release Plan

_Written as the plan I'd actually run: an Apple platform lead's engineering judgment, with a
gold pro-trader's view of what has to be perfect. Last updated 2026-06-25._

---

> ## ▶ Resume here (next session)
>
> **Phase 1 (the KMP shared core) is in progress on branch `ios-port`** (pushed to `origin`).
> All work so far is on Linux — no Mac needed yet. `master` is untouched on the shipped Android v2.0.
>
> - **Done & green** (Android builds, **23/23** tests): the **entire domain** — `model/` + both engines
>   (`GoldIndexEngine` + HMAI) + `util/formatDecimals` — is migrated to `shared/commonMain`. See the
>   **Phase 1 progress** section below for the play-by-play and commits (`baf3bb0`→`83c2f5f`).
> - **Next task:** migrate the **`network/` clients** (`YahooFinanceClient`, `FredClient`,
>   `GeminiClient`, `GoogleSheetsClient`, `CentralBankClient`) into `shared/commonMain` on **Ktor**
>   (replace OkHttp; Darwin engine for iOS) + **kotlinx.serialization** (replace `org.json`). Then
>   storage/biometric → `expect/actual`, engine tests → `commonTest`, then Phase 2 (SwiftUI) on the Mac.
> - **Keep it green at every step:** `source /home/sun/option_android/android_env.sh` then
>   `./gradlew :app:assembleDebug :app:testDebugUnitTest` (must stay 23/23).

---

## 0. TL;DR — the recommendation

**Ship a native SwiftUI app on top of a Kotlin Multiplatform (KMP) shared core.**

- **Share** the proven, unit-tested brain — the scoring engines, the data clients, and the models —
  as one KMP module compiled to an `.xcframework`. This is the *single source of truth* for the
  numbers. On a trading product, the iOS Gold Index **must** equal the Android one to the decimal;
  re-implementing the math in Swift would fork it and, sooner or later, betray a user who sees 71 on
  their phone and 68 on their iPad. We already have 23 engine tests — run them against the iOS target
  and we keep parity for free.
- **Rewrite** the UI natively in **SwiftUI** for a first-class Apple experience (HIG, Dynamic Type,
  dark mode, Face ID, Swift Charts, widgets, Watch). No WebView, no Flutter — this is a glanceable
  trader tool and it should feel like Apple built it.

This is the lowest-risk path to a high-quality iOS app **and** the cheapest to maintain: fix the
model once, both platforms get it.

> ⚠️ **Hard prerequisite:** iOS apps can only be built, signed, and submitted from **macOS + Xcode**.
> The current dev box is Linux. You can write/test the KMP shared module on Linux, but the iOS
> framework link, the SwiftUI app, TestFlight, and App Store submission **require a Mac** (or a
> rented cloud Mac / Xcode Cloud). Treat "get a Mac" as Phase 0, step 1.

---

## Phase 1 progress — branch `ios-port` (started 2026-06-25)

Real migration underway on Linux (no Mac needed yet); Android stays green throughout.

- ✅ **`:shared` KMP module stood up** — KMP toolchain proven in this environment (Kotlin 1.9.24 /
  AGP 8.6). `androidTarget` only for now; iOS targets get enabled on the Mac (Phase 2).
- ✅ **`model/` migrated** to `commonMain`. Cost: a 5-site "cross-module smart-cast tax" in the app
  (Kotlin won't smart-cast a nullable property declared in another module) — fixed with safe calls.
- ✅ **HMAI engine migrated** (`HmaiEngine`, 6 pillars, `CircuitBreaker`, `TechnicalIndicators`) to
  `commonMain`, behind an `expect/actual formatDecimals` (androidMain → `String.format`, byte-identical).
- ✅ **`GoldIndexEngine` migrated** — the hard one. `FredClient.Obs` lifted into `model.FredObs`;
  `java.util` dates → `kotlinx-datetime` (`Clock`/`Instant`/America-New_York); `TreeMap.headMap`
  windows → a `SortedDateSeries` (sort once, binary-search per query — same values, keeps the perf
  canary green); `String.format` → `formatDecimals`. **The entire scoring brain is now shared.**
- ✅ **23/23 engine tests green** against `:shared` (still run from `:app`; behavior verified identical,
  including the exact-value CB assertions and the <15s perf canary).
- ⏭️ **Next:** network clients → Ktor (`OkHttp`→Ktor, `org.json`→kotlinx.serialization); storage/
  caching/biometric → `expect/actual`; move the engine tests to `commonTest` (kotlin.test); then on
  the Mac, enable the iOS targets and stand up the SwiftUI app (Phase 2).

Commits: `baf3bb0` (module + model), `8e8bea4` (HMAI), `d3dbcd6` (FredObs), `83c2f5f` (GoldIndexEngine).

---

## 1. What we're porting

The Android app is small and well-layered — **35 Kotlin files, ~4.5k LOC**. That's a feature: most
of the value is in a thin, portable core.

| Layer | Files | Port strategy |
|---|---|---|
| **`domain/`** — Gold Index engine + 6 HMAI pillars + circuit breaker + technical indicators | `GoldIndexEngine`, `HmaiEngine`, `Pillar1–6`, `CircuitBreaker`, `TechnicalIndicators` | **Share via KMP** (the crown jewels; already tested) |
| **`model/`** — data classes (`Candle`, `QuoteData`, `SymbolState`, `HmaiReport`, `GeminiResult`, …) | `Models.kt` | **Share via KMP** |
| **`network/`** — REST clients | `Yahoo`, `Fred`, `Gemini`, `GoogleSheets`, `CentralBank` | **Share via KMP** (swap OkHttp→Ktor, org.json→kotlinx.serialization) |
| **`data/`** — repo + caches + secure storage + auth | `DataRepository`, `*Cache`, `Crypto`/`SecurePrefs`, `BiometricAuth`, `GoogleAuthManager` | **Split:** orchestration shared; storage/crypto/auth behind `expect/actual` |
| **`worker/`** — daily refresh | `DailyRefreshWorker` (WorkManager) | **Rewrite** → `BGTaskScheduler` + local notifications |
| **`ui/`** — fragments, pager, custom chart views | `MainActivity`, `*Fragment`, `*ChartView`, `QuotePagerAdapter`, `SettingsActivity` | **Rewrite** in SwiftUI |
| **app/VM** | `MainViewModel`, `AurumApp` | VM logic mostly shareable; iOS gets an `@Observable` wrapper |

**Rough split:** ~2.0–2.5k LOC of shareable core, ~2k LOC of UI/platform to rewrite natively.

---

## 2. Architecture — options weighed

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **A. KMP core + SwiftUI** *(recommended)* | One source of truth for the model; reuse 23 tests; native UX; smallest long-term maintenance | KMP toolchain; needs a few `expect/actual` shims; coroutine→Swift bridging | ✅ **Pick this** |
| B. Full native Swift rewrite | Pure Apple stack, no KMP | Re-implement **and re-validate** the financial math in Swift; two engines to keep in sync forever — unacceptable drift risk for a trading model | ❌ |
| C. Flutter / React Native | One cross-platform UI codebase | Throw away a polished native Android app; non-native feel; plugin friction for charts/biometric/secure storage/widgets | ❌ |

The deciding factor is **engine fidelity**. For a "numbers" product the model is the brand; sharing
it is non-negotiable, which is exactly what KMP buys us.

---

## 3. Target repository structure

Evolve the repo into a KMP-friendly layout. The Android app keeps working throughout (`:app`
depends on `:shared` instead of its in-tree `domain/`/`network/`).

```
gold/
├─ shared/                      # NEW — Kotlin Multiplatform module
│  ├─ src/commonMain/kotlin/    # engines, models, network, repo orchestration (moved from app/)
│  ├─ src/commonTest/kotlin/    # the 23 engine tests, now multiplatform
│  ├─ src/androidMain/kotlin/   # actual: Keystore storage, OkHttp/Ktor-Android, file cache
│  └─ src/iosMain/kotlin/       # actual: Keychain storage, Ktor-Darwin, file cache
├─ app/                         # existing Android app → consumes :shared, keeps SwiftUI's analog (Compose-free)
├─ ios/                         # NEW — Xcode project (SwiftUI app) — THIS folder
│  ├─ APPLE_RELEASE_PLAN.md     # this doc
│  ├─ APP_STORE_SUBMISSION_CHECKLIST.md
│  ├─ Aurum88.xcodeproj/        # (created on the Mac, Phase 2)
│  ├─ Aurum88/                  # SwiftUI sources
│  └─ Aurum88Widgets/ Aurum88Watch/   # (Phase 5, optional)
└─ data/cb_quarterly.json       # shared CB feed (unchanged)
```

> Migration is incremental: stand up `:shared` with the `domain/`+`model/` first (pure code, zero
> platform deps), point Android at it, ship an Android patch to prove parity, **then** add iOS.

---

## 4. Android → iOS platform mapping

Every platform touchpoint and its Apple counterpart:

| Concern | Android (today) | iOS (target) |
|---|---|---|
| UI framework | Views + XML + Material + ViewBinding | **SwiftUI** + SF Symbols + HIG |
| Tabs (4) | `ViewPager2` + `QuotePagerAdapter` | `TabView` |
| Lists (news) | `RecyclerView` | `List` / `LazyVStack` |
| Pull-to-refresh | `SwipeRefreshLayout` | `.refreshable {}` |
| Charts | custom `GoldIndexChartView` / `PriceChartView` (Canvas) | **Swift Charts** (iOS 16+) |
| Concurrency | `kotlinx-coroutines` | shared via KMP; Swift `async/await` bridge (**SKIE** or KMP-NativeCoroutines) |
| HTTP | OkHttp | **Ktor** (`Darwin` engine) in shared core |
| JSON | org.json / manual | **kotlinx.serialization** in shared core |
| Secure key storage | `Crypto`/`SecurePrefs` (Android Keystore) | **Keychain** (+ Secure Enclave) via `actual` |
| Biometric gate | `BiometricAuth` (BiometricPrompt) | **LocalAuthentication** (Face ID / Touch ID) |
| Google sign-in (Drive sync) | `play-services-auth` | **GoogleSignIn-iOS** SDK, same `drive.file` scope |
| Daily refresh | `WorkManager` (`DailyRefreshWorker`) | **BGTaskScheduler** (`BGAppRefreshTask`) — best-effort |
| Notifications | `POST_NOTIFICATIONS` + NotificationManager | **UNUserNotificationCenter** (request auth, local notif) |
| On-disk cache | `DataCache`/`GeminiCache`/`CentralBankCache` (filesDir) | shared via `expect/actual` → app container / `FileManager` |
| App id | `com.sun.aurum` | bundle id `com.bull88.aurum` (decide in Phase 0) |
| Min OS | minSdk 26 | **iOS 16** deployment target (unlocks Swift Charts + modern widgets) |

External REST services — **Yahoo Finance, FRED, Gemini, Google Sheets/Drive, the GitHub CB feed** —
are platform-agnostic and carry over unchanged behind the shared Ktor clients.

---

## 5. Shared-core migration — the concrete refactors

These are the only real code changes to the *core* (everything else is additive):

1. **Date handling → `kotlinx-datetime`.** `GoldIndexEngine` is the one domain file using
   `java.util.Calendar` / `java.text.SimpleDateFormat` (publication-lag math, freshness notes,
   month labels). `java.*` isn't in KMP `commonMain` — replace with `kotlinx-datetime`
   (`Instant`, `TimeZone.of("America/New_York")`, `LocalDate`). Lock behavior with the existing
   `cbEffectiveYear` / `cb_quarterly_ttm` tests, which already assert the lag logic.
2. **Caching/secure-storage/auth → `expect/actual`.** Define `interface KeyStore`, `interface
   FileCache`, `interface BiometricGate` in `commonMain`; implement Keystore/filesDir/BiometricPrompt
   in `androidMain` and Keychain/app-container/LocalAuthentication in `iosMain`. `DataRepository`
   keeps orchestrating against the interfaces.
3. **HTTP → Ktor.** Re-express the 5 clients on Ktor's `HttpClient` (engine: `OkHttp` on Android,
   `Darwin` on iOS). Keep the same retry / `query1→query2` failover logic that P1-2 added to Yahoo.
4. **JSON → kotlinx.serialization.** Replace `org.json` parsing with `@Serializable` models. The CB
   feed, FRED, Yahoo, and Gemini payloads all become typed.
5. **Coroutine bridging.** Add **SKIE** (Touchlab) so Swift sees `async` functions and `Flow` as
   `AsyncSequence` — the cleanest Swift ergonomics over KMP suspend/Flow.
6. **Tests stay.** Move `GoldIndexEngineTest` + `HmaiEngineTest` to `commonTest`; they then run on
   JVM **and** the iOS simulator target → automated cross-platform parity.

---

## 6. The iOS app (SwiftUI)

Mirror the four-tab model, native:

- **`RootView`** — `TabView` with Gold · Brief · News · Dollar (matches `QuotePagerAdapter`), gated by
  a **Face ID** `AuthGate` on launch (port of `BiometricAuth`).
- **Gold tab** — the 0–100 Gold Index gauge, five component rows with availability/key states, the
  forward signal, and the **Swift Charts** history view with regime bands (port of
  `GoldIndexChartView`).
- **Brief tab** — Gemini AI brief (yesterday recap / today outlook). Same `GeminiClient`, same
  8-hour cache, gold-only.
- **News tab** — `List` of `NewsItem`s with source/links.
- **Dollar tab** — the HMAI report for DX-Y.NYB (6 pillars + circuit breaker), reusing the shared
  `HmaiEngine`.
- **Settings** — API keys into **Keychain**, Google Drive connect/disconnect, the CSV history export
  (share sheet), Getting-Started copy.

State: a thin `@Observable AurumStore` (the `MainViewModel` analog) calling shared-core `async`
functions via SKIE.

---

## 7. Trader-grade iOS wins (do these — they're why iPhone users switch)

The pro-trader lens: the index is most valuable when it's **glanceable**. iOS gives surfaces Android
doesn't, and they're the differentiator:

- **WidgetKit — Home & Lock Screen widget.** The Gold Index number + arrow + regime color, refreshed
  on the timeline. A trader checks it without opening anything. *(highest ROI)*
- **Apple Watch complication.** Same glanceable index on the wrist.
- **Live Activity / Dynamic Island** — optional: live intraday gold move during the session.
- **Siri / App Shortcuts** — "Hey Siri, what's the Gold Index?" returns the latest read.
- **Handoff & iCloud** — continuity across iPhone/iPad; optionally keep API keys in the user's
  Keychain iCloud sync so a new device is set up instantly.

Widgets/Watch read the **same shared core** with a cached snapshot — no duplicate logic.

---

## 8. App Store Connect & submission

### Accounts / signing
- **Apple Developer Program** enrollment ($99/yr).
- Bundle id + App ID, certificates & provisioning (use Xcode **automatic signing**).
- App record in **App Store Connect**.

### Compliance items (don't get surprised in review)
- **Privacy Nutrition Labels (App Privacy).** It's bring-your-own-keys, on-device, **no backend** —
  most categories are *Data Not Collected*. Drive sync writes to the **user's own** Drive (not
  collected by us). Declare honestly; under-claiming is fine, over-collecting is the trap.
- **Privacy Manifest (`PrivacyInfo.xcprivacy`)** — required (2024+). Declare "required-reason" API
  usage (UserDefaults, file timestamps) and bundle the Google Sign-In SDK's manifest.
- **Sign in with Apple (Guideline 4.8).** Triggered only if a third-party login is used to create
  the app's account. Here Google is **optional** and only authorizes **Drive sync** — so frame it in
  UI/metadata strictly as *"Connect Google Drive"*, **not** *"Sign in with Google."* That keeps us
  out of 4.8. (If you ever make Google a login, you must add Sign in with Apple.)
- **Export compliance** — standard HTTPS only → set `ITSAppUsesNonExemptEncryption = false`.
- **Financial-app metadata.** Apple scrutinizes trading apps. Keep the **"not investment advice"**
  disclaimer prominent (already in the app), make store copy accurate, and — critically — **never put
  performance/"always wins" claims in the listing.** That's both an App Review rejection and a
  false-advertising problem. The "trader who always wins" is our internal bar for *quality*, not a
  marketing claim.
- **No ATT** needed (no tracking/ads).

### Assets
- App icon 1024², iPhone 6.7"/6.9" + (optional) iPad screenshots, description, keywords, support URL
  (reuse `sunny@bull88protocol.com` + the site), privacy policy URL (have `PRIVACY.md` hosted).

### Beta + release
- **TestFlight** for beta — the Apple analog of the Play testing track. Testers join via email
  invite or a public link (this is the iOS side of `TESTING.md`).
- Internal TestFlight is instant; external TestFlight needs a light Beta App Review.
- Submit for App Review → release (manual or phased).

---

## 9. Phased plan & effort (solo dev)

| Phase | Work | Est. |
|---|---|---|
| **0. Setup & decisions** | Mac + Xcode; Apple Developer Program; lock bundle id, min-iOS (16), monetization (free), KMP sign-off | 2–4 days |
| **1. Shared core (KMP)** | Stand up `:shared`; move `domain/`+`model/`; `kotlinx-datetime` refactor; Ktor + kotlinx.serialization clients; `expect/actual` storage; SKIE; **green parity tests on JVM + iOS sim**; ship an Android patch on `:shared` to prove no regression | 1.5–2 wks |
| **2. SwiftUI app** | Xcode project; consume `.xcframework`; the 4 tabs + Settings; `AurumStore`; wire shared async APIs | 2–2.5 wks |
| **3. Platform services** | Keychain keys; Face ID gate; GoogleSignIn-iOS + Drive `drive.file`; local notifications; `BGTaskScheduler` daily refresh | 1 wk |
| **4. Charts & polish** | Swift Charts (index history + regime bands, intraday); Dynamic Type, dark mode, accessibility, empty/error states | 1 wk |
| **5. iOS extras** *(optional, high-value)* | WidgetKit widget; Watch complication; Shortcuts/Siri | 0.5–1.5 wks |
| **6. Store prep** | Privacy labels + manifest; icon/screenshots/metadata; disclaimers; export compliance | 2–4 days |
| **7. TestFlight → review → release** | Internal → external TestFlight; fix review feedback; submit; release | 1–2 wks |

**Total:** ~6–10 weeks for a polished v1 (drop Phase 5 to trim ~1–1.5 wks). The shared-core phase is
the long pole; everything after it is additive and parallelizable.

---

## 10. Decisions to lock before Phase 1

1. **Mac/Xcode access** — own a Mac, or rent (Xcode Cloud / MacStadium / a cloud Mac)? *Hard gate.*
2. **Bundle id** — `com.bull88.aurum` vs `com.sun.aurum`? (affects App ID, App Store Connect.)
3. **Min iOS** — 16 (recommended, Swift Charts + widgets) vs 15 (custom chart, wider reach).
4. **Monetization** — free (matches Android) vs later IAP. Free now keeps review simplest.
5. **KMP vs native** — confirm the KMP recommendation (this plan assumes it).
6. **iOS extras scope** — widget/Watch in v1, or fast-follow v1.1?

---

## 11. Definition of done (v1)

- iOS app produces **bit-identical** Gold Index & HMAI numbers vs Android (parity tests green on the
  iOS target).
- All four tabs + Settings functional; Face ID gate; keys in Keychain; Google Drive sync verified
  under `drive.file`; daily local notification fires.
- Swift Charts history with regime bands renders correctly across device sizes and in dark mode.
- App Privacy labels + privacy manifest complete; "not investment advice" disclaimer present;
  metadata honest.
- Passes external TestFlight, then App Review; released (or staged) on the App Store.
- One shared core: a model fix lands once and both platforms inherit it.
