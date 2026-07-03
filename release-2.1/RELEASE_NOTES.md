# Aurum88 Protocol v2.1 — Release Notes

## v2.1.1 (versionCode 8) — patch

One fix on top of 2.1.0: the **Clear Cache** action (⋮ menu) now also drops the weekly
central-bank feed cache, so it genuinely "fetches everything fresh" as its dialog promises.
Before this, `clearCache()` wiped only the data + Gemini caches, leaving the CB feed on its 7-day
TTL — so a corrected WGC feed (or any feed change) couldn't be pulled on demand and users waited up
to a week. `CentralBankCache.invalidate()` marks the file stale (keeps the last-good copy for
offline) rather than deleting it. AAB: `app/build/outputs/bundle/release/app-release.aab`.

> Note: the CB *data* correction reaches existing 2.1.0 installs automatically within 7 days via the
> normal weekly refresh — no app update needed. 2.1.1 only adds the *on-demand* refresh capability.

Play "What's new" (2.1.1): *"Clear Cache now also refreshes the central-bank data feed on demand."*

---

## v2.1.0 (versionCode 7)

**Status:** on Play **Internal testing** (the test device runs a Play-App-Signing-signed build).
Merged to `master` 2026-07-03 (`b03afe6`).

## What's new (Play "What's new" copy)

> Smarter 3–6 month Forward Signal — rebuilt and backtested against 21 years of real market
> history. It now reads the real-rate regime (10-year real yields vs their 5-year range), the
> 12-month price trend, and the Fed cycle (3-month change in 2-year yields).
>
> The Gold Index headline now speaks plainly about current conditions — HOT, MIXED or WEAK —
> while direction lives in the Forward Signal.
>
> No new keys needed — your existing FRED key powers the new drivers.

## Technical summary

### Forward Signal v2 (the headline change)
The 3–6M outlook was rebuilt after a full backtest against real 2005–2026 history (Yahoo GLD/DXY,
FRED, WGC CB feed). Methodology, data and every number: **`research/README.md`**.

- **Old (v1)** delta-based signal (RealYield Δ 0.40 / USD Δ 0.30 / Inflation Δ 0.20 / ROC60 0.10)
  measured Spearman IC ≈ **−0.05** vs forward 63-day gold returns — its BEARISH months actually
  out-returned its BULLISH months. No predictive value.
- **New (v2):** `0.55 Real-Rate Regime + 0.25 12M Trend + 0.20 Fed Cycle`, availability-renormalized.
  - Real-Rate Regime = DFII10 level, **high = bullish** (0.5 fixed anchors + 0.5 rolling 5y percentile).
  - 12M Trend = ROC252 through fixed anchors.
  - Fed Cycle = DGS2 3-month change, **falling = easing = bullish**.
  - Engine-dumped IC: **+0.30 train / +0.38 test** (CI +0.20..+0.57), positive in all four eras;
    labels calibrated (BULLISH → 82% up, +8.1% mean next 3M vs a 61.6% base rate).

### Spot index — validated as a nowcast, math unchanged
Weights stay 30/23/22/13/12. It's a strong *coincident* read (+0.40/+0.55) but a poor 3M
*forecast* (≥70 historically preceded −0.6% mean), so its headline now uses conditions vocabulary
**HOT / MIXED / WEAK** (≥70 / 45–69 / <45). Per-component and Forward labels stay directional.

### Data & build
- New input: FRED **DGS2** (2-year Treasury, 3y) via the *same* FRED key. DFII10 now fetched 6y at
  `limit=2000` so the 5y rolling-percentile window is fully fed. No-key users degrade gracefully
  (Forward Signal falls back to 12M-Trend-only).
- **KMP `:shared` core** from iOS Phase 1 rode this merge onto `master` (engines + model in
  `commonMain`). Android behavior is identical apart from the changes above.
- R8 fix: `-dontwarn kotlinx.serialization.**` (kotlinx-datetime carries optional @Serializable
  refs the app doesn't ship; only surfaced on `bundleRelease`, which minifies).
- Tests **23 → 29**, all green.

## Play upload checklist

1. Play Console → Aurum88 Protocol → Test and release → **Internal testing** → Create new release.
2. Upload `app-release.aab` (release name auto-fills "7 (2.1.0)"). Accept Play App Signing.
3. Paste the "What's new" copy above → Save → Review → Roll out to internal testing.
4. Install from the internal opt-in link; confirm the Gold tab shows **HOT/MIXED/WEAK** and the
   Forward Signal's three new rows (Real-Rate Regime · 12M Trend · Fed Cycle).
5. Promote the release to **Production** when satisfied.

## Not blocking
- Store screenshot `store/screenshots/02_*.png` still shows the v1 four-row forward card and the
  old BULLISH/BEARISH spot label. Play doesn't require refreshed screenshots for an update —
  retake whenever convenient.
