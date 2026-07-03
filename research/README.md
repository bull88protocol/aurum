# Gold Index validation & Forward Signal v2 — research log (2026-07-03)

Question asked: *do the Gold Index component weights/components need adjusting, and can the
3-month outlook actually be predicted?* Everything below is measured on real downloaded
history — no synthetic data, no full-sample fitting, train/test discipline throughout.

## Data (all real, fetched 2026-07-03, no API keys)

| series | source | span | notes |
|---|---|---|---|
| GLD daily OHLCV | Yahoo chart API (the app's own source) | 2004-11-18 → 2026-07-02 (5,438 bars) | cross-checked vs GC=F |
| DX-Y.NYB daily | Yahoo chart API | 2000-01-03 → 2026-07-03 | 2008 low 71.33 / 2001 high 120.90 verified |
| GC=F daily | Yahoo | 2000-08 → 2026-07 | GLD×10/GC ratio drifts −0.40%/yr = exactly GLD's expense ratio → both series genuine |
| DFII10, T10YIE | FRED fredgraph.csv | 2003-01 → 2026-07 | the app's two FRED series |
| DGS2, VIXCLS | FRED fredgraph.csv | 1976 / 1990 → 2026-07 | candidate factors |
| CB net purchases | repo `data/cb_quarterly.json` + engine bundled annual | 2009 → 2026 | publication-lagged in engine |

`scripts/parse_inputs.py` re-downloads nothing; it parses `cache/raw/` and enforces the
data-quality gates (gaps, cross-source ratio bounds, landmark extremes).

## Method

1. **Engine-exact ground truth.** `EngineHistoryDumpTest` (app unit test) replays the full real
   history through `GoldIndexEngine` itself — `computeHistoricalFull` for the daily spot index,
   truncated `compute()` calls at each month-end for the forward signal — into `cache/engine/`.
2. **Python replica** (`scripts/engine_replica.py`) reproduces both to max|diff| ≤ 1e-5 against
   the dump (float32 noise), so variant iteration is drift-free.
3. **Evaluation**: monthly (month-end) observations, Spearman IC vs forward 63-trading-day and
   126-day GLD returns; moving-block bootstrap (6-month blocks) for CIs; label-bucket outcomes;
   sub-era tables. ~258 monthly rows ≈ only ~85 independent 3M windows — treat all CIs seriously.
4. **Discipline**: variants pre-registered before results (`variants.py`); the one post-hoc
   discovery (real-yield *level*) is flagged as such and held to a stricter bar: sign-consistency
   across four eras (2005-12 / 13-18 / 19-21 / 22-26), both horizons, and OOS CI excluding zero.
   Train 2005-2018, test 2019-2026.

Base rate first: **P(gold up over 3M) = 61.6%**, mean +3.03% (2005-2026 was secularly bullish).
Any signal must beat that, not a coin flip.

## Findings — the index as shipped (pre-2026-07)

**Spot composite (30/23/22/13/12)** — it is a decent *nowcast* and a poor *forecast*:
- Coincident: Spearman(level, trailing 3M return) **+0.40**; Pearson(Δindex, monthly gold) **+0.55**. ✅ does its job as "current conditions".
- Predictive: IC **−0.15** [−0.32, +0.03]. Label outcomes *inverted*: after BULLISH (≥70)
  readings gold averaged **−0.6%** (42% up); after BEARISH, **+4.5%** (72% up). The percentile
  components peak after the move has happened — a classic stretched-conditions artifact.
- The RY/INF percentile components sit pinned at their 5/95 clamps **28% / 21%** of all months.
  Widening windows to 504d does NOT fix pinning (28%→29%) and worsens level-coincidence
  (0.40→0.33) → **rejected; spot index left unchanged.**

**Forward signal v1 (RYΔ 0.40 / USDΔ 0.30 / INFΔ 0.20 / ROC60 0.10)** — no predictive value:
- IC **−0.05** [−0.20, +0.08] at 63d; −0.03 at 126d. Robustness on GC=F: −0.04.
- By era: −0.18 / −0.18 / −0.31 / **+0.17** — negative for 17 years, mildly positive only 2022+.
- Its BEARISH months were followed by *better* returns (+3.2%, 68% up) than its BULLISH months
  (+2.7%, 61% up). Ingredient ICs (train/test): ryΔ +0.07/−0.08, usdΔ +0.02/+0.11 (wrong sign
  vs orientation), infΔ **−0.14/−0.13** (stable wrong sign — rising breakevens preceded weaker
  gold), roc60 −0.01/−0.01.

## Findings — what actually predicts 3M gold (single factors, train | test IC)

| factor | train 63d | test 63d | era pattern | verdict |
|---|---|---|---|---|
| **DFII10 level (high = bullish)** | **+0.42** | **+0.42** | +0.27/+0.40/+0.47/+0.46, 126d test +0.70 | the one robust driver |
| 12M momentum (12-1) | +0.08 | +0.16 | −0.20/−0.13/−0.12/**+0.24** | weak; positive only 2022+; small sleeve |
| DGS2 63d Δ (falling = bullish) | −0.02 | −0.24 → right sign | flat pre-2019 | small sleeve |
| CB net purchases | −0.31 | +0.33 | sign flips | structural level, NOT timing — stays in spot only |
| VIX level | +0.06 | −0.25 | sign flips | rejected |
| T10YIE Δ | −0.14 | −0.13 | stable wrong sign vs v1's orientation | dropped |
| inverted composite (contrarian) | +0.13 | −0.04 | died post-2019 | rejected — the mean-reversion trade broke with the regime |

High real yields → strong forward gold is the standout: restrictive-peak pricing (cut runway
ahead) + washed-out positioning. It is the **opposite orientation** of the spot RY component —
correct for *conditions*, backwards for *outlook*.

## Forward Signal v2 (implemented in `GoldIndexEngine` 2026-07)

`0.55 × Real-Rate Regime + 0.25 × 12M Trend + 0.20 × Fed Cycle`, availability-renormalized.

- **Real-Rate Regime** = 0.5 × fixed DFII10 anchors (−1%→12 … 3%→92) + 0.5 × rolling 5y
  percentile (≥504 obs, else fixed-only). The blend exists because pure fixed anchors mislabeled
  the QE era (2019-21) BEARISH for three years; the percentile half keeps ranks regime-adaptive.
- **12M Trend** = ROC252 through fixed anchors (−25%→15 … +50%→90).
- **Fed Cycle** = DGS2 63-obs change through anchors (−2.0→90 … +2.0→15; flat ≈ 55).
- Degradation: no FRED key → trend-only (IC +0.09/+0.18, flagged `keyRequired`); no DGS2 → RY+trend
  renormalized (test IC +0.35).

Validated numbers, computed from the **engine's own dumped output** (not the replica):

| period | IC 63d | IC 126d |
|---|---|---|
| full 2005-2026 (n=258) | **+0.330** | +0.496 |
| train 2005-2018 | +0.295 | +0.417 |
| test 2019-2026 | **+0.382** [CI +0.20, +0.57] | +0.663 |
| 2022-2026 | +0.375 | +0.649 |

Era ICs (63d): +0.20 / +0.29 / +0.37 / +0.38 — positive in all four. Label outcomes (full sample,
vs 61.6% base): **BULLISH n=49: 82% up, +8.1% mean · NEUTRAL n=113: 64%, +3.1% · BEARISH n=92:
51%, +0.2%** — monotone and honestly calibrated (BEARISH means "no edge", not "crash").

Turning-point record (v2 | v1 → next 63d): Sep-2012 **31**|80 → −5.1% and 2013 crash followed;
Jan-2013 **29**|49 → −12.0%; Nov-2015 bottom **52**|35 → +16.4%; Jul-2020 top **37**|89 → −5.4%;
Oct-2022 bull launch **57**|19 → +19.6%; Feb-2024 **71**|53 → +14.4%.

Current (2026-07-02): spot composite 33 (conditions weak after the pullback), v1 forward would
have said 29/BEARISH, **v2 says 76/BULLISH** (real rates 2.25% = 95th 5y pctile → 87.5; 12M trend
+23% → 72.4; Fed 2Y +0.38 → 47.4).

## Honest limitations

- ~85 independent 3M samples in 21 years. IC ≈ +0.33 explains ~10% of rank variance — a
  probability *tilt*, not an oracle. The right consumer framing is odds vs the base rate.
- Known miss: 2019-2021 ran mostly BEARISH-labeled while gold rose (QE + COVID: low real yields
  but gold bid). Its BEARISH bucket there still matched era base (+3.0% vs +3.2%) — no harm, no edge.
- The anchor maps are monotone calibrations informed by the full sample; the *sign and
  monotonicity* are what the era-by-era validation supports. Spearman ICs are map-invariant.
- The real-yield-level relationship is an empirical regularity of 2005-2026, not a law. If a new
  regime breaks it (e.g. yields high *and* rising for years with gold unbid), v2 will be wrong —
  the 12M-trend sleeve is the hedge.
- CB series pre-2010 in the engine falls back to the 2009 annual figure (−34t) for earlier dates;
  the true 2004-08 CBGA-era selling was heavier. Affects only pre-2009 spot history cosmetics.

## Recommendations beyond the code change

1. **Spot index label copy**: display it as *conditions* ("HOT / SUPPORTIVE / WEAK"), not
   outlook — at ≥70 it has historically been a stretched-conditions warning (next 3M −0.6%).
   The predictive claim now lives exclusively in the Forward Signal.
2. Revisit v2 when a real *monthly* CB flow series or COT positioning is added as a data source —
   both were out of scope here (COT untested; annual-derived CB momentum is degenerate).

## Reproduce

```bash
cd research
python3 scripts/parse_inputs.py                    # data-quality gates on cache/raw
# regenerate engine ground truth:
./gradlew :app:testDebugUnitTest --tests "com.sun.aurum.research.EngineHistoryDumpTest"
python3 scripts/engine_replica.py                  # replica == engine (1e-5)
python3 scripts/evaluate_shipped.py                # v1 baseline evaluation
python3 scripts/variants.py                        # pre-registered factor zoo + configs
python3 scripts/round2.py; python3 scripts/round3.py; python3 scripts/final_check.py
```

`cache/` is reproducible and gitignored; `results/*.csv` hold the monthly datasets behind every
number above; `results/forward_signal_v2.png` charts v1 vs v2 against gold.
