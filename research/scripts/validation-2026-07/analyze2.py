#!/usr/bin/env python3
"""Part 2: forward signal v2 validation vs independent LBMA outcomes + today's live read."""
import pandas as pd
import numpy as np

SP = "/tmp/claude-1001/-home-sun-gold/9a849d98-cd7c-4df1-bc21-76eb168561c9/scratchpad"
REPO = "/home/sun/gold"

def spearman(a, b):
    j = pd.DataFrame({"a": a, "b": b}).dropna()
    if len(j) < 3: return float("nan")
    return j["a"].rank().corr(j["b"].rank())

lbma = pd.read_pickle(f"{SP}/lbma.pkl")["pm"]

# forward returns on the LBMA trading grid (63 / 126 LBMA business days ~ 3M / 6M)
lb = lbma.to_frame("g")
lb["f63"] = lb["g"].shift(-63) / lb["g"] - 1
lb["f126"] = lb["g"].shift(-126) / lb["g"] - 1

# engine's own month-end forward signal dump (v2, engine-exact)
fw = pd.read_csv(f"{REPO}/research/cache/engine/engine_forward_monthly.csv", parse_dates=["date"]).set_index("date")
# v1 from the research monthly dataset
v1 = pd.read_csv(f"{REPO}/research/results/monthly_final.csv", parse_dates=["date"]).set_index("date")["forward"]

# align outcomes: for each month-end, the next LBMA fix on/after that date
def outcome(dates, col):
    out = []
    for d in dates:
        pos = lb.index.searchsorted(d)
        out.append(lb[col].iloc[pos] if pos < len(lb) else np.nan)
    return pd.Series(out, index=dates)

fw["f63"] = outcome(fw.index, "f63")
fw["f126"] = outcome(fw.index, "f126")
v1f = pd.DataFrame({"v1": v1})
v1f["f63"] = outcome(v1f.index, "f63")

print("=== G. FORWARD SIGNAL v2 vs INDEPENDENT LBMA OUTCOMES ===")
full = fw.dropna(subset=["forward", "f63"])
train = full.loc[:"2018-12-31"]; test = full.loc["2019-01-01":]
print(f"IC (Spearman vs fwd 63 LBMA days): full {spearman(full['forward'], full['f63']):+.3f} (n={len(full)})  claim +0.33")
print(f"  train 05-18 {spearman(train['forward'], train['f63']):+.3f}  claim +0.295 | test 19-26 {spearman(test['forward'], test['f63']):+.3f}  claim +0.382")
f126 = fw.dropna(subset=["forward", "f126"])
print(f"IC at 126d: full {spearman(f126['forward'], f126['f126']):+.3f}  claim +0.496")
for a, b in [("2005", "2012"), ("2013", "2018"), ("2019", "2021"), ("2022", "2026")]:
    s = full.loc[a:b]
    print(f"  era {a}-{b}: IC63 {spearman(s['forward'], s['f63']):+.2f} (n={len(s)})")

print(f"\nv1 (shipped pre-2026-07) IC63 vs LBMA: {spearman(v1f['v1'], v1f['f63']):+.3f}  claim ~-0.05")

print("\nlabel buckets (v2, full sample) vs base rate:")
base = full["f63"]
print(f"  base: {(base>0).mean()*100:.0f}% up, mean {base.mean()*100:+.2f}%")
for name, lo, hi in [("BULLISH >=70", 70, 101), ("NEUTRAL 45-70", 45, 70), ("BEARISH <45", -1, 45)]:
    b = full[(full["forward"] >= lo) & (full["forward"] < hi)]["f63"]
    print(f"  {name:14s} n={len(b):3d}: mean {b.mean()*100:+5.2f}%, up {(b>0).mean()*100:4.1f}%   claims: 82/64/51% up, +8.1/+3.1/+0.2%")

print("\nturning points (engine v2 at month-end -> actual next-63d LBMA return):")
for d in ["2012-09-28", "2013-01-31", "2015-11-30", "2020-07-31", "2022-10-31", "2024-02-29"]:
    dt = pd.Timestamp(d)
    if dt in fw.index:
        r = fw.loc[dt]
        print(f"  {d}: v2={r['forward']:5.1f} -> next 63d {r['f63']*100:+6.1f}%")

print("\n=== H. THE LIVE TEST: 2025-2026 run, the $5405 top (2026-01-29), the -24% crash ===")
sub = fw.loc["2025-06-01":]
for d, r in sub.iterrows():
    g = lbma.asof(d)
    f63 = f"{r['f63']*100:+6.1f}%" if pd.notna(r["f63"]) else "   n/a"
    print(f"  {d.date()} gold ${g:7.0f} | v2 {r['forward']:5.1f} (RR {r['fRealRate']:5.1f} T {r['fTrend']:5.1f} F {r['fFed']:5.1f}) -> next63d {f63}")

# what would v2 have needed to catch the top? what was DFII10 doing?
fred = pd.read_csv(f"{SP}/fred_DFII10.csv", na_values=["."]); fred.columns = ["date", "v"]
fred["date"] = pd.to_datetime(fred["date"]); dfii = fred.set_index("date")["v"].dropna()
print(f"\n  DFII10 around the top: 2025-10-01 {dfii.asof(pd.Timestamp('2025-10-01'))} | 2026-01-29 {dfii.asof(pd.Timestamp('2026-01-29'))} | now {dfii.iloc[-1]} ({dfii.index[-1].date()})")

# ── I. today's live forward signal from FRESH data ──────────────────────────
print("\n=== I. TODAY'S SIGNAL RECOMPUTED FROM FRESH INDEPENDENT DATA ===")
RY_ANCH = [(-1.0, 12), (-0.5, 22), (0.0, 32), (0.5, 42), (1.0, 52), (1.5, 62), (2.0, 74), (2.5, 86), (3.0, 92)]
TREND_ANCH = [(-25, 15), (-10, 30), (0, 45), (10, 58), (20, 70), (35, 82), (50, 90)]
FED_ANCH = [(-2.0, 90), (-1.0, 75), (-0.25, 60), (0.25, 50), (1.0, 35), (2.0, 15)]

def piecewise(x, anch):
    if x <= anch[0][0]: return anch[0][1]
    if x >= anch[-1][0]: return anch[-1][1]
    for (x0, y0), (x1, y1) in zip(anch, anch[1:]):
        if x0 <= x <= x1: return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return 50

cur_ry = dfii.iloc[-1]
win5y = dfii.iloc[-1260:]
pct5y = min(max((win5y < cur_ry).mean(), 0.05), 0.95)
rr = 0.5 * piecewise(cur_ry, RY_ANCH) + 0.5 * pct5y * 100
roc252 = (lbma.iloc[-1] / lbma.iloc[-253] - 1) * 100
tr = piecewise(roc252, TREND_ANCH)
d2 = pd.read_csv(f"{SP}/fred_DGS2.csv", na_values=["."]); d2.columns = ["date", "v"]
dgs2 = d2.set_index(pd.to_datetime(d2["date"]))["v"].dropna()
delta = dgs2.iloc[-1] - dgs2.iloc[-63]
fed = piecewise(delta, FED_ANCH)
v2_now = 0.55 * rr + 0.25 * tr + 0.20 * fed
lab = "BULLISH" if v2_now >= 70 else ("NEUTRAL" if v2_now >= 45 else "BEARISH")
print(f"  DFII10 {cur_ry}% -> fixed {piecewise(cur_ry, RY_ANCH):.1f}, 5y pct {pct5y*100:.0f}th -> RR sleeve {rr:.1f}")
print(f"  12M trend (LBMA ROC252) {roc252:+.1f}% -> {tr:.1f}")
print(f"  Fed: DGS2 63-obs delta {delta:+.2f} -> {fed:.1f}")
print(f"  Forward v2 TODAY = {v2_now:.1f} ({lab})   [engine said 75.7 BULLISH on 2026-07-02]")

# monthly GLD vs LBMA return corr (close the loop on the 0.654 daily figure = fix-timing artifact)
gld = pd.read_csv(f"{REPO}/research/cache/inputs/gld.csv", parse_dates=["date"]).set_index("date")["close"]
mm = pd.DataFrame({"gld": gld, "lb": lbma}).resample("ME").last().dropna().pct_change().dropna()
print(f"\n  GLD vs LBMA MONTHLY return corr: {mm['gld'].corr(mm['lb']):.3f} (daily 0.654 was PM-fix timing, monthly confirms same asset)")

fw.to_pickle(f"{SP}/fw.pkl")
