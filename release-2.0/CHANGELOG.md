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

## Milestone B — An index you can trust (in progress)

### P1-1 — Central Bank: live hosted feed, look-ahead-free, transparent ✅ COMPLETE
Probe finding: there is **no** free, on-device, official central-bank *net-purchase* JSON feed.
IMF's DataMapper API exposes only reserve *ratios* (no absolute gold series); the full IMF IFS
gold series is per-country (would need ~100-country aggregation on-device); WGC/Goldhub has no
open API. A true "live WGC feed" therefore runs into the app's no-backend promise — see the open
decision below.

Shipped this turn (all source-agnostic, so unwasted whatever live source we pick):
- **Fixed a look-ahead bug.** `cbTonnesEffective` made year Y's full-year figure effective in
  April **of year Y**, but WGC doesn't publish it until ~Q1 of **Y+1**, so the historical chart/CSV
  (and the reweight validation) were using up to ~12 months of future data. New `cbEffectiveYear`
  uses Y−1 from April, Y−2 in Jan–Mar — look-ahead-free, with a test asserting eff-year < as-of-year
  for every month 2012–2030. Live snapshot today is unchanged (2025 est. = 1000 t → score 84).
- **Freshness/estimate transparency.** The CB row now reads "… · as of 2024" (actual) or
  "… · as of 2025 est." so users always see how current the input is.
- **No-dominance guardrail (P1-1c).** When neither FRED nor DXY is available, CB's weight is
  halved (0.22 → 0.11) so a slow, near-static series can't dominate a no-key headline — exactly
  the degraded state seen live (67 → ~59, leaning more on the live Technical read).
- **Live-feed seam + tests.** `cbTonnesEffective` is the single override point for a future feed;
  internal helpers exposed for testing. Engine tests now **9/9 green**.

**Decision: hosted quarterly JSON (option 1).** Part 2 shipped this turn:
- `CentralBankClient` downloads a public quarterly file (`data/cb_quarterly.json`, hosted at
  `raw.githubusercontent.com/bull88protocol/aurum/master/data/cb_quarterly.json`); `CentralBankCache`
  caches it weekly with offline fallback to the last-good copy.
- Engine consumes it via `Inputs.cbQuarterly`: a **trailing-12-month** sum of the last 4
  **published** quarters (≥6-week publication lag → look-ahead-free), falling back to the bundled
  annual series whenever <4 quarters are available. Live snapshot + history chart + CSV all use it.
- The CB row now shows the live quarter ("… · as of 2025-Q1"). The dead `goldCentralBankScore`
  Gemini→engine flow was removed while wiring this.
- **Tooling for the owner:** `release-2.0/cb-data/cb_update.py` (one command to add a quarter and
  push) + `release-2.0/cb-data/README.md`. The seed is an even split of WGC annual totals (matches
  today's basis exactly; replace recent quarters with WGC actuals for real intra-year movement).
- Privacy unchanged — read-only download of a public file, no user data, no key; `PRIVACY.md` §3
  updated to list it.
- Engine tests now **12/12 green** (added TTM, publication-lag, and feed-integration cases).

To go live: publish `data/cb_quarterly.json` to `master` (happens on the v2.0 merge, or push sooner),
then replace the recent placeholder quarters with real WGC numbers via the script.

### P1-2 — Data-source resilience ✅ COMPLETE
A single flaky Yahoo request used to silently drop a core series — on-device we caught DXY blank
out, which dragged the 23%-weight USD driver to "Not configured" and leaned the index on the
near-static CB constant. Fixed:
- **Retry + mirror failover.** `YahooFinanceClient.get()` now tries the **query1** host then its
  **query2** mirror on each of up to 3 attempts, with linear backoff (300/600 ms). Every Yahoo
  call (quote, intraday, daily, max, VIX, DXY) gets this for free. (Stooq was scoped as a 2nd
  provider but is now behind a JS bot-wall — dropped; query1/query2 covers the transient case we
  actually saw. A keyed provider like Twelve Data remains a future opt-in for full-outage cover.)
- **Honest UI signal.** New `GoldComponentScore.keyRequired` separates "needs a FRED key"
  (Real Yield / Inflation) from "couldn't load" (a dropped DXY/Technical fetch). The Gold tab
  banner now reads "Add a FRED key for: …" vs "Couldn't load (pull to refresh): …" instead of
  lumping a transient network failure under "Not configured".
- Engine tests now **13/13 green** (added the key-required-vs-data-failure case).

### P1-4 — Honest quote source: Yahoo for display, Google for sync ✅ COMPLETE
Sign-in was sold as "real-time Google Finance," but `GOOGLEFINANCE` lags ~20 min and has no
extended hours — and it was *overriding* the better Yahoo quote, so signing in made the quote
worse and dropped the pre/after-hours badge. Per the decision (Yahoo for quotes, Google for sync):
- **Behavior:** `DataRepository.fetchAll` now always displays the **Yahoo** quote (near-real-time,
  keeps pre/after-hours). When signed in it still maintains the user's own Sheet, but no longer
  reads its delayed GOOGLEFINANCE values back; `usingGoogleData` is always false (badge = YF).
- **Relabel:** removed every "real-time Google Finance" claim and reframed sign-in as optional
  **Sheets sync** — Settings (section header, label, description, status line), the Getting Started
  dialog, README, and APP_GUIDE. (Store listing had no such claim.)
- No engine changes; 13/13 tests still green.

**Milestone B complete** except the optional **P1-3** (move price/technicals from the GLD ETF to
spot XAU for 24-h coverage) — deferred unless you want it.

## Milestone C — Production-grade plumbing (in progress)

### P2-2 — Runtime notification permission ✅ COMPLETE
`POST_NOTIFICATIONS` was declared in the manifest but never requested, so on Android 13+ (the
connected test device is Android 16 / API 36) the daily "market open" notification was silently
dropped.
- `MainActivity` requests it once via the Activity Result API, **after** the biometric gate (so it
  doesn't collide with the fingerprint prompt), guarded by an `asked_notif_perm` flag so a prior
  choice is respected. If denied, the daily refresh still runs — only the notification is skipped.
- assembleDebug + 13/13 tests green.

Remaining — see `NEXT_RELEASE_PLAN.md` §5: surface a 2nd instrument via HMAI (P2-5), more engine
tests (P2-1), Credential Manager + `drive.file` scope migration (P2-3), Jetpack-Security migration
(P2-4), and the small cleanups (P2-5).

---

## Tooling note
`release-2.0/cb-data/cb_update.py` now degrades gracefully: re-entering the same value prints
"Nothing to commit" instead of erroring, the commit is scoped to the data file only, and a push
with no upstream explains that the feed goes live when the branch reaches `master`.
