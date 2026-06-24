# Aurum88 Protocol — Next Major Release Plan (v2.0)

> Deep review of the shipped 1.3.0-beta codebase **and the live app on-device**
> (`adb 44251JEKB01464`, Gold tab read live: GLD **$387.12 / −0.38%**, Gold Index
> **67 / 100 NEUTRAL**), conducted from two seats: **Android lead** and **pro gold/macro
> trader**. This document is the plan for the next major release — what to fix, in what
> order, and why each item matters. File:line references point at the exact code.
>
> Produced 2026-06-18. Companion to `APP_GUIDE.md` (architecture) and `plan.md` (Play upload).

---

## 0. Verdict

The engineering core is genuinely good: clean MVVM, correct coroutine/threading discipline,
proper ViewBinding lifecycle, encrypted BYOK storage, biometric gate, and a **mathematically
sound** domain layer (Wilder's RSI smoothing, EMA/ATR/MACD, annualized HV, least-squares
slope) with real attention to **look-ahead bias** (point-in-time `headMap` windows, the
April-lagged central-bank series). The reweighting/forward-signal rationale is honest and
defensible. **Most of the code does not need changing — it needs the rough edges that betray
its "forked from a multi-stock app" origin removed, and two integrity gaps in the index closed.**

The headline risk is not a crash; it's **credibility**. Several user-visible surfaces still
speak the language of the old GOOG/SMH/NVDA stock app, and ~22% of the "live macro" index is
currently a frozen constant. For a product sold as *"the macro pulse of gold, read the way a
desk reads it,"* those are the things a pro user will notice first.

> **Update (2026-06-19):** most of this plan is now built — see the status table below. The two
> headline risks called out above (the stock-app leftovers and the frozen-constant CB) are both
> resolved.

---

## 0.5 Implementation status — branch `release-2.0` (updated 2026-06-24)

`master` stays frozen on the Play-submitted 1.3.0-beta; all work below is committed on
`release-2.0` and was verified on-device (debug build, in-place upgrade — the Dollar tab renders
its HMAI card, and the Gold tab shows the new CB freshness label). Per-item detail in
[CHANGELOG.md](CHANGELOG.md).

| Item | Status | Commit |
|---|---|---|
| **P0-1** purge multi-stock leftovers (notification, Sheet, Gemini prompt) | ✅ done | `57b6ecf` |
| **P0-2** fix "Getting Started" copy (3 of 5 components, CB is key-less) | ✅ done | `57b6ecf` |
| **P0-3** CSV export uses true-daily history (not monthly/quarterly) | ✅ done | `c7a4e92` |
| **P1-1** Central Bank: hosted live feed + look-ahead lag fix + freshness + no-dominance guardrail | ✅ done | `9f9bfec`, `dbbcc78` |
| **P1-2** data-source resilience: Yahoo retry + query1/query2 failover + honest UI | ✅ done | `12e4f94` |
| **P1-3** move price/technicals from the GLD ETF to spot XAU (24-h) | ⬜ deferred (optional) | — |
| **P1-4** honest quote source — Yahoo for display, Google for sync (relabel) | ✅ done | `5db1ef5` |
| **P1-5** trader micro-notes (RSI philosophy, RY/USD window asymmetry, DXY anchors) | ⬜ remaining | — |
| **P2-1** broaden engine tests | ◑ partial — **23** engine tests (Gold Index 13 + HMAI 10); HMAI engine now covered | (across the above) |
| **P2-2** request `POST_NOTIFICATIONS` at runtime (Android 13+) | ✅ done | `880c9bc` |
| **P2-3** least-privilege `drive.file` scope swap | ✅ done | `d46b371` |
| **P2-3+** Credential Manager migration + drop `GET_ACCOUNTS` | ⬜ deferred (needs on-device auth test) | — |
| **P2-4** migrate off alpha/deprecated `EncryptedSharedPreferences` → Android Keystore + migration | ✅ done + verified on phone | `dc8e540` |
| **P2-5** surface a 2nd instrument (DXY via HMAI) | ✅ done + verified on phone | `8c6da81` |
| **P2-5 cleanups** dedup GLD fetch block · branded notif icon · chart timezone | ✅ done + verified on phone | `c635f82` |
| **P2-5 follow-up** share DX-Y.NYB candles across a batch refresh (was fetched twice) | ✅ done | `2e9a76c` |
| **Fix** edge-to-edge insets (Android 15): toolbar + footer were under the system bars | ✅ done + verified on phone | `57b0fe2` |

**Milestones: A ✅ · B ✅** (P1-3 optional) **· C nearly done** — P2-2/P2-3/P2-4/P2-5 + the P2-5
cleanups and DXY-dedup follow-up done, P2-1 partial; only the optional Credential Manager follow-up
(P2-3+) remains.

**Open operational items (not code):** publish `data/cb_quarterly.json` to `master` (happens on
the v2.0 merge) and replace the placeholder quarters with real WGC numbers via
`cb-data/cb_update.py`; add a store screenshot of the Dollar tab; smoke-test Google sign-in once
under the new `drive.file` scope.

---

## 1. What is already strong — do not touch

- **Domain math** (`domain/hmai/TechnicalIndicators.kt`, `domain/gold/GoldIndexEngine.kt`):
  indicators are textbook-correct; percentiles clamped to [0.05, 0.95]; composite
  re-normalizes over *available* weights.
- **Look-ahead discipline**: `computeHistorical`/`computeHistoricalFull` rebuild each day
  from `TreeMap.headMap(date)` windows; CB tonnage is made effective in April (≈3-mo lag).
  This is the kind of thing most retail apps get wrong, and it's right here.
- **Forward signal** deliberately *excludes* central-bank level with a written IC rationale
  (`GoldIndexEngine.kt:245-250`) — correct call.
- **Architecture**: `MainViewModel` (StateFlow) → `DataRepository` (Dispatchers.IO) →
  pure-function engines; fragments null their bindings in `onDestroyView`; key-aware empty
  states; pull-to-refresh wired to `isRefreshing`.
- **Security/privacy posture**: `EncryptedSharedPreferences`, HTTPS-only
  (`usesCleartextTraffic=false`), `allowBackup=false`, no backend, no analytics/ads, BYOK.

Keep all of the above as-is.

---

## 2. P0 — Credibility bugs (fix before the next public build)

These are small code changes with outsized impact on how "finished" the app feels.

### P0-1 — Purge the multi-stock leftovers (the "old app" still shows through)
The app was clearly forked from a GOOG/SMH/NVDA stock tracker, and that identity leaks into
three user-facing places:

| Where | Evidence | Problem |
|---|---|---|
| Daily notification | `worker/DailyRefreshWorker.kt:61` — `setContentText("Data ready: GOOG · SMH · NVDA · GLD — tap to view")` | Every morning a **gold** app notifies about Google, semis, and Nvidia. |
| User's Google Sheet | `network/GoogleSheetsClient.kt:41-47,152-165` — writes `GOOGLEFINANCE` rows for GOOG/SMH/NVDA/GLD into a sheet in the user's Drive | A "gold" app silently creates an **Nvidia/Google** spreadsheet in the user's own Drive. Only GLD + VIX are ever read back. |
| AI Brief / News prompt | `network/GeminiClient.kt:100-122` — *"Search for real-time information about $symbol **stock**… any **company-specific news**… what the broader market (S&P 500, Nasdaq) did"* | Gold is not a stock and has no "company news." This pulls equities into a gold feed — **confirmed live**: the News tab currently shows *"US Stocks Close: Nasdaq Rises 1.91%, Philadelphia Semiconductor Index…"* |

**Fix:** notification → gold-specific copy ("Gold Index updated — tap to view today's read").
Sheet → write only the GLD (and VIX) rows. Prompt → rewrite for gold-as-macro-asset (spot
gold / XAU, COMEX, real yields, DXY, central banks, ETF flows; drop "stock"/"company news";
keep the one-line equities-context sentence at most). Also fix the stale "Search tab" comment
in `MainViewModel.kt:27-28`.

### P0-2 — "Getting Started" misstates how the index works without keys
`MainActivity.kt:152-170` tells users: *"the Gold Index uses only 2 of its 5 pillars (USD +
Technicals)"* without keys, and that the **Gemini** key adds *"the Central Bank Demand
pillar."* Both are now false: Central Bank ships **built-in** from the WGC series (no key),
so **3** components work key-less, and Gemini no longer feeds CB (`GoldIndexEngine.kt:235-243`,
confirmed in `APP_GUIDE.md §5`). **Fix:** update the dialog to "3 of 5 (USD + Central Bank +
Technicals)" and move CB out of the Gemini bullet.

### P0-3 — CSV "full history" export is computed on the wrong-resolution data
`MainViewModel.generateGoldIndexHistoryCsv()` → `YahooFinanceClient.fetchMaxDailyCandles()`
requests `interval=1d&range=max`, but **Yahoo silently downsamples `range=max`** (verified
live today):

- `GLD` max → **260 bars, `dataGranularity=1mo`** (monthly)
- `DX-Y.NYB` max → **167 bars, `dataGranularity=3mo`** (quarterly)

So `computeHistoricalFull` (`GoldIndexEngine.kt:393-436`) runs `scoreTechnical` over a window
of *months* (a 200-bar SMA = 200 **months**), and the USD percentile over ~40 years of
**quarterly** points — then labels the columns as the same daily basis as the live index. The
CSV header even claims "DFII10/T10YIE are daily." The exported "full index history" is
therefore not comparable to the on-screen index, despite the code comment asserting it is.
**Fix:** page true daily history via `period1`/`period2` chunks with `interval=1d` (Yahoo caps
per request, so loop), or relabel/scope the export to the resolution actually returned and
guard `scoreTechnical` against sub-daily bar spacing. (The on-screen 1-yr chart is unaffected —
it uses the 2-yr daily fetch.)

---

## 3. P1 — Index integrity & trader logic (the heart of the product)

### P1-1 — ~22% of the "live" index is a frozen constant
Central-bank tonnage is hardcoded with **2025 and 2026 both pinned to 1000 t/yr**
(`GoldIndexEngine.kt:196-203`). Through the anchor map (`cbScoreFromTonnes`, 800→72, 1100→90)
that fixes the CB **score at ≈84 (bullish) every single day of this year**, look-ahead-free but
also information-free for the current regime. Consequences:

- With a FRED key, CB is a fixed 22% bullish weight.
- **Without** a FRED key (the default first-run state), RY (30%) + Inflation (13%) drop and CB
  becomes ~**39%** of a re-normalized composite — and if the DXY fetch also misses (see P1-2),
  CB + a sliver of Technical is essentially the whole index. **This is exactly what the live
  device shows right now:** 67/100 with *"Not configured: Real Yield · USD · Inflation,"* i.e.
  CB(84) + Technical(~36) ≈ 67. A "macro pulse" that is mostly a hand-entered constant.

**Decision (v2.0): pursue (a) the live WGC feed as the primary fix**, with (b) and (c) shipped
alongside as the freshness guarantee and the no-key guardrail.
(a) pull the **live WGC quarterly net-purchase feed** the code TODO already calls for, so CB
actually moves; (b) auto-roll the annual estimate and surface a **"CB data as of <date>"**
freshness chip so it's never silently stale; (c) cap CB's effective share when it is the *only*
non-technical component available, so a no-key user isn't handed a near-constant score dressed
as macro. Pin the feed parse + April-lag logic with a unit test (P2-1) so it can't regress.

### P1-2 — Single-fetch fragility on the one always-on data source
Everything rides on Yahoo's **unofficial** endpoint (`YahooFinanceClient`) with **no retry, no
backoff, no fallback provider**, and every failure path returns `emptyList()`/`null` silently
(`get()` at `:64-73`). A single transient miss on `DX-Y.NYB` drops the **23%-weight USD driver**
to "Not configured," and the stale gap then persists for up to the 8-hour cache TTL with no
"retrying" signal. (Today `DX-Y.NYB?range=2y` also returns 610 timestamps but only **504
non-null closes** — ~17% nulls — so the feed is genuinely lumpy.) A "pro reference" can't have
a core driver blink out on one flaky request.

**Fix:** add bounded retry + backoff; add a fallback source (Stooq `l1`/CSV, Twelve Data, or
Alpha Vantage) behind the same `Candle` interface; and distinguish **"couldn't refresh
(network)"** from **"key required"** in the UI so the "Not configured" banner stops absorbing
transient failures.

### P1-3 — GLD is a US-hours proxy for a 24-hour asset
Price, intraday chart, and the Technical component all use **GLD** — a US-session ETF with
~0.40%/yr expense drag that doesn't trade the London/Asian sessions where gold often makes its
biggest moves. The macro components (DXY, FRED, WGC) are about *gold*, but the timing layer is
about *an ETF wrapper*. **Fix/decide:** use spot **XAU/USD** (or `GC=F` continuous) for
price/technicals and keep GLD only if the ETF audience is intentional; at minimum disclose the
proxy in the methodology/Getting Started.

### P1-4 — "Real-time Google Finance" is delayed and *loses* extended hours
Sign-in is sold as *"real-time Google Finance quotes"* (`SettingsActivity.kt:165`,
Getting Started, README). But `GOOGLEFINANCE()` is **~20-minute delayed** for most exchanges,
and the Sheets path (`GoogleSheetsClient.parseRows`) populates **no `marketState`**, so a
signed-in user *loses* the PRE/AFTER-HOURS badge the Yahoo path provides
(`YahooFinanceClient.parseQuote:116-145`). For a trader, signing in can make quotes **worse**.
**Fix:** relabel as "Google Finance (may be delayed)", or drop the feature, or only prefer it
when it's actually fresher; carry extended-hours through if kept.

### P1-5 — Smaller trader notes (low effort, worth a pass)
- **RSI is scored mean-reversion** inside a trend product: RSI>80 → 10, RSI<30 → 28
  (`GoldIndexEngine.scoreTechnical:171-175`). In a strong gold uptrend, high RSI is often
  *continuation*, not a fade. Technical is only 12%, but reconcile the philosophy.
- **Window-length asymmetry**: real-yield percentile uses 252d, USD uses 504d
  (`:92` vs `:119`). Defensible (USD moves slower) but undocumented — state it.
- **DXY level anchors** clamp outside ~84–120 (`dxyLevelScore:475-488`); fine today (DXY ≈100.8)
  but revisit if the dollar breaks range.

---

## 4. P2 — Android platform & engineering health

### P2-1 — Zero tests on a numeric financial engine
There are **no unit or instrumented tests anywhere** (`app/src` has no test source set). The
domain layer is pure Kotlin with no Android deps — it is trivially JVM-testable. For a product
whose entire value is a number, the absence of tests pinning the scoring math, the
re-normalization, the percentile/window edges, the look-ahead guarantees, and CSV correctness
is the biggest *engineering* risk. **Fix:** add a `test/` source set; golden-master the
engine on a fixed candle/FRED fixture; assert `range=max` resolution assumptions so P0-3 can't
silently regress.

### P2-2 — Notifications are silently dropped on Android 13+
`POST_NOTIFICATIONS` is declared in the manifest but **never requested at runtime** (no
`requestPermissions`/Credential flow anywhere in `app/src/main/java`). On API 33+ (most of the
target base, since `targetSdk=35`) the daily "Market Open" notification the worker builds is
suppressed until the user enables it in system settings. **Fix:** request the runtime
permission (e.g., on first run or first worker schedule), and gate the notification on grant.

### P2-3 — Deprecated auth + a *restricted* OAuth scope (Play/verification risk)
- `GoogleAuthManager` uses the **deprecated** `GoogleSignIn`/`GoogleSignInOptions`/
  `GoogleAuthUtil` stack and `SettingsActivity` uses the deprecated `startActivityForResult`.
  Migrate to **Credential Manager / Google Identity Services**.
- It requests the **`auth/spreadsheets`** scope (`GoogleAuthManager.kt:63`) — full read/write to
  *all* of the user's spreadsheets, which is a Google **restricted scope** requiring a **CASA
  security assessment** before Google Sign-In can ship to all production users. Since the app
  only touches a sheet **it created**, switch to the non-sensitive **`drive.file`** scope.
- `GET_ACCOUNTS` (manifest) appears unused by the modern sign-in path and is a sensitive
  permission worth removing (and it simplifies the Data Safety form). `USE_FINGERPRINT` is
  legacy (covered by `USE_BIOMETRIC`) — keep only if you still target the 26–27 window.

### P2-4 — Jetpack Security (EncryptedSharedPreferences) is alpha **and** deprecated
`androidx.security:security-crypto:1.1.0-alpha06` backs the core key store
(`SecurePrefs.kt`). The library is an alpha that Google has since **deprecated**. It works
today, but plan a migration (Tink directly, or the current recommended approach) so the app's
one security-critical dependency isn't an abandoned alpha.

### P2-5 — Cleanups (low priority, reduce maintenance surface)
- **GLD fetch block is duplicated** verbatim between `DataRepository.fetchAll` (`:68-85`) and
  `fetchSymbol` (`:129-146`) — extract one private builder.
- **VIX is fetched but unused** in the shipped GLD-only build (only HMAI consumes it, and HMAI
  isn't surfaced). `centralBankScore` is plumbed end-to-end (`DataRepository` → `Inputs`) and
  then **ignored** by the engine. Remove the dead flow.
- **The entire HMAI engine + 6 pillars + CircuitBreaker is built and unreachable**
  (`MainViewModel.SYMBOLS = ["GLD"]`) — ~600 lines of well-written dead code.
  **Decision (v2.0): keep it and surface a second instrument** (e.g. DXY / GDX / silver) so HMAI
  pays rent. This promotes it from a cleanup to a **Milestone B/C product feature**: add the
  symbol(s) to `SYMBOLS`, give the second tab a quote + HMAI card (the `renderHmai` path already
  exists in `QuoteFragment`), confirm the per-symbol Gemini cache keys don't collide, and add a
  test fixture for the HMAI composite + circuit breaker before exposing it.
- Notification uses the generic `android.R.drawable.ic_dialog_info` — ship a branded mono icon.
- `GoldIndexChartView` x-axis labels use device-local `Calendar` while the engine dates in ET —
  cosmetic month-boundary drift.

---

## 5. Proposed v2.0 scope & sequencing

**Milestone A — "De-stock & de-stale" (P0, ~2–3 days).** P0-1, P0-2, P0-3. Pure win: the app
stops sounding like a stock tracker and the CSV stops lying about its basis. Low risk, high
polish. Ship as 1.3.1.

**Milestone B — "Index you can trust" (P1, ~1–2 weeks).** P1-1 (live/rolling WGC + freshness
chip), P1-2 (retry + fallback source + honest error states), P1-4 (relabel/repair Google
quotes). P1-3 (spot-gold basis) if appetite. This is the real v2.0 headline: *the macro pulse
actually pulses, and never silently flatlines.*

**Milestone C — "Production-grade plumbing" (P2, in parallel).** P2-1 tests **first** (they
de-risk A and B), then P2-2 notifications, P2-3 auth/scope migration (needed before "available
to everyone" production anyway), P2-4 crypto migration, P2-5 cleanups.

**Acceptance criteria for v2.0**
1. No GOOG/SMH/NVDA/"stock"/"company news" string in any user-facing surface or the user's
   Drive sheet (grep-clean + manual News-tab check).
2. CSV export rows are true daily, or the export is explicitly scoped to its real resolution;
   a unit test pins the assumption.
3. Central-Bank component either moves with live data or shows a dated freshness indicator; a
   no-key user is never shown a near-constant score presented as live macro.
4. A simulated DXY/Yahoo failure shows a "couldn't refresh" state and retries — it does **not**
   silently read as "Not configured," and does not let one missing series dominate the score.
5. Domain engine covered by JVM tests (golden master + edge cases); CI runs them.
6. Daily notification is delivered on a fresh Android 14/15 install (runtime permission
   requested) and its text is gold-specific.

---

## 6. Out of scope / future
- Price alerts / index-threshold notifications (the notification channel already exists).
- Widget for the Gold Index score.
- A published, versioned methodology page (turns "proprietary" into "credible").
- (Multi-instrument mode is now **in scope** — see P2-5 / Milestone B.)

---

## Appendix A — Live readings captured during this review (2026-06-18)
- Gold (GLD): **$387.12**, −1.48 (−0.38%), H $392.48 / L $385.60, Vol 7,370,647, source **YF**.
- Gold Index: **67 / 100 NEUTRAL**; banner *"Not configured: Real Yield · USD · Inflation"*
  (no FRED key on the device + a DXY miss) → composite effectively CB(≈84) + Technical(≈36).
- News tab: live, fresh (2026-06-16…18), Gemini-grounded — but includes an off-topic equities
  recap headline (the P0-1 prompt issue, observed in the wild).
- Note for future live audits: the app's biometric overlay blacks out `screencap`, but
  `uiautomator dump` reads the underlying view tree fine — that's how these values were pulled.

## Appendix B — Data-source health (tested live from the dev host, 2026-06-18)
| Call | Result | Note |
|---|---|---|
| `GLD ?interval=1d&range=5d` | 200, price 387.12 | matches app exactly |
| `DX-Y.NYB ?interval=1d&range=2y` | 200, 610 ts / **504 non-null** | ~17% null closes |
| `GLD ?interval=1d&range=max` | 200, **260 bars, `1mo`** | not daily → P0-3 |
| `DX-Y.NYB ?interval=1d&range=max` | 200, **167 bars, `3mo`** | quarterly → P0-3 |
| `^VIX ?range=5d` | 200, 16.4 | fetched but unused in GLD-only build |
