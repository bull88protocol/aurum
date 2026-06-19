# Aurum88 Protocol v2.0 — Changelog (work in progress)

Branch: `release-2.0` (cut from `master` @ 1.3.0-beta / versionCode 5).
`master` stays frozen on the Play-submitted build until v1.3 is approved; then
`release-2.0` merges back and the version bumps to 2.0.0.

See `NEXT_RELEASE_PLAN.md` (this folder) for the full plan and rationale.

---

## Milestone A — De-stock & de-stale ✅ COMPLETE

### P0-1 — Purge multi-stock leftovers ✅ (code complete, pending on-device check)
The app was forked from a GOOG/SMH/NVDA stock tracker; that identity leaked into
user-facing surfaces. Removed:

- **Daily notification** (`DailyRefreshWorker.kt`): was *"Data ready: GOOG · SMH · NVDA ·
  GLD — tap to view"* → now *"Today's Gold Index and market brief are ready — tap to view"*
  (title simplified to "Aurum88 Protocol").
- **User's Google Sheet** (`GoogleSheetsClient.kt`): no longer writes GOOG/SMH/NVDA
  `GOOGLEFINANCE` rows into the user's Drive. Sheet is now GLD + VIX only (rows 1–3); read
  range `A2:I3`, write range `A1:I3`, `GF_TICKER`/`SYMBOLS` trimmed to GLD. (VIX kept for the
  v2.0 HMAI second instrument.)
- **AI Brief / News prompt** (`GeminiClient.kt`): split into `goldPrompt` (GLD) and
  `genericPrompt` (future instruments). Gold prompt now frames gold as a macro asset
  (spot/XAU, real yields, the dollar, Fed, inflation, central-bank/ETF flows), drops
  "stock"/"company-specific news"/"S&P-Nasdaq recap", and the dead `central_bank_score` ask.
  This was producing off-topic equity-index headlines in the live News tab.
- **Stale comment** (`MainViewModel.kt`): removed the non-existent "Search tab" reference.

### P0-2 — Fix "Getting Started" copy ✅ (code complete)
`MainActivity.kt` told users the index uses "only 2 of its 5 pillars (USD + Technicals)"
without keys and that the Gemini key adds "the Central Bank Demand pillar." Both were false
since CB moved to a built-in WGC series. Now: Gemini bullet = "AI market analysis and daily
news"; no-key state = "3 of its 5 components — USD, Central Bank Demand (built in), and
Technicals."

**Verification so far:** `./gradlew :app:compileDebugKotlin` → success. Still to do: install
the debug build and confirm the notification text, the Getting Started dialog, and (if signed
in) the Google Sheet contents.

### P0-3 — CSV "full history" resolution ✅ (code complete + tested)
`range=max` silently downsampled to **monthly GLD / quarterly DXY**, so the exported "full
index history" ran technical indicators over month/year windows and DXY over a 40-yr quarterly
percentile — not the daily basis the code claimed. Fixed:

- `YahooFinanceClient.fetchMaxDailyCandles` now requests an explicit `period1..period2` window
  with `interval=1d`, which returns TRUE daily bars for the whole history in one response
  (verified live: GLD 5,429 bars back to 2004, DX-Y.NYB 7,172 back to 2003). No chunking needed.
- The regression guard lives in tests, not a fragile runtime resolution check: the new
  `computeHistoricalFull_is_daily_and_well_formed` asserts one row per day after the 50-bar
  warmup (monthly data would collapse the row count), all five component columns present,
  composites in range, and CSV shape.
- Bootstrapped the JVM test source set (`testImplementation junit:junit:4.13.2`) — also the
  P2-1 seed. 5 tests, all green. Perf canary: `computeHistoricalFull(5500)` → 5,450 rows in
  ~0.5s on JVM (a few seconds on-device, off the main thread → fine; no optimization needed).

**Milestone A complete.** `compileDebugKotlin` and `testDebugUnitTest` both green.

---

## Milestones B & C
Not started — see `NEXT_RELEASE_PLAN.md` §5. Headlines: live WGC central-bank feed (P1-1),
retry + fallback data source (P1-2), surface a 2nd instrument via HMAI (P2-5), JVM tests for
the engine (P2-1), runtime notification permission (P2-2), Credential Manager + `drive.file`
scope migration (P2-3).
