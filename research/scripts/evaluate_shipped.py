#!/usr/bin/env python3
"""Evaluate the AS-SHIPPED Gold Index (spot composite) and Forward Signal against real
forward gold returns, 2005-2026. Monthly (month-end) observations to limit the
overlapping-window inflation; block-bootstrap CIs for the headline numbers.

Nothing here fits anything — this is pure measurement of the shipped engine.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import engine_replica as er

RNG = np.random.default_rng(42)


def spearman(x: pd.Series, y: pd.Series) -> float:
    m = pd.concat([x, y], axis=1).dropna()
    if len(m) < 8:
        return np.nan
    rx = m.iloc[:, 0].rank()
    ry = m.iloc[:, 1].rank()
    return float(np.corrcoef(rx, ry)[0, 1])


def block_bootstrap_ci(x: pd.Series, y: pd.Series, block: int = 6, b: int = 2000) -> tuple[float, float]:
    m = pd.concat([x, y], axis=1).dropna().reset_index(drop=True)
    n = len(m)
    if n < 24:
        return (np.nan, np.nan)
    stats = []
    n_blocks = int(np.ceil(n / block))
    for _ in range(b):
        starts = RNG.integers(0, n - block + 1, size=n_blocks)
        idx = np.concatenate([np.arange(s, s + block) for s in starts])[:n]
        s = m.iloc[idx]
        stats.append(spearman(s.iloc[:, 0], s.iloc[:, 1]))
    return (float(np.percentile(stats, 2.5)), float(np.percentile(stats, 97.5)))


def build_monthly() -> pd.DataFrame:
    d = er.load_inputs()
    spot = er.spot_series(d)
    fwd = er.forward_series_v1(d)   # v1 = the pre-2026-07 signal this analysis was a baseline for

    gld = d["gld"].reset_index(drop=True)
    close = gld["close"].to_numpy()
    n = len(gld)

    def fwd_ret(h: int) -> np.ndarray:
        out = np.full(n, np.nan)
        out[: n - h] = close[h:] / close[: n - h] - 1.0
        return out

    def trail_ret(h: int) -> np.ndarray:
        out = np.full(n, np.nan)
        out[h:] = close[h:] / close[: n - h] - 1.0
        return out

    base = pd.DataFrame({
        "date": gld["date"],
        "close": close,
        "ret63f": fwd_ret(63),
        "ret126f": fwd_ret(126),
        "ret63t": trail_ret(63),
        "ret21f": fwd_ret(21),
    })

    # GC=F robustness returns (its own trading spine, as-of joined)
    gc = pd.read_csv(er.INPUTS / "gcf.csv")
    gcc = gc["close"].to_numpy()
    gcr = np.full(len(gc), np.nan)
    gcr[: len(gc) - 63] = gcc[63:] / gcc[: len(gc) - 63] - 1.0
    gc_ser = pd.Series(gcr, index=gc["date"])
    idx = np.searchsorted(gc_ser.index.to_numpy(), base["date"].to_numpy(), side="right") - 1
    v = gc_ser.to_numpy()
    base["ret63f_gc"] = np.where(idx >= 0, v[np.clip(idx, 0, None)], np.nan)

    df = base.merge(spot[["date", "composite", "ry", "usd", "cb", "inf", "tech"]], on="date", how="left")
    df = df.merge(
        fwd[["date", "forward", "f_ry", "f_usd", "f_inf", "f_tech",
             "ry_delta", "usd_delta", "inf_delta", "roc60"]],
        on="date", how="left",
    )

    df["ym"] = df["date"].str[:7]
    monthly = df.groupby("ym").tail(1).reset_index(drop=True)
    monthly = monthly[monthly["composite"].notna()].reset_index(drop=True)
    return monthly


PERIODS = [
    ("full 2005-2026", "2005-01", "2026-12"),
    ("2005-2012", "2005-01", "2012-12"),
    ("2013-2018", "2013-01", "2018-12"),
    ("2019-2026", "2019-01", "2026-12"),
    ("2022-2026", "2022-01", "2026-12"),
]


def per_period_ic(m: pd.DataFrame, col: str, ret: str) -> None:
    for name, lo, hi in PERIODS:
        s = m[(m["ym"] >= lo) & (m["ym"] <= hi)]
        ic = spearman(s[col], s[ret])
        nn = len(s[[col, ret]].dropna())
        print(f"    {name:16s} IC={ic:+.3f}  (n={nn})")


def main() -> None:
    m = build_monthly()
    m.to_csv(er.ROOT / "results" / "monthly_dataset.csv", index=False)
    print(f"monthly rows: {len(m)}  ({m['ym'].iloc[0]} -> {m['ym'].iloc[-1]})")

    up = (m["ret63f"] > 0).mean()
    print(f"\nBASE RATE: P(3M gold return > 0) = {up:.1%}   "
          f"mean 3M ret = {m['ret63f'].mean():+.2%}, median = {m['ret63f'].median():+.2%}")

    print("\n== A. FORWARD SIGNAL (shipped): predictive power ==")
    ic = spearman(m["forward"], m["ret63f"])
    lo, hi = block_bootstrap_ci(m["forward"], m["ret63f"])
    print(f"  Spearman IC vs fwd 63d return : {ic:+.3f}   95% CI [{lo:+.3f}, {hi:+.3f}]")
    ic126 = spearman(m["forward"], m["ret126f"])
    lo6, hi6 = block_bootstrap_ci(m["forward"], m["ret126f"])
    print(f"  Spearman IC vs fwd 126d return: {ic126:+.3f}   95% CI [{lo6:+.3f}, {hi6:+.3f}]")
    icgc = spearman(m["forward"], m["ret63f_gc"])
    print(f"  Robustness (GC=F futures 63d) : {icgc:+.3f}")
    print("  Sub-periods (63d):")
    per_period_ic(m, "forward", "ret63f")

    print("\n  Label buckets (shipped thresholds: >=70 BULLISH, <45 BEARISH):")
    m["flabel"] = np.where(m["forward"] >= 70, "BULLISH", np.where(m["forward"] < 45, "BEARISH", "NEUTRAL"))
    for lab in ["BULLISH", "NEUTRAL", "BEARISH"]:
        s = m[m["flabel"] == lab]["ret63f"].dropna()
        if len(s) == 0:
            print(f"    {lab:8s} n=0")
            continue
        print(f"    {lab:8s} n={len(s):3d}  P(up)={np.mean(s > 0):.1%}  mean={s.mean():+.2%}  median={s.median():+.2%}")

    print("\n  Forward-signal quintiles vs fwd 63d return:")
    q = pd.qcut(m["forward"], 5, duplicates="drop")
    tab = m.groupby(q, observed=True)["ret63f"].agg(["count", "mean", "median"])
    print(tab.to_string(float_format=lambda x: f"{x:+.2%}"))

    print("\n== B. SPOT COMPOSITE (shipped): what does it actually measure? ==")
    print(f"  Coincident: Spearman(composite, TRAILING 63d ret) = {spearman(m['composite'], m['ret63t']):+.3f}")
    dm = m["composite"].diff()
    mret = m["close"].pct_change()
    print(f"  Coincident: Pearson(monthly d(composite), monthly gold ret) = {float(pd.concat([dm, mret], axis=1).corr().iloc[0, 1]):+.3f}")
    ics = spearman(m["composite"], m["ret63f"])
    los, his = block_bootstrap_ci(m["composite"], m["ret63f"])
    print(f"  Predictive: Spearman IC vs fwd 63d = {ics:+.3f}   95% CI [{los:+.3f}, {his:+.3f}]")
    print("  Sub-periods (63d):")
    per_period_ic(m, "composite", "ret63f")

    print("\n  Composite label time-share and outcomes:")
    m["clabel"] = np.where(m["composite"] >= 70, "BULLISH", np.where(m["composite"] < 45, "BEARISH", "NEUTRAL"))
    for lab in ["BULLISH", "NEUTRAL", "BEARISH"]:
        s = m[m["clabel"] == lab]["ret63f"].dropna()
        share = (m["clabel"] == lab).mean()
        if len(s) == 0:
            print(f"    {lab:8s} share={share:.1%}  n=0")
            continue
        print(f"    {lab:8s} share={share:.1%}  P(up next 3M)={np.mean(s > 0):.1%}  mean={s.mean():+.2%}")

    print("\n== C. Single-factor ICs vs fwd 63d return (shipped ingredients) ==")
    for col in ["f_ry", "f_usd", "f_inf", "f_tech", "ry", "usd", "cb", "inf", "tech"]:
        print(f"    {col:7s} IC={spearman(m[col], m['ret63f']):+.3f}", end="")
        s19 = m[m["ym"] >= "2019-01"]
        print(f"   | 2019+ IC={spearman(s19[col], s19['ret63f']):+.3f}")

    print("\n== D. Year-by-year regime table ==")
    m["year"] = m["ym"].str[:4]
    yr = m.groupby("year").agg(
        comp=("composite", "mean"), fwdsig=("forward", "mean"),
    )
    gld = er.load_inputs()["gld"]
    gld["year"] = gld["date"].str[:4]
    ylast = gld.groupby("year")["close"].last()
    yret = ylast.pct_change()
    yr["gold_ret"] = yret
    print(yr.to_string(float_format=lambda x: f"{x:+.2f}" if abs(x) < 3 else f"{x:.1f}"))


if __name__ == "__main__":
    main()
