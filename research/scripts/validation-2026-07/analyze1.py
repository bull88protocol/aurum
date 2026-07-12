#!/usr/bin/env python3
"""Part 1: data integrity of gold_index_history.csv + nowcast fidelity vs independent LBMA gold."""
import json
import pandas as pd
import numpy as np


def spearman(a, b):
    j = pd.DataFrame({"a": a, "b": b}).dropna()
    if len(j) < 3: return float("nan")
    return j["a"].rank().corr(j["b"].rank())

SP = "/tmp/claude-1001/-home-sun-gold/9a849d98-cd7c-4df1-bc21-76eb168561c9/scratchpad"
REPO = "/home/sun/gold"

# ── Load the app's phone export ──────────────────────────────────────────────
app = pd.read_csv(f"{REPO}/gold_index_history.csv", skiprows=2)
app.columns = ["date", "composite", "ry", "usd", "cb", "inf", "tech"]
app["date"] = pd.to_datetime(app["date"])
app = app.set_index("date").sort_index()

print("=== A. APP CSV STRUCTURE ===")
print(f"rows: {len(app)}  span: {app.index[0].date()} -> {app.index[-1].date()}")
gaps = app.index.to_series().diff().dt.days.dropna()
print(f"date gaps: max {gaps.max():.0f}d, gaps>5d: {(gaps>5).sum()} (holidays ok)")
big = gaps[gaps > 5]
if len(big): print("  big gaps:", [(d.date(), int(g)) for d, g in big.items()][:10])
for c in ["composite", "ry", "usd", "cb", "inf", "tech"]:
    s = app[c]
    print(f"  {c:10s} min {s.min():5.1f} max {s.max():5.1f} mean {s.mean():5.1f} NaN {s.isna().sum()}")

# ── Engine dump comparison (same math, desktop run 2026-07-03) ──────────────
eng = pd.read_csv(f"{REPO}/research/cache/engine/engine_spot_daily.csv", parse_dates=["date"]).set_index("date")
common = app.index.intersection(eng.index)
m = app.loc[common].join(eng.loc[common], rsuffix="_e")
print(f"\n=== B. APP EXPORT vs ENGINE DUMP ({len(common)} common days) ===")
for c in ["composite", "ry", "usd", "cb", "inf", "tech"]:
    d = (m[c] - m[f"{c}_e"]).abs()
    print(f"  {c:10s} max|diff| {d.max():7.3f}  mean|diff| {d.mean():7.4f}  days>0.2: {(d>0.2).sum()}")
worst = (m["composite"] - m["composite_e"]).abs().sort_values(ascending=False).head(5)
print("  worst composite diffs:", [(d.date(), round(v, 2)) for d, v in worst.items()])

# ── LBMA gold (independent, authoritative) ───────────────────────────────────
lb = json.load(open(f"{SP}/lbma_gold_pm.json"))
lbma = pd.DataFrame([(r["d"], r["v"][0]) for r in lb if r["v"] and r["v"][0]], columns=["date", "pm_usd"])
lbma["date"] = pd.to_datetime(lbma["date"])
lbma = lbma.set_index("date").sort_index()["pm_usd"].astype(float)
print(f"\n=== C. LBMA PM FIX (independent) ===")
print(f"rows {len(lbma)}, span {lbma.index[0].date()} -> {lbma.index[-1].date()}, last {lbma.iloc[-1]}")

# validate the app's GLD input (research cache, Yahoo) against LBMA
gld = pd.read_csv(f"{REPO}/research/cache/inputs/gld.csv", parse_dates=["date"]).set_index("date")["close"]
both = pd.DataFrame({"gld": gld, "lbma": lbma}).dropna()
ratio = both["gld"] / both["lbma"]
r_corr = both["gld"].pct_change().corr(both["lbma"].pct_change())
yrs = (both.index[-1] - both.index[0]).days / 365.25
drift = (ratio.iloc[-1] / ratio.iloc[0]) ** (1 / yrs) - 1
print(f"GLD(Yahoo cache) vs LBMA: {len(both)} common days, daily-return corr {r_corr:.3f}")
print(f"  GLD/spot ratio {ratio.iloc[0]:.4f} ({both.index[0].date()}) -> {ratio.iloc[-1]:.4f} ({both.index[-1].date()}), drift {drift*100:+.2f}%/yr (GLD expense ratio = -0.40%)")

# FRED cache (Jul 3) vs fresh FRED (today)
for name in ["dfii10", "t10yie", "dgs2"]:
    cache = pd.read_csv(f"{REPO}/research/cache/inputs/{name}.csv", parse_dates=["date"]).set_index("date")["value"]
    fresh = pd.read_csv(f"{SP}/fred_{name.upper()}.csv", na_values=["."])
    fresh.columns = ["date", "value"]
    fresh["date"] = pd.to_datetime(fresh["date"])
    fresh = fresh.set_index("date")["value"].dropna()
    j = pd.DataFrame({"c": cache, "f": fresh}).dropna()
    print(f"FRED {name.upper()}: cache vs fresh on {len(j)} common days, max|diff| {(j['c']-j['f']).abs().max():.4f}; fresh last {fresh.index[-1].date()} = {fresh.iloc[-1]}")

# ── D. landmarks: known market extremes vs the index ─────────────────────────
print("\n=== D. LANDMARKS (LBMA PM fix vs app index) ===")
marks = [
    ("2011-09-06", "gold all-time peak of the 2011 cycle"),
    ("2013-04-15", "crash day (-9%)"),
    ("2015-12-17", "cycle bottom ~$1049"),
    ("2020-08-06", "COVID-era peak ~$2067"),
    ("2022-09-26", "2022 trough ~$1620"),
    ("2024-10-30", "2024 run ~$2780"),
    ("2026-04-30", "2026 spring"),
    ("2026-07-09", "latest"),
]
for d, note in marks:
    dt = pd.Timestamp(d)
    g = lbma.asof(dt)
    row = app.asof(dt)
    print(f"  {d} gold ${g:7.2f} | index {row['composite']:5.1f} (ry {row['ry']:4.1f} usd {row['usd']:4.1f} cb {row['cb']:4.1f} inf {row['inf']:4.1f} tech {row['tech']:4.1f}) | {note}")

# peak/trough of LBMA itself in 2025-2026 for context
recent = lbma.loc["2025":]
print(f"  2025-26 gold: max ${recent.max():.2f} on {recent.idxmax().date()}, latest ${recent.iloc[-1]:.2f} ({(recent.iloc[-1]/recent.max()-1)*100:+.1f}% off the high)")

# ── E. NOWCAST FIDELITY: index vs actual gold ────────────────────────────────
print("\n=== E. NOWCAST: does the index reflect gold conditions? (vs LBMA, independent) ===")
# align app composite with LBMA on LBMA's grid
df = pd.DataFrame({"idx": app["composite"], "gold": lbma}).dropna()
df["ret63t"] = df["gold"] / df["gold"].shift(63) - 1        # trailing 3M gold return
df["ret63f"] = df["gold"].shift(-63) / df["gold"] - 1       # forward 3M gold return
me = df.resample("ME").last().dropna(subset=["idx", "gold"])  # month-end obs
me["dIdx"] = me["idx"].diff()
me["mret"] = me["gold"].pct_change()

sp_coin = spearman(me["idx"], me["ret63t"])
pe_delta = me["dIdx"].corr(me["mret"])
sp_fwd = spearman(me["idx"], me["ret63f"])
print(f"monthly n={len(me)}")
print(f"  Spearman(index level, TRAILING 3M gold return)  = {sp_coin:+.3f}   <- nowcast claim ~+0.40")
print(f"  Pearson(monthly d-index, monthly gold return)    = {pe_delta:+.3f}   <- claim ~+0.55")
print(f"  Spearman(index level, FORWARD 3M gold return)    = {sp_fwd:+.3f}   <- claim ~-0.15 (why label is conditions, not outlook)")

# per era
eras = [("2005", "2012"), ("2013", "2018"), ("2019", "2021"), ("2022", "2026")]
print("  era breakdown (coincident spearman | delta pearson | forward spearman):")
for a, b in eras:
    sub = me.loc[a:b]
    print(f"    {a}-{b}: {spearman(sub['idx'], sub['ret63t']):+.2f} | {sub['dIdx'].corr(sub['mret']):+.2f} | {spearman(sub['idx'], sub['ret63f']):+.2f}  (n={len(sub)})")

# label-bucket behaviour of the SPOT index (what follows HOT / MIXED / WEAK)
base = me["ret63f"].dropna()
print(f"\n  base rate: P(gold up next 3M) = {(base>0).mean()*100:.1f}%, mean {base.mean()*100:+.2f}%  (n={len(base)})")
for name, lo, hi in [("HOT  >=70", 70, 101), ("MIXED 45-70", 45, 70), ("WEAK <45", -1, 45)]:
    b = me[(me["idx"] >= lo) & (me["idx"] < hi)]["ret63f"].dropna()
    if len(b):
        print(f"  spot {name:12s} n={len(b):3d}: next-3M mean {b.mean()*100:+5.2f}%, up {(b>0).mean()*100:4.1f}%")

# component pinning at percentile clamps (5/95)
print("\n=== F. COMPONENT PINNING (percentile clamp saturation, daily) ===")
for c in ["ry", "inf"]:
    s = app[c]
    at5, at95 = (s <= 5.01).mean() * 100, (s >= 94.99).mean() * 100
    # current streak at the clamp
    cur = 0
    for v in s.iloc[::-1]:
        if v <= 5.01: cur += 1
        else: break
    # longest streak either clamp
    pin = (s <= 5.01) | (s >= 94.99)
    grp = (pin != pin.shift()).cumsum()
    longest = pin.groupby(grp).sum().max()
    print(f"  {c}: at 5.0 {at5:4.1f}% of days, at 95.0 {at95:4.1f}% -> pinned total {at5+at95:4.1f}%; longest pinned streak {longest:.0f}d; CURRENT streak at 5.0: {cur}d")

app.to_pickle(f"{SP}/app.pkl"); df.to_pickle(f"{SP}/df.pkl"); me.to_pickle(f"{SP}/me.pkl")
lbma.to_frame("pm").to_pickle(f"{SP}/lbma.pkl")
print("\nsaved pickles for part 2")
