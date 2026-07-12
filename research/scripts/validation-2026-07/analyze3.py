#!/usr/bin/env python3
"""Part 3: the Jan-2026 top episode, joint spot+forward conditioning, spot-RY de-pinning variant."""
import pandas as pd
import numpy as np

SP = "/tmp/claude-1001/-home-sun-gold/9a849d98-cd7c-4df1-bc21-76eb168561c9/scratchpad"
REPO = "/home/sun/gold"

def spearman(a, b):
    j = pd.DataFrame({"a": a, "b": b}).dropna()
    if len(j) < 3: return float("nan")
    return j["a"].rank().corr(j["b"].rank())

app = pd.read_pickle(f"{SP}/app.pkl")
lbma = pd.read_pickle(f"{SP}/lbma.pkl")["pm"]
fw = pd.read_pickle(f"{SP}/fw.pkl")

print("=== J. WHAT THE SPOT INDEX (conditions dial) SAID AT THE JAN-2026 TOP ===")
win = app.loc["2025-11-01":"2026-03-15", "composite"]
print(f"  spot index Nov25-Mar26: max {win.max():.1f} on {win.idxmax().date()}, days >=70 (HOT): {(win>=70).sum()}")
for d in ["2025-12-31", "2026-01-15", "2026-01-29", "2026-02-11", "2026-02-27"]:
    dt = pd.Timestamp(d)
    print(f"  {d}: gold ${lbma.asof(dt):7.0f} | spot {app['composite'].asof(dt):5.1f} | ry {app['ry'].asof(dt):4.1f} inf {app['inf'].asof(dt):4.1f} tech {app['tech'].asof(dt):4.1f}")

print("\n=== K. JOINT CONDITIONING: forward BULLISH split by spot condition (exploratory, small n) ===")
me_spot = app["composite"].resample("ME").last()
j = fw.dropna(subset=["forward", "f63"]).copy()
j["spot"] = [me_spot.asof(d) for d in j.index]
for fname, flo, fhi in [("fwd BULLISH", 70, 101), ("fwd NEUTRAL", 45, 70), ("fwd BEARISH", -1, 45)]:
    for sname, slo, shi in [("spot HOT>=70", 70, 101), ("spot <70", -1, 70)]:
        b = j[(j["forward"] >= flo) & (j["forward"] < fhi) & (j["spot"] >= slo) & (j["spot"] < shi)]["f63"]
        if len(b) >= 5:
            print(f"  {fname} & {sname:13s} n={len(b):3d}: mean {b.mean()*100:+5.2f}%, up {(b>0).mean()*100:4.1f}%")

# also: spot HOT alone vs 12M-trend blowoff measure at the 2026 top
print("\n  (12M ROC at 2026-01-30 month-end: {:+.1f}% — TREND_ANCHORS cap at +50 -> 90)".format(
    (lbma.asof(pd.Timestamp('2026-01-30')) / lbma.asof(pd.Timestamp('2025-01-30')) - 1) * 100))

print("\n=== L. SPOT RY COMPONENT: de-pinning variant (0.5 structural + 0.5 percentile) ===")
fred = pd.read_csv(f"{SP}/fred_DFII10.csv", na_values=["."]); fred.columns = ["date", "v"]
dfii = fred.set_index(pd.to_datetime(fred["date"]))["v"].dropna()
RY_ANCH = [(-1.0, 12), (-0.5, 22), (0.0, 32), (0.5, 42), (1.0, 52), (1.5, 62), (2.0, 74), (2.5, 86), (3.0, 92)]
def piecewise(x, anch):
    if x <= anch[0][0]: return anch[0][1]
    if x >= anch[-1][0]: return anch[-1][1]
    for (x0, y0), (x1, y1) in zip(anch, anch[1:]):
        if x0 <= x <= x1: return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return 50.0

# replicate the shipped spot RY (1y inverted percentile, clamped) on app dates, then the variant
vals = dfii.values; dates = dfii.index
ry_ship, ry_var = {}, {}
for d in app.index:
    pos = dates.searchsorted(d, side="right")
    if pos < 5: continue
    w = vals[max(0, pos-252):pos]
    cur = w[-1]
    pct_inv = min(max((w > cur).mean(), 0.05), 0.95) * 100
    ry_ship[d] = pct_inv
    ry_var[d] = 0.5 * (100 - piecewise(cur, RY_ANCH)) + 0.5 * pct_inv
ry_ship = pd.Series(ry_ship); ry_var = pd.Series(ry_var)
print(f"  replica vs app csv RY: max|diff| {(ry_ship - app['ry']).abs().max():.2f} (sanity)")

# rebuild composite with the variant RY, everything else from the app CSV
W = {"ry": .30, "usd": .23, "cb": .22, "inf": .13, "tech": .12}
comp_var = (ry_var * W["ry"] + app["usd"] * W["usd"] + app["cb"] * W["cb"]
            + app["inf"] * W["inf"] + app["tech"] * W["tech"])
df = pd.DataFrame({"gold": lbma, "ship": app["composite"], "var": comp_var}).dropna()
df["ret63t"] = df["gold"] / df["gold"].shift(63) - 1
me = df.resample("ME").last().dropna()
me["mret"] = me["gold"].pct_change()
for nm, col in [("shipped", "ship"), ("RY-blend", "var")]:
    sp = spearman(me[col], me["ret63t"])
    pe = me[col].diff().corr(me["mret"])
    pin = ((ry_ship if nm == "shipped" else ry_var) <= 5.01).mean() * 100 + ((ry_ship if nm == "shipped" else ry_var) >= 94.99).mean() * 100
    print(f"  {nm:9s}: coincident spearman {sp:+.3f} | delta pearson {pe:+.3f} | RY pinned {pin:4.1f}% of days")

print("\n=== M. MISC SANITY ===")
dxy = pd.read_csv(f"{REPO}/research/cache/inputs/dxy.csv", parse_dates=["date"]).set_index("date")["close"]
print(f"  DXY cache last: {dxy.index[-1].date()} = {dxy.iloc[-1]:.2f} (app USD score today 45.2 -> structural ~100-101 zone: consistent)")
t = pd.read_csv(f"{SP}/fred_T10YIE.csv", na_values=["."]); t.columns = ["date", "v"]
t10 = t.set_index(pd.to_datetime(t["date"]))["v"].dropna()
w = t10.iloc[-252:]
print(f"  T10YIE now {t10.iloc[-1]} | 1y window [{w.min()}, {w.max()}] -> {(w < t10.iloc[-1]).mean()*100:.0f}th pctile: app inf score 5.0 legit")
w = dfii.iloc[-252:]
print(f"  DFII10 now {dfii.iloc[-1]} | 1y window [{w.min()}, {w.max()}] -> {(w > dfii.iloc[-1]).mean()*100:.0f}% of window above -> app ry score 5.0 legit")
# forward label time distribution
full = fw.dropna(subset=["forward"])
print(f"  forward label mix 2005-2026: BULL {(full['forward']>=70).mean()*100:.0f}% | NEUT {((full['forward']>=45)&(full['forward']<70)).mean()*100:.0f}% | BEAR {(full['forward']<45).mean()*100:.0f}% of months")
