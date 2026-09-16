#!/usr/bin/env python3
"""20-Day Drivers -- replica of the exact in-app spec, on the app's own inputs.

Inputs: research/cache/inputs gld.csv + dxy.csv (Yahoo, the app's sources) + dfii10.csv (FRED).
Legs are computed on each series' OWN spine, then as-of joined to GLD dates (what the engine does):
  change_i = x_i - x_{i-20}  (DFII10, pct pts)   |   (x_i / x_{i-20} - 1) * 100  (DXY, %)
  sd_i     = sample std of change_j, j in [max(20, i-251), i], needs >= 126 values
  score_i  = -100 * clamp(change_i / sd_i / 2, -1, 1)
  drivers  = mean of available leg scores (50/50)
Also fits the attribution betas: gold 20d % ~ a + b_ry * dDFII10 + b_usd * dDXY%, non-overlapping.
"""
import sys

import numpy as np
import pandas as pd

from pathlib import Path

REPO = sys.argv[1] if len(sys.argv) > 1 else str(Path(__file__).resolve().parents[3])
INP = f"{REPO}/research/cache/inputs"
W, VOLW, MINV = 20, 252, 126


def own_spine_leg(dates, vals, pct):
    v = np.asarray(vals, float)
    n = len(v)
    ch = np.full(n, np.nan)
    ch[W:] = (v[W:] / v[:-W] - 1) * 100 if pct else v[W:] - v[:-W]
    sd = np.full(n, np.nan)
    for i in range(W, n):
        lo = max(W, i - VOLW + 1)
        win = ch[lo:i + 1]
        if len(win) >= MINV:
            sd[i] = win.std(ddof=1)
    z = ch / sd
    score = -100 * np.clip(z / 2, -1, 1)
    return pd.DataFrame({"date": dates, "ch": ch, "sd": sd, "score": score})


def asof(leg, dates):
    keys = leg["date"].to_numpy()
    pos = np.searchsorted(keys, dates, side="right") - 1
    out = {}
    for c in ["ch", "score"]:
        vals = leg[c].to_numpy()
        out[c] = np.where(pos >= 0, vals[np.clip(pos, 0, None)], np.nan)
    return out


gld = pd.read_csv(f"{INP}/gld.csv")
dxy = pd.read_csv(f"{INP}/dxy.csv")
ry = pd.read_csv(f"{INP}/dfii10.csv")
# the app keys candles by their America/New_York date; the cached CSV 'date' column is already that
L_ry = own_spine_leg(ry["date"].to_numpy(), ry["value"], pct=False)
L_usd = own_spine_leg(dxy["date"].to_numpy(), dxy["close"], pct=True)

gd = gld["date"].to_numpy()
a_ry, a_usd = asof(L_ry, gd), asof(L_usd, gd)
df = pd.DataFrame({"date": gd, "close": gld["close"].to_numpy(),
                   "ry_ch": a_ry["ch"], "ry_s": a_ry["score"], "usd_ch": a_usd["ch"], "usd_s": a_usd["score"]})
legs = df[["ry_s", "usd_s"]]
df["drivers"] = legs.mean(axis=1, skipna=True)          # availability-renormalized 50/50
c = df["close"].to_numpy(); n = len(c)
df["gold20"] = np.nan; df.loc[W:, "gold20"] = (c[W:] / c[:-W] - 1) * 100
df["fwd20"] = np.nan; df.loc[:n - 1 - W - 1, "fwd20"] = (c[1 + W:] / c[1:n - W] - 1) * 100
df["fwd63"] = np.nan; df.loc[:n - 1 - 63 - 1, "fwd63"] = (c[1 + 63:] / c[1:n - 63] - 1) * 100
df.to_csv(f"{REPO}/research/cache/engine/replica_drivers_daily.csv", index=False,
          columns=["date", "ry_ch", "ry_s", "usd_ch", "usd_s", "drivers"])

d = df[(df["date"] >= "2007-01-01")].dropna(subset=["ry_s", "usd_s"]).reset_index(drop=True)
nz = d.iloc[::20].dropna(subset=["gold20"])


def sp(a, b):
    m = ~(np.isnan(a) | np.isnan(b))
    return pd.Series(a[m]).rank().corr(pd.Series(b[m]).rank())


print(f"app-spec sample {d['date'].iloc[0]} -> {d['date'].iloc[-1]}, non-overlapping n={len(nz)}")
print(f"  same-window Spearman  {sp(nz['drivers'].to_numpy(), nz['gold20'].to_numpy()):+.3f}")
print(f"  next 20d              {sp(nz['drivers'].to_numpy(), nz['fwd20'].to_numpy()):+.3f}")
me = d.groupby(d['date'].str[:7]).tail(1)
print(f"  next 63d (month-ends) {sp(me['drivers'].to_numpy(), me['fwd63'].to_numpy()):+.3f}")
for lo, hi in [("2007", "2012"), ("2013", "2018"), ("2019", "2021"), ("2022", "2026")]:
    a = nz[(nz['date'] >= lo) & (nz['date'] <= hi + "-12-31")]
    print(f"   {lo}-{hi} same {sp(a['drivers'].to_numpy(), a['gold20'].to_numpy()):+.2f} next {sp(a['drivers'].to_numpy(), a['fwd20'].to_numpy()):+.2f}")


def ols(frame):
    X = np.column_stack([np.ones(len(frame)), frame["ry_ch"], frame["usd_ch"]])
    y = frame["gold20"].to_numpy()
    b, *_ = np.linalg.lstsq(X, y, rcond=None)
    res = y - X @ b
    return b, 1 - res.var() / y.var()


b, r2 = ols(nz)
print(f"\nattribution OLS, non-overlapping 20d, 2007-2026: drift {b[0]:+.2f}%  b_ry {b[1]:+.2f}%/pp  b_usd {b[2]:+.2f}%/1%  R2 {r2:.2f}")
bd, r2d = ols(d.dropna(subset=["gold20"]))
print(f"  same, daily overlapping:                        drift {bd[0]:+.2f}%  b_ry {bd[1]:+.2f}%/pp  b_usd {bd[2]:+.2f}%/1%  R2 {r2d:.2f}")
for off in (5, 10, 15):
    bo, r2o = ols(d.iloc[off::20].dropna(subset=["gold20"]))
    print(f"  phase offset {off:2d}:                                drift {bo[0]:+.2f}%  b_ry {bo[1]:+.2f}  b_usd {bo[2]:+.2f}  R2 {r2o:.2f}")
for lo, hi in [("2007", "2012"), ("2013", "2018"), ("2019", "2021"), ("2022", "2026")]:
    be, r2e = ols(nz[(nz['date'] >= lo) & (nz['date'] <= hi + "-12-31")])
    print(f"   {lo}-{hi}: b_ry {be[1]:+.2f}  b_usd {be[2]:+.2f}  R2 {r2e:.2f}")
# the leg z-scores are what the headline uses; attribution uses raw changes -> check they agree in sign
BR, BU = round(bd[1], 1), round(bd[2], 1)
d2 = d.dropna(subset=["gold20"]).copy()
d2["macro"] = BR * d2["ry_ch"] + BU * d2["usd_ch"]
agree = (np.sign(d2["macro"]) == np.sign(d2["drivers"])) | (d2["drivers"].abs() < 30)
print(f"\nwith b_ry {BR}, b_usd {BU}: macro-part sign agrees with headline (or headline MIXED) on {100 * agree.mean():.1f}% of days")
print(f"  corr(macro part, gold20) {np.corrcoef(d2['macro'], d2['gold20'])[0, 1]:+.3f}; median |other| {np.median(np.abs(d2['gold20'] - d2['macro'])):.2f}%  median |gold20| {np.median(np.abs(d2['gold20'])):.2f}%")
last = df.dropna(subset=["drivers"]).iloc[-1]
print(f"\nlast row {last['date']}: ry_ch {last['ry_ch']:+.3f} ry_s {last['ry_s']:+.1f} usd_ch {last['usd_ch']:+.3f} usd_s {last['usd_s']:+.1f} drivers {last['drivers']:+.1f} gold20 {last['gold20']:+.2f}")
