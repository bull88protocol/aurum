#!/usr/bin/env python3
"""Pre-registered variant tests for the Gold Index forward signal + nowcast quality.

Discipline: every candidate below was declared before looking at its result. Train
window 2005-2018, test window 2019-2026 (includes the 2022+ regime break). A change
is adoptable only if it works in train AND holds in test. All factors are built
point-in-time (expanding ranks, publication-lagged CB); no full-sample scaling.

Factor zoo (all real series already pulled):
  momentum:  mom_12_1 (12m ret excl. last month), above200 (close/sma200-1), roc60
  macro dv:  ry_delta63, usd_delta63, inf_delta63, dgs2_delta63 (Fed cycle), vix_d63
  levels:    ry_level, vix_level, cb_score (publication-lagged), composite (shipped)
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import engine_replica as er
from evaluate_shipped import build_monthly, spearman, block_bootstrap_ci

TRAIN = ("2005-01", "2018-12")
TEST = ("2019-01", "2026-12")


def sub(m: pd.DataFrame, lo: str, hi: str) -> pd.DataFrame:
    return m[(m["ym"] >= lo) & (m["ym"] <= hi)]


def expanding_rank(s: pd.Series, min_n: int = 36) -> pd.Series:
    """Point-in-time percent rank of the latest value within all history up to t."""
    vals = s.to_numpy()
    out = np.full(len(vals), np.nan)
    for i in range(len(vals)):
        if np.isnan(vals[i]):
            continue
        hist = vals[: i + 1]
        hist = hist[~np.isnan(hist)]
        if len(hist) < min_n:
            continue
        out[i] = (hist < vals[i]).sum() / len(hist)
    return pd.Series(out, index=s.index)


def add_factors(m: pd.DataFrame) -> pd.DataFrame:
    d = er.load_inputs()
    gld = d["gld"].reset_index(drop=True)
    close = gld["close"]

    mom_12_1 = close.shift(21) / close.shift(273) - 1.0   # 12m ret excluding last month (21d)
    mom_12 = close / close.shift(252) - 1.0
    sma200 = close.rolling(200).mean()
    above200 = close / sma200 - 1.0
    daily = pd.DataFrame({
        "date": gld["date"],
        "mom_12_1": mom_12_1,
        "mom_12": mom_12,
        "above200": above200,
    })
    m = m.merge(daily, on="date", how="left")

    def asof_join(csv: str, col: str, delta: int | None) -> pd.Series:
        f = pd.read_csv(er.INPUTS / csv)
        v = f["value"]
        ser = pd.Series((v - v.shift(delta)).to_numpy() if delta else v.to_numpy(), index=f["date"])
        idx = np.searchsorted(ser.index.to_numpy(), m["date"].to_numpy(), side="right") - 1
        vals = ser.to_numpy()
        out = np.where(idx >= 0, vals[np.clip(idx, 0, None)], np.nan)
        return pd.Series(out, index=m.index, name=col)

    m["dgs2_delta63"] = asof_join("dgs2.csv", "dgs2_delta63", 62)
    m["vix_level"] = asof_join("vixcls.csv", "vix_level", None)
    m["vix_d63"] = asof_join("vixcls.csv", "vix_d63", 62)
    m["ry_level"] = asof_join("dfii10.csv", "ry_level", None)
    return m


FACTORS = [
    # (name, gold-bullish direction note)
    ("mom_12_1", "+"), ("mom_12", "+"), ("above200", "+"), ("roc60", "+"),
    ("ry_delta", "-"), ("usd_delta", "-"), ("inf_delta", "+"),
    ("dgs2_delta63", "-"), ("vix_level", "+"), ("vix_d63", "+"), ("ry_level", "-"),
    ("cb", "+"), ("composite", "?"), ("forward", "?"), ("ret63t", "?"),
]


def factor_table(m: pd.DataFrame) -> None:
    print("== Single-factor Spearman IC vs forward returns (monthly obs) ==")
    print(f"{'factor':13s} {'dir':3s} | {'train63':>8s} {'test63':>8s} | {'train126':>9s} {'test126':>9s} | {'2022+63':>8s}")
    tr, te = sub(m, *TRAIN), sub(m, *TEST)
    t22 = sub(m, "2022-01", "2026-12")
    for name, direction in FACTORS:
        r = [
            spearman(tr[name], tr["ret63f"]), spearman(te[name], te["ret63f"]),
            spearman(tr[name], tr["ret126f"]), spearman(te[name], te["ret126f"]),
            spearman(t22[name], t22["ret63f"]),
        ]
        print(f"{name:13s} {direction:3s} | {r[0]:+8.3f} {r[1]:+8.3f} | {r[2]:+9.3f} {r[3]:+9.3f} | {r[4]:+8.3f}")


def build_configs(m: pd.DataFrame) -> pd.DataFrame:
    """Candidate forward signals as weighted sums of point-in-time expanding ranks
    (oriented so higher = gold-bullish), scaled 0-100."""
    pr = {c: expanding_rank(m[c]) for c in
          ["mom_12_1", "above200", "ry_delta", "usd_delta", "dgs2_delta63", "cb", "composite", "inf_delta"]}

    m["C_mom"] = 100 * (0.5 * pr["mom_12_1"] + 0.5 * pr["above200"])
    m["C_mommacro"] = 100 * (0.35 * pr["mom_12_1"] + 0.25 * (1 - pr["ry_delta"])
                             + 0.25 * (1 - pr["usd_delta"]) + 0.15 * (1 - pr["dgs2_delta63"]))
    m["C_fed"] = 100 * (0.5 * (1 - pr["dgs2_delta63"]) + 0.5 * pr["mom_12_1"])
    m["C_contra"] = 100 * (1 - pr["composite"])
    m["C_cbmom"] = 100 * (0.4 * pr["cb"] + 0.6 * pr["mom_12_1"])
    m["C_macroinv"] = 100 * ((1 - pr["ry_delta"]) * 0.4 + (1 - pr["usd_delta"]) * 0.3
                             + pr["inf_delta"] * 0.2 + pr["mom_12_1"] * 0.1)  # shipped recipe, rank form
    return m


def config_table(m: pd.DataFrame) -> None:
    print("\n== Candidate forward-signal configs (pre-registered) ==")
    print(f"{'config':12s} | {'train63':>8s} {'test63':>8s} {'2022+':>8s} | {'testCI':>18s}")
    tr, te = sub(m, *TRAIN), sub(m, *TEST)
    t22 = sub(m, "2022-01", "2026-12")
    for c in ["forward", "C_macroinv", "C_mom", "C_mommacro", "C_fed", "C_contra", "C_cbmom"]:
        lo, hi = block_bootstrap_ci(te[c], te["ret63f"])
        print(f"{c:12s} | {spearman(tr[c], tr['ret63f']):+8.3f} {spearman(te[c], te['ret63f']):+8.3f} "
              f"{spearman(t22[c], t22['ret63f']):+8.3f} | [{lo:+.3f}, {hi:+.3f}]")

    print("\n  Tercile outcomes (test 2019-2026), P(up next 3M) / mean — base rate:", end=" ")
    print(f"{(te['ret63f'] > 0).mean():.1%} / {te['ret63f'].mean():+.2%}")
    for c in ["forward", "C_mom", "C_mommacro", "C_cbmom"]:
        s = te[[c, "ret63f"]].dropna()
        q = pd.qcut(s[c], 3, labels=["low", "mid", "high"], duplicates="drop")
        g = s.groupby(q, observed=True)["ret63f"]
        parts = [f"{lab}: {(grp > 0).mean():.0%}/{grp.mean():+.2%} (n={len(grp)})" for lab, grp in g]
        print(f"    {c:12s} " + "  ".join(parts))


def stretched_warning(m: pd.DataFrame) -> None:
    print("\n== 'Stretched' contrarian warning (composite >= 70) by era ==")
    for name, lo, hi in [("2005-2018", *TRAIN), ("2019-2026", *TEST), ("2024-2026", "2024-01", "2026-12")]:
        s = sub(m, lo, hi)
        w = s[s["composite"] >= 70]["ret63f"].dropna()
        r = s["ret63f"].dropna()
        if len(w) < 3:
            print(f"  {name}: n={len(w)} (too few)")
            continue
        print(f"  {name}: after composite>=70 (n={len(w)}): P(up)={np.mean(w > 0):.0%}, mean={w.mean():+.2%}"
              f"   vs era base {np.mean(r > 0):.0%}, {r.mean():+.2%}")


def nowcast_quality(m: pd.DataFrame) -> None:
    print("\n== Nowcast quality (coincident association with gold, monthly) ==")
    mret = m["close"].pct_change()
    print(f"{'series':12s} | {'S(level,trail63)':>16s} | {'P(dLevel,mret)':>14s}")
    for c in ["composite", "ry", "usd", "cb", "inf", "tech", "forward"]:
        s_lvl = spearman(m[c], m["ret63t"])
        dp = pd.concat([m[c].diff(), mret], axis=1).dropna()
        p_d = float(dp.corr().iloc[0, 1]) if len(dp) > 10 else np.nan
        print(f"{c:12s} | {s_lvl:+16.3f} | {p_d:+14.3f}")


def main() -> None:
    m = build_monthly()
    m = add_factors(m)
    m = build_configs(m)
    m.to_csv(er.ROOT / "results" / "monthly_factors.csv", index=False)
    factor_table(m)
    config_table(m)
    stretched_warning(m)
    nowcast_quality(m)


if __name__ == "__main__":
    main()
