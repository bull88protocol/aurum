#!/usr/bin/env python3
"""Core Gold Signal (CGS) backtest -- the proposal exactly as written, on fresh keyless data.

CGS = 0.5*RY + 0.5*USD, each leg = -100*clip(z/2, -1, 1),
z = 20-obs change / rolling-252 std of 20-obs changes (point-in-time, own spine).
RY  = FRED DFII10 (pct-pt change).  USD = FRED DTWEXBGS (% change), publication-lagged
(H.10 is weekly: a week's dailies post the following Monday).  Outcomes = LBMA gold PM fix.
Signal at gold date t uses data available by end of t; forward returns start at the NEXT fix.
"""
import json

import numpy as np
import pandas as pd

from pathlib import Path

HERE = Path(__file__).resolve().parent
RESEARCH = HERE.parents[1]
REPO = str(RESEARCH.parent)
D = str(RESEARCH / "cache" / "drivers-2026-09")   # fetch commands: research/DRIVERS_20D_2026-09-16.md
pd.set_option("display.width", 200)


def fred(sid):
    f = pd.read_csv(f"{D}/fred_{sid}.csv")
    f.columns = ["date", "value"]
    f["value"] = pd.to_numeric(f["value"], errors="coerce")
    f = f.dropna()
    return pd.Series(f["value"].to_numpy(), index=pd.to_datetime(f["date"]))


rows = json.load(open(f"{D}/lbma_gold_pm.json"))
gold = pd.Series({pd.Timestamp(r["d"]): r["v"][0] for r in rows if r["v"] and r["v"][0]}).sort_index()
ry, usd, dgs10, dgs2, t10yie = (fred(s) for s in ["DFII10", "DTWEXBGS", "DGS10", "DGS2", "T10YIE"])
dxy_df = pd.read_csv(f"{REPO}/research/cache/inputs/dxy.csv")
dxy = pd.Series(dxy_df["close"].to_numpy(), index=pd.to_datetime(dxy_df["date"]))


def h10_available(idx):
    # value for day d is published the next Monday strictly after d
    wd = idx.weekday
    add = np.where((7 - wd) % 7 == 0, 7, (7 - wd) % 7)
    return idx + pd.to_timedelta(add, unit="D")


def leg(series, kind, h=20, volwin=252, minvol=126, mapping="clip"):
    ch = series - series.shift(h) if kind == "diff" else (series / series.shift(h) - 1) * 100
    sd = ch.rolling(volwin, min_periods=minvol).std()
    z = ch / sd
    sc = -100 * (np.clip(z / 2, -1, 1) if mapping == "clip" else np.tanh(z / 1.5))
    return pd.DataFrame({"ch": ch, "z": z, "score": sc})


def asof(s, idx, lag_fn=None):
    s = s.dropna()
    keys = (lag_fn(s.index) if lag_fn is not None else s.index).to_numpy()
    order = np.argsort(keys, kind="stable")
    keys, vals = keys[order], s.to_numpy()[order]
    pos = np.searchsorted(keys, idx.to_numpy(), side="right") - 1
    return pd.Series(np.where(pos >= 0, vals[np.clip(pos, 0, None)], np.nan), index=idx)


G = gold[gold.index >= "2003-01-01"]
gi = G.index


def build(h=20, w_ry=0.5, dollar="broad", mapping="clip", lag_usd=True):
    L_ry = leg(ry, "diff", h, mapping=mapping)
    if dollar == "broad":
        L_usd = leg(usd, "pct", h, mapping=mapping)
        av = h10_available if lag_usd else None
    else:
        L_usd = leg(dxy, "pct", h, mapping=mapping)
        av = None
    df = pd.DataFrame(index=gi)
    df["gold"] = G
    df["ry"] = asof(ry, gi)
    df["ry_ch"] = asof(L_ry["ch"], gi)
    df["ry_s"] = asof(L_ry["score"], gi)
    df["usd_ch"] = asof(L_usd["ch"], gi, av)
    df["usd_s"] = asof(L_usd["score"], gi, av)
    df["cgs"] = w_ry * df["ry_s"] + (1 - w_ry) * df["usd_s"]
    g = df["gold"].to_numpy()
    n = len(g)
    for hh in (20, 63):
        back = np.full(n, np.nan)
        back[hh:] = g[hh:] / g[:-hh] - 1
        fwd = np.full(n, np.nan)
        fwd[: n - 1 - hh] = g[1 + hh:] / g[1: n - hh] - 1   # from next fix
        df[f"back{hh}"] = back * 100
        df[f"fwd{hh}"] = fwd * 100
    return df


def spearman(a, b):
    a = np.asarray(a, float); b = np.asarray(b, float)
    m = ~(np.isnan(a) | np.isnan(b))
    if m.sum() < 8:
        return np.nan
    return pd.Series(a[m]).rank().corr(pd.Series(b[m]).rank())


def pearson(a, b):
    a = np.asarray(a, float); b = np.asarray(b, float)
    m = ~(np.isnan(a) | np.isnan(b))
    return np.corrcoef(a[m], b[m])[0, 1] if m.sum() >= 8 else np.nan


def boot_ci(a, b, block=6, n=2000, seed=7):
    a = np.asarray(a, float); b = np.asarray(b, float)
    m = ~(np.isnan(a) | np.isnan(b)); a, b = a[m], b[m]
    N = len(a); nb = int(np.ceil(N / block)); rng = np.random.default_rng(seed); out = []
    for _ in range(n):
        st = rng.integers(0, N - block + 1, nb)
        ix = np.concatenate([np.arange(s, s + block) for s in st])[:N]
        out.append(spearman(a[ix], b[ix]))
    lo, hi = np.nanpercentile(out, [2.5, 97.5])
    return f"[{lo:+.2f},{hi:+.2f}]"


def month_ends(df):
    return df.groupby([df.index.year, df.index.month]).tail(1)


def every(df, k):
    return df.iloc[::k]


ERAS = [("2007", "2012"), ("2013", "2018"), ("2019", "2021"), ("2022", "2026")]


def sub(df, lo, hi):
    return df[(df.index >= f"{lo}-01-01") & (df.index <= f"{hi}-12-31")]


def bucket(x):
    if np.isnan(x): return None
    if x >= 70: return "1 STRONG BULL >=70"
    if x >= 30: return "2 BULL 30..70"
    if x > -30: return "3 NEUTRAL"
    if x > -70: return "4 BEAR -30..-70"
    return "5 STRONG BEAR <=-70"


base = build()
base = base[base.index >= "2007-01-01"].dropna(subset=["cgs"])
print(f"CGS sample: {base.index[0].date()} -> {base.index[-1].date()}  ({len(base)} gold days)")

# ── 1. Coincident (nowcast) vs predictive, non-overlapping 20-day samples ─────────────────
nz = every(base, 20)
print("\n=== 1. What the CGS explains: same-window vs NEXT-window gold (non-overlapping 20d, n=%d) ===" % len(nz))
print(f"{'':28s}{'same 20d':>10s}{'next 20d':>10s}{'next 63d(mo)':>14s}")
me = month_ends(base)
for col, lab in [("cgs", "CGS 50/50"), ("ry_s", "  real-yield leg"), ("usd_s", "  dollar leg")]:
    print(f"{lab:28s}{spearman(nz[col], nz['back20']):+10.2f}{spearman(nz[col], nz['fwd20']):+10.2f}"
          f"{spearman(me[col], me['fwd63']):+14.2f}")
print(f"  CI (block bootstrap)      same {boot_ci(nz['cgs'], nz['back20'])}  next20 {boot_ci(nz['cgs'], nz['fwd20'])}  "
      f"next63 {boot_ci(me['cgs'], me['fwd63'])}")

print("\nBy era (Spearman)        same20  next20  next63")
for lo, hi in ERAS:
    a = sub(nz, lo, hi); m = sub(me, lo, hi)
    print(f"  {lo}-{hi}  n={len(a):3d}      {spearman(a['cgs'], a['back20']):+.2f}   {spearman(a['cgs'], a['fwd20']):+.2f}"
          f"   {spearman(m['cgs'], m['fwd63']):+.2f}")
for lo, hi, lab in [("2007", "2018", "train"), ("2019", "2026", "test ")]:
    a = sub(nz, lo, hi); m = sub(me, lo, hi)
    print(f"  {lab} {lo}-{hi} n={len(a):3d} {spearman(a['cgs'], a['back20']):+.2f}   {spearman(a['cgs'], a['fwd20']):+.2f}"
          f"   {spearman(m['cgs'], m['fwd63']):+.2f}")

# ── 2. Label buckets -> next 20d (non-overlapping) and last 20d ────────────────────────────
print("\n=== 2. Proposal's labels -> gold over the NEXT 20 days (non-overlapping) ===")
nz = nz.assign(b=nz["cgs"].map(bucket))
fw = nz.dropna(subset=["fwd20"])
print(f"  base rate: {100 * (fw['fwd20'] > 0).mean():.1f}% up, mean {fw['fwd20'].mean():+.2f}%  (n={len(fw)})")
t = fw.groupby("b").agg(n=("fwd20", "size"), next20_mean=("fwd20", "mean"),
                        next20_up=("fwd20", lambda x: 100 * (x > 0).mean()),
                        prev20_mean=("back20", "mean"),
                        prev20_up=("back20", lambda x: 100 * (x > 0).mean()))
print(t.round(2).to_string())

# 2b. agreement table
print("\n  Leg agreement (both legs bullish >=30 / both bearish <=-30 / conflict or quiet):")
def agree(r):
    if r.ry_s >= 30 and r.usd_s >= 30: return "both bullish"
    if r.ry_s <= -30 and r.usd_s <= -30: return "both bearish"
    if (r.ry_s >= 30 and r.usd_s <= -30) or (r.ry_s <= -30 and r.usd_s >= 30): return "conflict"
    return "quiet/mixed"
fw = fw.assign(a=[agree(r) for r in fw.itertuples()])
print(fw.groupby("a").agg(n=("fwd20", "size"), next20=("fwd20", "mean"),
                          next20_up=("fwd20", lambda x: 100 * (x > 0).mean()),
                          prev20=("back20", "mean")).round(2).to_string())

# ── 3. Head-to-head with the SHIPPED Forward Signal v2 (engine dump, month-ends) ──────────
fwdv2 = pd.read_csv(f"{REPO}/research/cache/engine/engine_forward_monthly.csv", parse_dates=["date"]).set_index("date")
me_all = month_ends(build().dropna(subset=["gold"]))
me_all = me_all[me_all.index >= "2007-01-01"]
j = me_all.join(fwdv2[["forward"]], how="inner")
if len(j) < 100:  # month-end dates may differ by a day (GLD vs LBMA calendars) -> as-of match
    fwd_asof = asof(fwdv2["forward"], me_all.index)
    j = me_all.assign(forward=fwd_asof.to_numpy())
    j = j[j.index <= fwdv2.index.max() + pd.Timedelta(days=3)]
print(f"\n=== 3. Same month-ends, 2007 -> {j.index.max().date()} (n={len(j)}) : which dial predicts? ===")
print(f"{'':30s}{'next 20d':>10s}{'next 63d':>10s}   63d CI")
for col, lab in [("forward", "shipped Forward Signal v2"), ("cgs", "proposed CGS")]:
    print(f"{lab:30s}{spearman(j[col], j['fwd20']):+10.2f}{spearman(j[col], j['fwd63']):+10.2f}   {boot_ci(j[col], j['fwd63'])}")
print(f"  corr(v2, CGS) = {spearman(j['forward'], j['cgs']):+.2f}")

# spot index as nowcast, for comparison
spot = pd.read_csv(f"{REPO}/research/cache/engine/engine_spot_daily.csv", parse_dates=["date"]).set_index("date")
spot20 = (spot["composite"] - spot["composite"].shift(20))
nz2 = every(base[base.index <= spot.index.max()], 20)
nz2 = nz2.assign(spot_d20=asof(spot20, nz2.index).to_numpy(), spot_lvl=asof(spot["composite"], nz2.index).to_numpy())
print(f"\n  Nowcast quality, same-window 20d gold (n={len(nz2)}):  CGS {spearman(nz2['cgs'], nz2['back20']):+.2f}   "
      f"shipped spot index 20d change {spearman(nz2['spot_d20'], nz2['back20']):+.2f}   corr(CGS, spot Δ20) {spearman(nz2['cgs'], nz2['spot_d20']):+.2f}")

# ── 4. Real vs nominal, and regime drift of each leg (same-window Pearson, 20d non-overlap) ──
print("\n=== 4. Same-window correlation of 20d gold return with each driver's 20d change, by era ===")
chg = pd.DataFrame(index=gi)
for name, s, kind in [("DFII10 real", ry, "diff"), ("DGS10 nominal", dgs10, "diff"), ("T10YIE breakeven", t10yie, "diff"),
                      ("broad USD %", usd, "pct"), ("DXY %", dxy, "pct")]:
    c = s - s.shift(20) if kind == "diff" else (s / s.shift(20) - 1) * 100
    av = h10_available if name.startswith("broad") else None
    chg[name] = asof(c, gi, av)
chg["back20"] = base["back20"].reindex(gi)
cz = every(chg[chg.index >= "2007-01-01"], 20)
hdr = "".join(f"{c:>18s}" for c in chg.columns[:-1])
print(f"{'':14s}{hdr}")
for lo, hi in ERAS + [("2007", "2026")]:
    a = sub(cz, lo, hi)
    print(f"  {lo}-{hi}  " + "".join(f"{pearson(a[c], a['back20']):+18.2f}" for c in chg.columns[:-1]))
print("  (negative = the textbook direction: driver up, gold down)")

print("\n  By year, same-window Pearson (20d non-overlap): real-yield / broad-USD")
for y in range(2019, 2027):
    a = cz[cz.index.year == y]
    print(f"   {y}: n={len(a):2d}  RY {pearson(a['DFII10 real'], a['back20']):+.2f}   USD {pearson(a['broad USD %'], a['back20']):+.2f}")

# ── 5. Sensitivity: horizon, weights, dollar series, mapping ─────────────────────────────
print("\n=== 5. Sensitivity (Spearman; same = same-window 20d; next = next-window) ===")
print(f"{'variant':34s}{'same h':>8s}{'next h':>8s}{'next63(mo)':>11s}")
for label, kw, h in [("h=10", dict(h=10), 10), ("h=20 (proposal)", dict(h=20), 20), ("h=40", dict(h=40), 40),
                     ("h=63", dict(h=63), 63), ("60/40 RY/USD", dict(w_ry=0.6), 20), ("40/60 RY/USD", dict(w_ry=0.4), 20),
                     ("DXY instead of broad (to 2026-07)", dict(dollar="dxy"), 20), ("tanh map", dict(mapping="tanh"), 20),
                     ("broad USD, no pub lag (look-ahead)", dict(lag_usd=False), 20)]:
    d = build(**kw)
    d = d[d.index >= "2007-01-01"].dropna(subset=["cgs"])
    g = d["gold"].to_numpy(); n = len(g)
    back = np.full(n, np.nan); back[h:] = (g[h:] / g[:-h] - 1) * 100
    fwd = np.full(n, np.nan); fwd[: n - 1 - h] = (g[1 + h:] / g[1: n - h] - 1) * 100
    d = d.assign(bh=back, fh=fwd)
    s = every(d, h); m = month_ends(d)
    print(f"{label:34s}{spearman(s['cgs'], s['bh']):+8.2f}{spearman(s['cgs'], s['fh']):+8.2f}{spearman(m['cgs'], m['fwd63']):+11.2f}")

# ── 6. Data-driven "confidence": does a decoupled regime persist? ────────────────────────
print("\n=== 6. Measured confidence: trailing fit of the 2-factor model -> next 6 months' fit ===")
dd = build()
dd = dd[dd.index >= "2007-01-01"].dropna(subset=["ry_ch", "usd_ch", "back20"])
nzr = every(dd, 20).copy()
X = np.column_stack([np.ones(len(nzr)), nzr["ry_ch"], nzr["usd_ch"]]); y = nzr["back20"].to_numpy()
r2_trail, r2_next = [], []
W = 13  # ~1y of non-overlapping 20d windows
for i in range(len(nzr)):
    if i < W or i + 7 > len(nzr):
        r2_trail.append(np.nan); r2_next.append(np.nan); continue
    def r2(lo, hi):
        Xs, ys = X[lo:hi], y[lo:hi]
        beta, *_ = np.linalg.lstsq(Xs, ys, rcond=None)
        res = ys - Xs @ beta
        return 1 - res.var() / ys.var()
    r2_trail.append(r2(i - W, i))
    # next ~6m: fit using the TRAILING betas (point-in-time), measure explained share
    beta, *_ = np.linalg.lstsq(X[i - W:i], y[i - W:i], rcond=None)
    ys = y[i:i + 7]; res = ys - X[i:i + 7] @ beta
    r2_next.append(1 - (res ** 2).mean() / ((ys - ys.mean()) ** 2).mean())
nzr["r2_trail"] = r2_trail; nzr["r2_next"] = r2_next
ok = nzr.dropna(subset=["r2_trail", "r2_next"])
ok = ok.assign(t=pd.qcut(ok["r2_trail"], 3, labels=["low trailing fit", "mid", "high trailing fit"]))
print(ok.groupby("t", observed=True).agg(n=("r2_next", "size"), trailing_R2=("r2_trail", "median"),
                                           next6m_R2_out_of_sample=("r2_next", "median")).round(2).to_string())
print("  trailing 1y R^2 by year-end:")
for y_ in range(2008, 2027):
    a = nzr[nzr.index.year == y_]["r2_trail"].dropna()
    if len(a): print(f"   {y_}: {a.iloc[-1]:.2f}", end="")
print()

# ── 7. Right now ─────────────────────────────────────────────────────────────────────────
print("\n=== 7. Current reading ===")
print("  proposal quoted: DFII10 2.41 (Aug 14) -> 2.60 (Sep 14); DTWEXBGS 118.90 (Aug 14) -> 118.21 (Sep 11)")
for dte in ["2026-08-14", "2026-09-14", "2026-09-15"]:
    print(f"  DFII10 {dte}: {ry.get(pd.Timestamp(dte), np.nan)}", end="")
print()
for dte in ["2026-08-14", "2026-09-11"]:
    print(f"  DTWEXBGS {dte}: {usd.get(pd.Timestamp(dte), np.nan):.2f}", end="")
print()
Lr = leg(ry, "diff"); Lu = leg(usd, "pct")
print(f"  RY  last={ry.index[-1].date()} {ry.iloc[-1]:.2f}%  20-obs Δ {Lr['ch'].iloc[-1]:+.2f}pp  z {Lr['z'].iloc[-1]:+.2f}  score {Lr['score'].iloc[-1]:+.0f}")
print(f"  USD last={usd.index[-1].date()} {usd.iloc[-1]:.2f}  20-obs Δ {Lu['ch'].iloc[-1]:+.2f}%  z {Lu['z'].iloc[-1]:+.2f}  score {Lu['score'].iloc[-1]:+.0f}")
cg = 0.5 * Lr['score'].iloc[-1] + 0.5 * Lu['score'].iloc[-1]
print(f"  CGS now = {cg:+.0f}  -> {bucket(cg)}")
g20 = gold.iloc[-1] / gold.iloc[-21] - 1
print(f"  gold LBMA {gold.index[-1].date()} ${gold.iloc[-1]:,.2f}; last 20 fixes {100 * g20:+.1f}%;  ATH ${gold.max():,.0f} on {gold.idxmax().date()}")

# v2 recomputed now (engine math, LBMA as the price series)
RY_REG = [(-1.0, 12), (-0.5, 22), (0.0, 32), (0.5, 42), (1.0, 52), (1.5, 62), (2.0, 74), (2.5, 86), (3.0, 92)]
TREND = [(-25.0, 15), (-10.0, 30), (0.0, 45), (10.0, 58), (20.0, 70), (35.0, 82), (50.0, 90)]
FED = [(-2.0, 90), (-1.0, 75), (-0.25, 60), (0.25, 50), (1.0, 35), (2.0, 15)]
def pw(x, a):
    if x <= a[0][0]: return a[0][1]
    if x >= a[-1][0]: return a[-1][1]
    for (x0, y0), (x1, y1) in zip(a, a[1:]):
        if x0 <= x <= x1: return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
ryv = ry[ry.index >= ry.index[-1] - pd.DateOffset(years=6)].to_numpy()
win = ryv[-1260:]; cur = ryv[-1]
pct = min(max((win < cur).sum() / len(win), 0.05), 0.95)
rr = 0.5 * pw(cur, RY_REG) + 0.5 * pct * 100
roc = (gold.iloc[-1] / gold.iloc[-253] - 1) * 100
d2 = dgs2.to_numpy(); fed = pw(d2[-1] - d2[-63], FED)
v2 = 0.55 * rr + 0.25 * pw(roc, TREND) + 0.20 * fed
print(f"  Forward Signal v2 now ≈ {v2:.1f}  (RR {rr:.1f} [DFII10 {cur:.2f}%, {pct * 100:.0f}th 5y pct] · Trend {pw(roc, TREND):.1f} [ROC252 {roc:+.1f}%] · Fed {fed:.1f} [DGS2 Δ63 {d2[-1] - d2[-63]:+.2f}])")
