#!/usr/bin/env python3
"""Python replica of GoldIndexEngine (spot composite + forward signal), mirrored from
shared/src/commonMain/kotlin/com/sun/aurum/domain/gold/GoldIndexEngine.kt.

Every function mirrors the Kotlin 1:1. The replica is validated against the engine's own
output (research/cache/engine/*.csv, produced by EngineHistoryDumpTest) before any variant
work — run validate() or `python3 engine_replica.py`.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
INPUTS = ROOT / "cache" / "inputs"
ENGINE = ROOT / "cache" / "engine"
REPO = ROOT.parent

W_REAL_YIELD, W_USD, W_CENTRAL, W_INFLATION, W_TECHNICAL = 0.30, 0.23, 0.22, 0.13, 0.12

# ── Bundled annual WGC series (engine cbByYear) ──────────────────────────────
CB_BY_YEAR = [
    (2009, -34.0), (2010, 79.0), (2011, 481.0), (2012, 544.0), (2013, 409.0),
    (2014, 584.0), (2015, 580.0), (2016, 395.0), (2017, 379.0), (2018, 656.0),
    (2019, 605.0), (2020, 255.0), (2021, 463.0), (2022, 1082.0), (2023, 1037.0),
    (2024, 1045.0), (2025, 1000.0), (2026, 1000.0),
]

CB_ANCHORS = [(-100.0, 10.0), (0.0, 28.0), (300.0, 45.0), (500.0, 55.0),
              (800.0, 72.0), (1100.0, 90.0), (1300.0, 95.0)]

DXY_ANCHORS = [(84.0, 92.0), (89.0, 82.0), (93.0, 70.0), (97.0, 58.0), (100.0, 50.0),
               (103.0, 42.0), (107.0, 32.0), (113.0, 18.0), (120.0, 8.0)]


def piecewise(x: float, anchors: list[tuple[float, float]]) -> float:
    if x <= anchors[0][0]:
        return anchors[0][1]
    if x >= anchors[-1][0]:
        return anchors[-1][1]
    for (x0, y0), (x1, y1) in zip(anchors, anchors[1:]):
        if x0 <= x <= x1:
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return 50.0


# ── CB (point-in-time, publication-lagged) ───────────────────────────────────

def cb_effective_year(year: int, month: int) -> int:
    return year - 1 if month >= 4 else year - 2


def cb_tonnes_effective(year: int, month: int) -> float:
    eff = cb_effective_year(year, month)
    known = [t for (y, t) in CB_BY_YEAR if y <= eff]
    return known[-1] if known else CB_BY_YEAR[0][1]


def cb_tonnes_from_quarterly(quarterly: list[tuple[int, int, float]], year: int, month: int) -> float | None:
    if not quarterly:
        return None
    published = []
    for (qy, qq, t) in quarterly:
        pub_y, pub_m = qy, qq * 3 + 2
        if pub_m > 12:
            pub_m -= 12
            pub_y += 1
        if pub_y < year or (pub_y == year and pub_m <= month):
            published.append((qy, qq, t))
    if len(published) < 4:
        return None
    published.sort()
    return sum(t for (_, _, t) in published[-4:])


def cb_score_at(year: int, month: int, quarterly: list[tuple[int, int, float]]) -> float:
    tonnes = cb_tonnes_from_quarterly(quarterly, year, month)
    if tonnes is None:
        tonnes = cb_tonnes_effective(year, month)
    return piecewise(tonnes, CB_ANCHORS)


# ── Rolling percentile scorers (invertedPct / directPct, clamped 5..95) ──────

def rolling_pct_count(values: np.ndarray, window: int, mode: str) -> np.ndarray:
    """Engine semantics at index i: over the last min(i+1, window) values INCLUDING i,
    fraction strictly greater (inverted) / strictly less (direct) than values[i]."""
    n = len(values)
    out = np.full(n, np.nan)
    for i in range(n):
        lo = max(0, i + 1 - window)
        w = values[lo:i + 1]
        if len(w) < 5:
            continue
        if mode == "inverted":
            frac = np.count_nonzero(w > values[i]) / len(w)
        else:
            frac = np.count_nonzero(w < values[i]) / len(w)
        out[i] = np.clip(frac, 0.05, 0.95) * 100.0
    return out


# ── Technical (spot component) ───────────────────────────────────────────────

def technical_series(closes: pd.Series) -> pd.Series:
    c = closes.to_numpy()
    n = len(c)
    sma20 = closes.rolling(20).mean().to_numpy()
    sma50 = closes.rolling(50).mean().to_numpy()
    sma200 = closes.rolling(200).mean().to_numpy()
    # RSI(14), simple averages over the last 14 diffs (engine computeRsi)
    diffs = np.diff(c, prepend=np.nan)
    gains = np.where(diffs > 0, diffs, 0.0)
    losses = np.where(diffs < 0, -diffs, 0.0)
    ag = pd.Series(gains).rolling(14).sum().to_numpy() / 14.0
    al = pd.Series(losses).rolling(14).sum().to_numpy() / 14.0
    roc = (closes / closes.shift(20) - 1.0).to_numpy() * 100.0

    out = np.full(n, np.nan)
    for i in range(n):
        if i + 1 < 20:
            continue
        trend = 0.0
        if c[i] > sma20[i]:
            trend += 15.0
        has50 = i + 1 >= 50
        has200 = i + 1 >= 200
        if has50 and sma20[i] > sma50[i]:
            trend += 15.0
        if has200 and (sma50[i] if has50 else sma20[i]) > sma200[i]:
            trend += 10.0
        # engine: RSI defaults to 50 when closes.size <= period (never here, i+1 >= 20 > 15)
        rsi = 100.0 if al[i] == 0.0 else 100.0 - 100.0 / (1.0 + ag[i] / al[i])
        if rsi < 30: rsi_s = 28.0
        elif rsi < 40: rsi_s = 25.0
        elif rsi < 50: rsi_s = 18.0
        elif rsi < 60: rsi_s = 22.0
        elif rsi < 70: rsi_s = 26.0
        elif rsi < 80: rsi_s = 18.0
        else: rsi_s = 10.0
        r = roc[i] if i >= 20 else 0.0
        if r > 5.0: roc_s = 30.0
        elif r > 2.0: roc_s = 25.0
        elif r > 0.0: roc_s = 20.0
        elif r > -2.0: roc_s = 15.0
        elif r > -5.0: roc_s = 8.0
        else: roc_s = 0.0
        out[i] = min(100.0, max(0.0, trend + rsi_s + roc_s))
    return pd.Series(out, index=closes.index)


# ── Forward-signal step maps ─────────────────────────────────────────────────

def f_ry_score(delta: float) -> float:
    if delta < -0.50: return 90.0
    if delta < -0.25: return 78.0
    if delta < -0.10: return 65.0
    if delta < 0.10: return 50.0
    if delta < 0.25: return 35.0
    if delta < 0.50: return 22.0
    return 10.0


def f_usd_score(delta_pct: float) -> float:
    if delta_pct < -3.0: return 90.0
    if delta_pct < -1.5: return 77.0
    if delta_pct < -0.5: return 63.0
    if delta_pct < 0.5: return 50.0
    if delta_pct < 1.5: return 37.0
    if delta_pct < 3.0: return 23.0
    return 10.0


def f_inf_score(delta: float, ry_delta: float) -> float:
    if delta > 0.30: base = 85.0
    elif delta > 0.15: base = 72.0
    elif delta > 0.05: base = 60.0
    elif delta > -0.05: base = 50.0
    elif delta > -0.15: base = 38.0
    elif delta > -0.30: base = 25.0
    else: base = 12.0
    if ry_delta > 0.20 and delta > 0.10:
        return max(base * 0.6, 30.0)
    return base


def f_tech_score(roc60: float) -> float:
    if roc60 > 8.0: return 85.0
    if roc60 > 5.0: return 75.0
    if roc60 > 2.0: return 63.0
    if roc60 > 0.0: return 53.0
    if roc60 > -2.0: return 43.0
    if roc60 > -5.0: return 30.0
    return 18.0


# ── Loading & assembly ───────────────────────────────────────────────────────

def load_inputs() -> dict:
    gld = pd.read_csv(INPUTS / "gld.csv")
    dxy = pd.read_csv(INPUTS / "dxy.csv")
    ry = pd.read_csv(INPUTS / "dfii10.csv")
    inf = pd.read_csv(INPUTS / "t10yie.csv")
    cbj = json.loads((REPO / "data" / "cb_quarterly.json").read_text())
    quarterly = sorted(
        (int(k[:4]), int(k[-1]), float(v)) for k, v in cbj["quarterly"].items()
    )
    return {"gld": gld, "dxy": dxy, "ry": ry, "inf": inf, "cbq": quarterly}


def spot_series(d: dict) -> pd.DataFrame:
    """Daily point-in-time spot index on the GLD date spine — mirrors computeHistoricalFull."""
    gld, dxy, ry, inf, cbq = d["gld"], d["dxy"], d["ry"], d["inf"], d["cbq"]
    gdates = gld["date"].to_numpy()

    # RY / INF percentile scores on their own spines, then as-of joined to GLD dates
    ry_score = pd.Series(rolling_pct_count(ry["value"].to_numpy(), 252, "inverted"), index=ry["date"])
    inf_score = pd.Series(rolling_pct_count(inf["value"].to_numpy(), 252, "direct"), index=inf["date"])

    # USD score on the DXY spine: 0.65 * level anchor + 0.35 * inverted 504d percentile
    dxy_close = dxy["close"].to_numpy()
    dxy_pct = rolling_pct_count(dxy_close, 504, "inverted")
    dxy_level = np.array([piecewise(x, DXY_ANCHORS) for x in dxy_close])
    usd_score = pd.Series(0.65 * dxy_level + 0.35 * dxy_pct, index=dxy["date"])

    tech = technical_series(gld["close"])

    def asof(series: pd.Series, dates: np.ndarray) -> np.ndarray:
        # last value whose date <= d (engine SortedDateSeries.window semantics)
        idx = np.searchsorted(series.index.to_numpy(), dates, side="right") - 1
        vals = series.to_numpy()
        out = np.full(len(dates), np.nan)
        ok = idx >= 0
        out[ok] = vals[idx[ok]]
        return out

    rows = pd.DataFrame({
        "date": gdates,
        "ry": asof(ry_score, gdates),
        "usd": asof(usd_score, gdates),
        "inf": asof(inf_score, gdates),
        "tech": tech.to_numpy(),
        "close": gld["close"].to_numpy(),
    })
    ym = pd.to_datetime(rows["date"])
    cb_cache: dict[tuple[int, int], float] = {}
    rows["cb"] = [
        cb_cache.setdefault((y, m), cb_score_at(y, m, cbq))
        for y, m in zip(ym.dt.year, ym.dt.month)
    ]

    comps = [("ry", W_REAL_YIELD), ("usd", W_USD), ("cb", W_CENTRAL), ("inf", W_INFLATION), ("tech", W_TECHNICAL)]
    num = np.zeros(len(rows))
    den = np.zeros(len(rows))
    for col, w in comps:
        v = rows[col].to_numpy()
        ok = ~np.isnan(v)
        num[ok] += v[ok] * w
        den[ok] += w
    rows["composite"] = np.where(den > 0, num / np.maximum(den, 1e-12), np.nan)
    return rows.iloc[50:].reset_index(drop=True)  # engine startIdx = 50


RY_REGIME_ANCHORS = [(-1.0, 12.0), (-0.5, 22.0), (0.0, 32.0), (0.5, 42.0), (1.0, 52.0),
                     (1.5, 62.0), (2.0, 74.0), (2.5, 86.0), (3.0, 92.0)]
TREND_ANCHORS = [(-25.0, 15.0), (-10.0, 30.0), (0.0, 45.0), (10.0, 58.0),
                 (20.0, 70.0), (35.0, 82.0), (50.0, 90.0)]
FED_ANCHORS = [(-2.0, 90.0), (-1.0, 75.0), (-0.25, 60.0), (0.25, 50.0), (1.0, 35.0), (2.0, 15.0)]


def forward_series_v2(d: dict) -> pd.DataFrame:
    """Daily Forward Signal v2 on the GLD date spine — mirrors computeForwardSignal (2026-07):
    0.55 Real-Rate Regime (0.5 fixed anchors + 0.5 rolling 5y pct of DFII10, pct half needs
    >=504 obs) + 0.25 12M Trend (ROC252 anchors) + 0.20 Fed Cycle (DGS2 63-obs delta anchors),
    availability-renormalized."""
    gld, ry = d["gld"], d["ry"]
    dgs2 = pd.read_csv(INPUTS / "dgs2.csv")
    gdates = gld["date"].to_numpy()

    ry_v = ry["value"].to_numpy()
    n_ry = len(ry_v)
    ry_score = np.full(n_ry, np.nan)
    for i in range(n_ry):
        fixed = piecewise(ry_v[i], RY_REGIME_ANCHORS)
        lo = max(0, i + 1 - 1260)
        w = ry_v[lo:i + 1]
        if len(w) >= 504:
            pct = np.clip((w < ry_v[i]).sum() / len(w), 0.05, 0.95) * 100.0
            ry_score[i] = 0.5 * fixed + 0.5 * pct
        else:
            ry_score[i] = fixed
    ry_ser = pd.Series(ry_score, index=ry["date"])
    ry_n = pd.Series(np.arange(1, n_ry + 1), index=ry["date"])

    d2_v = dgs2["value"]
    d2_delta = (d2_v - d2_v.shift(62)).to_numpy()
    # engine: delta = last - vals[max(0, size-63)] -> for size < 63, first obs
    for i in range(min(62, len(d2_v))):
        d2_delta[i] = d2_v.iloc[i] - d2_v.iloc[0]
    fed_ser = pd.Series([piecewise(x, FED_ANCHORS) for x in d2_delta], index=dgs2["date"])
    fed_n = pd.Series(np.arange(1, len(dgs2) + 1), index=dgs2["date"])

    roc252 = (gld["close"] / gld["close"].shift(252) - 1.0) * 100.0
    trend = pd.Series([piecewise(x, TREND_ANCHORS) if not np.isnan(x) else np.nan for x in roc252],
                      index=np.arange(len(gld)))

    def asof(series: pd.Series, dates: np.ndarray) -> np.ndarray:
        idx = np.searchsorted(series.index.to_numpy(), dates, side="right") - 1
        vals = series.to_numpy()
        out = np.full(len(dates), np.nan)
        ok = idx >= 0
        out[ok] = vals[idx[ok]]
        return out

    df = pd.DataFrame({
        "date": gdates,
        "f_realrate": np.where(asof(ry_n, gdates) >= 5, asof(ry_ser, gdates), np.nan),
        "f_trend": trend.to_numpy(),
        "f_fed": np.where(asof(fed_n, gdates) >= 60, asof(fed_ser, gdates), np.nan),
    })
    comps = [("f_realrate", 0.55), ("f_trend", 0.25), ("f_fed", 0.20)]
    num = np.zeros(len(df))
    den = np.zeros(len(df))
    for col, w in comps:
        v = df[col].to_numpy()
        ok = ~np.isnan(v)
        num[ok] += v[ok] * w
        den[ok] += w
    df["forward"] = np.where(den > 0, num / np.maximum(den, 1e-12), np.nan)
    return df


def forward_series_v1(d: dict) -> pd.DataFrame:
    """Daily forward signal v1 (AS SHIPPED BEFORE 2026-07, kept for the baseline evaluation) —
    mirrors the old computeForwardSignal. Deltas are computed on each series' own spine
    (last obs minus 63rd-from-last), then as-of joined to GLD dates."""
    gld, dxy, ry, inf = d["gld"], d["dxy"], d["ry"], d["inf"]
    gdates = gld["date"].to_numpy()

    ry_v = ry["value"]
    ry_delta = pd.Series((ry_v - ry_v.shift(62)).to_numpy(), index=ry["date"])
    ry_n = pd.Series(np.arange(1, len(ry) + 1), index=ry["date"])  # obs count as of date

    inf_v = inf["value"]
    inf_delta = pd.Series((inf_v - inf_v.shift(62)).to_numpy(), index=inf["date"])
    inf_n = pd.Series(np.arange(1, len(inf) + 1), index=inf["date"])

    dxy_c = dxy["close"]
    usd_delta = pd.Series(((dxy_c / dxy_c.shift(62) - 1.0) * 100.0).to_numpy(), index=dxy["date"])
    dxy_n = pd.Series(np.arange(1, len(dxy) + 1), index=dxy["date"])

    roc60 = (gld["close"] / gld["close"].shift(60) - 1.0) * 100.0

    def asof(series: pd.Series, dates: np.ndarray) -> np.ndarray:
        idx = np.searchsorted(series.index.to_numpy(), dates, side="right") - 1
        vals = series.to_numpy()
        out = np.full(len(dates), np.nan)
        ok = idx >= 0
        out[ok] = vals[idx[ok]]
        return out

    df = pd.DataFrame({
        "date": gdates,
        "ry_delta": asof(ry_delta, gdates),
        "ry_n": asof(ry_n, gdates),
        "usd_delta": asof(usd_delta, gdates),
        "dxy_n": asof(dxy_n, gdates),
        "inf_delta": asof(inf_delta, gdates),
        "inf_n": asof(inf_n, gdates),
        "roc60": roc60.to_numpy(),
        "gld_n": np.arange(1, len(gld) + 1),
    })

    f_ry = np.full(len(df), np.nan)
    f_usd = np.full(len(df), np.nan)
    f_inf = np.full(len(df), np.nan)
    f_tech = np.full(len(df), np.nan)
    for i, r in enumerate(df.itertuples(index=False)):
        if r.ry_n >= 60 and not np.isnan(r.ry_delta):
            f_ry[i] = f_ry_score(r.ry_delta)
        if r.dxy_n >= 60 and not np.isnan(r.usd_delta):
            f_usd[i] = f_usd_score(r.usd_delta)
        if r.inf_n >= 60 and not np.isnan(r.inf_delta):
            ryd = r.ry_delta if (r.ry_n >= 63 and not np.isnan(r.ry_delta)) else 0.0
            f_inf[i] = f_inf_score(r.inf_delta, ryd)
        if r.gld_n >= 60 and not np.isnan(r.roc60):
            f_tech[i] = f_tech_score(r.roc60)
    df["f_ry"], df["f_usd"], df["f_inf"], df["f_tech"] = f_ry, f_usd, f_inf, f_tech

    comps = [("f_ry", 0.40), ("f_usd", 0.30), ("f_inf", 0.20), ("f_tech", 0.10)]
    num = np.zeros(len(df))
    den = np.zeros(len(df))
    for col, w in comps:
        v = df[col].to_numpy()
        ok = ~np.isnan(v)
        num[ok] += v[ok] * w
        den[ok] += w
    df["forward"] = np.where(den > 0, num / np.maximum(den, 1e-12), np.nan)
    return df


# ── Validation against the engine dump ───────────────────────────────────────

def validate() -> None:
    d = load_inputs()

    # CB unit-test fixtures from GoldIndexEngineTest must reproduce exactly
    cb_q_test = [(2024, 1, 290.0), (2024, 2, 184.0), (2024, 3, 186.0),
                 (2024, 4, 333.0), (2025, 1, 244.0), (2025, 2, 166.0)]
    assert cb_tonnes_from_quarterly(cb_q_test, 2025, 4) == 290.0 + 184 + 186 + 333
    assert cb_tonnes_from_quarterly(cb_q_test, 2025, 6) == 184.0 + 186 + 333 + 244
    assert cb_tonnes_from_quarterly(cb_q_test, 2025, 8) == 186.0 + 333 + 244 + 166
    assert cb_tonnes_from_quarterly(cb_q_test, 2024, 1) is None
    assert cb_tonnes_effective(2025, 6) == 1045.0
    assert cb_tonnes_effective(2025, 2) == 1037.0
    print("CB fixtures: OK")

    spot = spot_series(d)
    eng = pd.read_csv(ENGINE / "engine_spot_daily.csv")
    m = pd.merge(eng, spot, on="date", suffixes=("_e", "_p"))
    assert len(m) == len(eng), f"row mismatch: {len(m)} vs {len(eng)}"
    print(f"spot rows compared: {len(m)}")
    for col in ["composite", "ry", "usd", "cb", "inf", "tech"]:
        diff = (m[f"{col}_e"] - m[f"{col}_p"]).abs()
        print(f"  spot {col:9s} max|diff| = {diff.max():.6f}")
        assert diff.max() < 0.01, f"spot {col} drifted: {diff.max()}"

    fwd = forward_series_v2(d)
    engf = pd.read_csv(ENGINE / "engine_forward_monthly.csv")
    mf = pd.merge(engf, fwd, on="date", suffixes=("_e", "_p"))
    assert len(mf) == len(engf), f"forward row mismatch: {len(mf)} vs {len(engf)}"
    print(f"forward month-ends compared: {len(mf)}")
    for ec, pc, avail in [("forward_e", "forward_p", None), ("fRealRate", "f_realrate", "fRealRateAvail"),
                          ("fTrend", "f_trend", "fTrendAvail"), ("fFed", "f_fed", "fFedAvail")]:
        rows = mf if avail is None else mf[mf[avail]]
        diff = (rows[ec].astype(float) - rows[pc].astype(float)).abs()
        print(f"  fwd  {ec:10s} max|diff| = {diff.max():.6f}  (n={len(rows)})")
        assert diff.max() < 0.01, f"forward {ec} drifted: {diff.max()}"

    print("REPLICA VALIDATION: PASS")


if __name__ == "__main__":
    validate()
