#!/usr/bin/env python3
"""Follow-ups, pre-registered before running:
H1  change-gap: gold 20d return minus the 2-factor implied return (expanding-window OLS, point-in-time).
    Does a big gap (gold ran ahead of / behind the macro) predict the next 20d / 63d?
H2  level-gap (WGC-style fair value): log gold ~ DFII10 + log broad USD, rolling 36-month fit.
    Does gold 'rich vs the model' predict the next 63d?
H3  beta-free confidence: trailing 2y same-window tracking (Spearman CGS vs gold 20d) ->
    next 1y tracking. Does a 'LOW confidence' regime persist?
"""
import numpy as np
import pandas as pd

from pathlib import Path

SRC = Path(__file__).resolve().parent / "cgs_backtest.py"
code = open(SRC).read().split("\nbase = build()")[0]
exec(code)  # helpers + data only, no report

base = build()
base = base[base.index >= "2007-01-01"].dropna(subset=["cgs", "ry_ch", "usd_ch"])

# ── H1: change-gap ─────────────────────────────────────────────────────────────────────────
nz = every(base, 20).copy()
X = np.column_stack([np.ones(len(nz)), nz["ry_ch"], nz["usd_ch"]])
y = nz["back20"].to_numpy()
gap = np.full(len(nz), np.nan); implied = np.full(len(nz), np.nan)
for i in range(36, len(nz)):
    ok = ~np.isnan(y[:i])
    beta, *_ = np.linalg.lstsq(X[:i][ok], y[:i][ok], rcond=None)   # fit on windows fully before t
    implied[i] = X[i] @ beta
    gap[i] = y[i] - implied[i]
nz["implied20"] = implied; nz["gap20"] = gap
print("=== H1: gold's 20d move minus the macro-implied move (expanding OLS, n=%d) ===" % np.isfinite(gap).sum())
beta_full, *_ = np.linalg.lstsq(X[~np.isnan(y)], y[~np.isnan(y)], rcond=None)
print(f"  full-sample betas: {beta_full[1]:+.2f}% gold per +1pp real yield (20d), {beta_full[2]:+.2f}% gold per +1% dollar; drift {beta_full[0]:+.2f}%")
print(f"  Spearman(gap, next 20d) {spearman(nz['gap20'], nz['fwd20']):+.2f} {boot_ci(nz['gap20'], nz['fwd20'])}")
me = month_ends(base).copy()
me["gap20"] = asof(nz["gap20"], me.index)
print(f"  Spearman(gap, next 63d, month-ends) {spearman(me['gap20'], me['fwd63']):+.2f} {boot_ci(me['gap20'], me['fwd63'])}")
q = nz.dropna(subset=["gap20", "fwd20"]).copy()
q["qt"] = pd.qcut(q["gap20"], 5, labels=["Q1 gold far BEHIND macro", "Q2", "Q3", "Q4", "Q5 gold far AHEAD of macro"])
print(q.groupby("qt", observed=True).agg(n=("fwd20", "size"), gap=("gap20", "median"),
                                          next20=("fwd20", "mean"), up=("fwd20", lambda x: 100 * (x > 0).mean())).round(2).to_string())
for lo, hi in ERAS:
    a = sub(nz, lo, hi)
    print(f"   {lo}-{hi}: Spearman(gap, next20) {spearman(a['gap20'], a['fwd20']):+.2f}")

# ── H2: level-gap, WGC-style ───────────────────────────────────────────────────────────────
print("\n=== H2: WGC-style level model  log(gold) ~ DFII10 + log(broad USD), rolling 36 month-ends ===")
m = month_ends(base).copy()
m["usd_lvl"] = asof(usd, m.index, h10_available)
m = m.dropna(subset=["usd_lvl", "ry"])
Xl = np.column_stack([np.ones(len(m)), m["ry"], np.log(m["usd_lvl"])])
yl = np.log(m["gold"].to_numpy())
lg = np.full(len(m), np.nan); r2 = np.full(len(m), np.nan)
for i in range(35, len(m)):
    Xs, ys = Xl[i - 35:i + 1], yl[i - 35:i + 1]
    b, *_ = np.linalg.lstsq(Xs, ys, rcond=None)
    res = ys - Xs @ b
    lg[i] = res[-1] * 100; r2[i] = 1 - res.var() / ys.var()
m["lgap"] = lg; m["r2"] = r2
print(f"  median rolling-3y R^2 {np.nanmedian(r2):.2f}  (levels R^2 flatters: trending series)")
print(f"  Spearman(level gap, next 63d) {spearman(m['lgap'], m['fwd63']):+.2f} {boot_ci(m['lgap'], m['fwd63'])}   "
      f"next 20d {spearman(m['lgap'], m['fwd20']):+.2f}")
for lo, hi in ERAS:
    a = sub(m, lo, hi)
    print(f"   {lo}-{hi}: {spearman(a['lgap'], a['fwd63']):+.2f}")
print("  gap (gold % above the rolling model) at landmarks:")
for dte in ["2011-08-31", "2012-12-31", "2020-07-31", "2022-09-30", "2024-12-31", "2025-10-31", "2026-01-30", "2026-06-30", "2026-08-31"]:
    r = m[m.index <= dte].iloc[-1]
    print(f"   {r.name.date()}  gold ${r['gold']:,.0f}  gap {r['lgap']:+.1f}%  next63 {r['fwd63']:+.1f}%")

# ── H3: beta-free confidence persistence ───────────────────────────────────────────────────
print("\n=== H3: does 'the macro is in control' persist? trailing-2y tracking -> next-1y tracking ===")
pts = every(base, 20)
c = pts["cgs"].to_numpy(); g = pts["back20"].to_numpy()
trail, nxt, dates = [], [], []
for i in range(26, len(pts) - 13, 13):          # non-overlapping yearly steps
    trail.append(spearman(c[i - 26:i], g[i - 26:i])); nxt.append(spearman(c[i:i + 13], g[i:i + 13])); dates.append(pts.index[i])
t = pd.DataFrame({"trail2y": trail, "next1y": nxt}, index=dates)
print(t.round(2).to_string())
print(f"  corr(trailing, next) = {pearson(t['trail2y'], t['next1y']):+.2f}  (n={len(t)})")

# ── August 2026 check ──────────────────────────────────────────────────────────────────────
print("\n=== LBMA month-ends 2026 ===")
g26 = gold[gold.index >= "2025-12-01"]
mm = g26.groupby([g26.index.year, g26.index.month]).tail(1)
prev = None
for d_, v in mm.items():
    print(f"   {d_.date()}  ${v:,.2f}" + (f"  {100 * (v / prev - 1):+.1f}% m/m" if prev else ""))
    prev = v
print("  gold 20d/60d:", f"{100 * (gold.iloc[-1] / gold.iloc[-21] - 1):+.1f}% / {100 * (gold.iloc[-1] / gold.iloc[-61] - 1):+.1f}%")
nzl = nz.iloc[-3:][["gold", "back20", "cgs", "implied20", "gap20"]]
print("  last non-overlapping 20d windows:\n", nzl.round(2).to_string())
