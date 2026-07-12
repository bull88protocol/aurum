# Gold Index deep-dive validation — session handoff (2026-07-10)

**What this is:** a full independent audit of the shipped Gold Index (spot nowcast + Forward
Signal v2) against real gold prices, run 2026-07-10 in Claude Code. Everything below was
*measured in-session* from the app's own 20-year CSV export, the engine's test dump, and fresh
independently-fetched LBMA/FRED data — no numbers taken on faith from `research/README.md`
(they were all re-derived and confirmed). A new session can pick up from this file alone.

**Verdict:** CSV export is legit (engine-exact); the spot index is a faithful *nowcast* in every
era; Forward Signal v2 reproduces its claimed out-of-sample skill on an independent outcome
series. **No changes to the shipped math.** Two small non-math adjustments recommended
(§8), three tempting changes explicitly rejected with measurements (§8), one live watch-item
(§4: v2 has been BULLISH through gold's −24% crash — three consecutive misses, still BULLISH).

**Charts artifact:** https://claude.ai/code/artifact/cb1daf63-7111-4601-b3f6-abfa9fda49c2
(gold vs index 2005-2026, the 2025-26 live episode, label-outcome bars; private to Amit's account).

---

## 1. Verification chain — is the data legit? (all PASS)

| check | result |
|---|---|
| `gold_index_history.csv` (phone export, 5,393 rows 2005-02-01→2026-07-10) vs `research/cache/engine/engine_spot_daily.csv` | max\|diff\| **0.05** on all 5,388 common days = CSV 1-decimal rounding. Phone == engine exactly. |
| CSV structure | no NaN, no gaps >5d, all scores in [0,100]. Composite range 22.4–83.2, mean 54.8. |
| GLD input (Yahoo, `research/cache/inputs/gld.csv`) vs **LBMA PM fixes** (fetched fresh) | monthly return corr **0.975**; GLD/spot ratio drifts **−0.43%/yr** = GLD's 0.40% expense ratio. Daily corr 0.654 is PM-fix timing (3pm London vs 4pm NY), not a data problem. |
| FRED cache vs fresh FRED pulls | DFII10/T10YIE/DGS2 max\|diff\| **0.0000** on ~6k days each. |
| Today's component scores vs fresh data | RY 5.0 ✓ (DFII10 2.31% on 7/8 = *highest of its 1y window*, clamp at 5). INF 5.0 ✓ (T10YIE 2.23% = 3rd pctile of 1y). USD 45.2 ✓ (DXY 100.81 on 7/3). CB 75.5 ✓ (trailing 4 published WGC quarters 166+218+230+244 = **858t** → piecewise 75.48). |
| CB feed `data/cb_quarterly.json` | real WGC actuals: 2024-Q4 333, 2025 Q1-Q4 249/166/218/230 (FY **863t**), 2026-Q1 244. `asOf: 2026-Q1`, updated 2026-07-03. |

Market context established from LBMA (14,636 fixes, 1968→2026-07-09, keyless JSON):
gold topped **$5,405 on 2026-01-29**, latest fix **$4,130.15 (2026-07-09) = −23.6% off the top**.

## 2. Spot index as nowcast — CONFIRMED (vs independent LBMA, 258 month-ends)

| metric | README claim | re-measured (LBMA) |
|---|---|---|
| Spearman(level, trailing-3M gold return) | +0.40 | **+0.401** |
| Pearson(monthly Δindex, monthly gold return) | +0.55 | **+0.519** |
| Spearman(level, FORWARD 3M return) | −0.15 | **−0.133** |

Era breakdown (coincident / delta / forward): 2005-12 +0.22/+0.52/−0.35 · 2013-18
+0.41/+0.50/−0.42 · 2019-21 +0.16/+0.63/−0.39 · 2022-26 +0.67/+0.52/+0.25.
Delta-correlation ~+0.5 in **every** era — the nowcast tracks gold in all regimes.

Spot label buckets → next 3M (base rate 63.1% up, +3.19%):
**HOT ≥70: n=38, −0.31%, 47.4% up** · MIXED: n=156, +3.63%, 64.1% · WEAK <45: n=61, +4.24%, 70.5%.
→ The HOT/MIXED/WEAK *conditions* copy (not bullish/bearish) is verified correct; HOT is a
stretched-conditions warning.

Landmarks (LBMA $ / index): 2011-09-06 peak $1,895 / **69.9** · 2013-04-15 crash $1,395 / 58.0 ·
2015-12-17 bottom $1,049 / **27.4** · 2020-08-06 peak $2,067 / **76.7** · 2022-09-26 trough
$1,643 / **23.9** · 2026-01-29 top $5,405 / **70.8 (HOT on the top day)** · 2026-07-09 $4,130 / 32.0.

Percentile-clamp pinning (daily, 21y): RY at 5/95 on **26.5%** of days (longest streak 43d;
currently 8d at 5.0) · INF **22.0%** (longest 55d). Cosmetic — see §7 for why we keep it.

## 3. Forward Signal v2 — CONFIRMED out-of-sample (vs independent LBMA outcomes)

Engine-dump month-ends (`research/cache/engine/engine_forward_monthly.csv`) scored against
next-63-LBMA-day returns (backtest used GLD, so this is an independent outcome series):

| period | README claim | re-measured |
|---|---|---|
| full 2005-26 IC63 | +0.330 | **+0.319** (n=254) |
| train 2005-18 | +0.295 | **+0.290** |
| test 2019-26 | +0.382 | **+0.353** |
| full IC126 | +0.496 | **+0.489** |

Eras all positive: +0.21 / +0.28 / +0.33 / +0.35. Old v1 re-measured **−0.036** (claim −0.05) — junk, correctly replaced.

Label buckets (n=254): **BULLISH n=49: +8.18%, 83.7% up · NEUTRAL n=113: +3.11%, 62.8% ·
BEARISH n=92: +0.30%, 51.1%** — monotone, matches claims. Label mix: 21/44/36% of months.
Turning points verified: 2012-09 → 31.4 (−4.6% followed) · 2013-01 → 29.3 (−11.7%) · 2015-11 →
51.7 (+17.7%) · 2020-07 → 36.6 (−4.8%) · 2022-10 → 56.8 (+17.2%) · 2024-02 → 71.3 (+14.1%).

## 4. THE LIVE EPISODE (new since README) — v2 through the 2026 top & crash

Month-end v2 (RR/Trend/Fed sleeves) → actual next-63d LBMA return:

| month-end | gold | v2 | sleeves RR/T/F | next 63d |
|---|---|---|---|---|
| 2025-06-30 | $3,287 | 74.2 B | 74/87/58 | **+14.7%** |
| 2025-07-31 | $3,299 | 72.3 B | 75/83/50 | **+21.5%** |
| 2025-08-29 | $3,429 | 70.4 B | 68/83/62 | **+20.7%** |
| 2025-09-30 | $3,825 | 70.2 B | 67/87/59 | **+13.8%** |
| 2025-10-31 | $4,012 | 70.0 B | 67/87/57 | **+24.3%** |
| 2025-11-28 | $4,191 | 70.1 B | 66/90/58 | **+22.8%** |
| 2025-12-31 | $4,368 | 73.7 B | 72/90/58 | +8.9% |
| 2026-01-30 | $4,982 | 72.1 B | 70/90/56 | **−6.9%** ← top was 01-29 |
| 2026-02-27 | $5,222 | 66.8 N | 60/90/56 | **−13.8%** |
| 2026-03-31 | $4,608 | 74.2 B | 76/90/48 | **−10.4%** |
| 2026-04-30 | $4,611 | 70.3 B | 72/84/49 | n/a yet |
| 2026-05-29 | $4,546 | 74.0 B | 80/83/46 | n/a |
| 2026-06-30 | $4,026 | 74.9 B | 86/72/48 | n/a |
| 2026-07-02 | $4,129 | 75.7 B | 87.5/72/47 | n/a |

Reading: v2 nailed the entire 2025 advance, then **stayed BULLISH through the top and −24%
crash** (3 misses so far). Mechanism: 12M-trend sleeve maxed at 90 (ROC252 peaked **+79%**,
beyond the +50 anchor cap — blow-off read as max trend support) while the real-rate sleeve
climbed (DFII10 1.77% Oct-25 → 1.89% at the top → **2.31%** now = rates high *and rising* =
the README's stated failure regime). The spot dial flashed HOT the day of the top — the
two dials together told the right story.

**Today's live recompute from fresh data (2026-07-10):** DFII10 2.31 → fixed 81.4, 95th 5y pctile
→ RR 88.2 · LBMA ROC252 +24.6% → 73.7 · DGS2 Δ63 +0.42 → 46.6 · **v2 = 76.3 BULLISH**
(engine said 75.7 on 7/2 ✓). This is a live contrarian bet from a stance that's been wrong 3
months; historically the bucket is 84% up / +8.2% mean. If real yields keep rising with gold
unbid, it stays wrong — known limitation, now in-sample-of-life.

## 5. Joint conditioning (spot condition × forward label) — the one real gap found

Forward-label outcomes split by spot HOT (n=254 month-ends):

| cell | n | mean next 3M | % up |
|---|---|---|---|
| fwd BULLISH & spot <70 | 46 | +8.24% | 82.6% |
| fwd BULLISH & spot HOT | **3** | +1% / +7% / +14% | (2024-09, 2025-03, 2025-09 — all fine, too few to judge) |
| fwd NEUTRAL & spot <70 | 98 | +3.46% | 64.3% |
| fwd NEUTRAL & spot HOT | 15 | **+0.80%** | 53.3% |
| fwd BEARISH & spot <70 | 75 | +1.36% | 58.7% |
| fwd BEARISH & spot HOT | 17 | **−4.39%** | **17.6%** |

The BEARISH&HOT months: 2012-01..04, 2012-07..11 (pre-2013-crash grind), 2014-07/08, 2016-09,
2020-07/08 (the exact 2020 top), 2020-12/2021-01/02 (double top). Coherent exhaustion list.
→ spot-HOT systematically degrades outcomes; evidence for a UI caution (§8), NOT an override
of BULLISH (that cell is n=3 and benign).

## 6. Variant tested and REJECTED: de-pinning the spot RY gauge

Tried spot RY = 0.5 × structural DFII10 anchor (inverted RY_REGIME map) + 0.5 × 1y inverted
percentile (analogous to the USD component's blend), rebuilt the composite:

| | coincident Spearman | delta Pearson | RY pinned days |
|---|---|---|---|
| shipped | **+0.401** | +0.516 | 26.5% |
| RY-blend variant | +0.292 | +0.580 | 0.0% |

Pinning disappears but the *primary* nowcast metric collapses (−0.11). Same conclusion as the
research team's 504d-window test. **Keep the shipped percentile; the stuck-at-5/95 needle is a
cosmetic quirk of a correct design.**

## 7. Notes a pro trader would raise, checked and accepted as-is

- RSI map in Technical is mean-reversion-tilted (oversold 28 pts > overbought 10) — odd inside
  "momentum", defensible for gold, only 12% weight, validated as part of the whole. Leave.
- DXY anchors floor at 84 (2008's 71.3 clamps to 92) — directionally right in that era. Leave.
- CB pre-2009 fallback (−34t) understates CBGA-era selling — documented README cosmetic. Leave.
- Missing factors (COT, ETF flows, gold/silver, SGE premium, seasonality): each needs a new data
  source + the same train/test discipline that killed v1. Parked per README rec #2. Agree.

## 8. ADJUSTMENTS — the actionable list

**Recommended (small, no validated-math changes):**
1. **`GoldIndexEngine.kt` bundled CB fallback is stale**: `cbByYear` has `2025 to 1000.0`
   (placeholder) but WGC FY-2025 actual = **863t** (sum of feed quarters). No-feed users get
   score ~90 where feed users correctly get 75.5. Fix: `2025 to 863.0`, bump
   `CB_ESTIMATE_FROM_YEAR = 2026`. (2026 stays 1000 est; Q1 run-rate 244×4 ≈ 976 supports it.)
2. **UI caution chip when spot ≥ 70 (HOT) shown near the Forward Signal** — copy like
   *"Conditions stretched: after HOT readings gold averaged −0.3% over 3M (47% up) vs +3.2%
   base."* Backed by §5. Copy/UI only; engine untouched.

**Rejected (do NOT do, with measured reasons):**
- Reweight/restructure spot index — it passes as nowcast in every era (§2); nothing to fix.
- De-pin the RY percentile — tested §6, degrades the nowcast −0.11 Spearman.
- Blow-off penalty on the trend sleeve (hump-shaped ROC map) — one +79% episode in 21y;
  would be curve-fitting a single point. The HOT caution (n=38) covers the same risk. Revisit
  in the next research round with COT/positioning per README.
- Adding new factors now — see §7 last bullet.

**Housekeeping:** `research/results/monthly_final.csv` predates the 2026-07-03 CB feed update
(its `cb` column reads 84 vs current 75.5) — regenerate on the next research-pipeline run.
The engine dump in `cache/engine/` is current (75.48).

## 9. Data sources & reproduction (all keyless)

```bash
# LBMA PM fixes (authoritative, 1968→now) — needs a browser UA:
curl -H "User-Agent: Mozilla/5.0" https://prices.lbma.org.uk/json/gold_pm.json -o lbma_gold_pm.json
# FRED series:
curl "https://fred.stlouisfed.org/graph/fredgraph.csv?id=DFII10"   # also T10YIE, DGS2
# Stooq XAUUSD: blocked (JS proof-of-work + IP deny) — don't bother.
# Yahoo chart API: 429s without cookie dance; research/cache/inputs/*.csv already has it.
```

Analysis scripts (as run; paths point at the session scratchpad, adapt `SP` before rerun):
`research/scripts/validation-2026-07/analyze1.py` (integrity + nowcast + pinning) ·
`analyze2.py` (v2 IC vs LBMA + live episode + today's recompute) ·
`analyze3.py` (top episode, joint conditioning, de-pin variant) ·
`gen_artifact.py` (charts page). Inputs needed alongside: `lbma_gold_pm.json`,
`fred_DFII10.csv`, `fred_T10YIE.csv`, `fred_DGS2.csv` (fetch commands above) + repo files.

## 10. Next-session pickup checklist

- [ ] Decide on §8.1 (CB fallback 2025→863t) — one-line engine change + test run
      (`./gradlew :app:testDebugUnitTest`, 29 tests; a CB-related expectation may need updating).
- [ ] Decide on §8.2 (HOT caution chip near Forward Signal) — Android UI + copy; consider for v2.2.
- [ ] If either ships: follow branch model (feature branch → master), versionCode 9+, and update
      `research/README.md` with a pointer to this file.
- [ ] Watch item: is v2 still BULLISH while gold keeps falling? (DFII10 rising = its failure regime.)
- [ ] Pending from before this session: v2.1.1 signed AAB awaiting Play upload (see CLAUDE.md).
