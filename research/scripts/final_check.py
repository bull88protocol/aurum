#!/usr/bin/env python3
"""Final validation of the exact engine-shaped Forward Signal v2 before implementation:

  RY_blend = 0.5 * fixed-anchor(DFII10 level) + 0.5 * rolling-5y-percentile(DFII10)
  F_v2     = 0.55 * RY_blend + 0.25 * mom12(ROC252 map) + 0.20 * fed(DGS2 d63 map)

Full stats package: era ICs both horizons, bootstrap CIs, label calibration, quintiles
(test period), degraded modes (no DGS2 / momentum-only), current reading, and a
comparison chart if matplotlib is present.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import engine_replica as er
from evaluate_shipped import build_monthly, spearman, block_bootstrap_ci
from variants import add_factors, sub, TRAIN, TEST
from round3 import ERAS, RY_FIX, MOM_MAP, FED_MAP, rolling_pct, label_calibration


def main() -> None:
    m = build_monthly()
    m = add_factors(m)

    ryf = pd.read_csv(er.INPUTS / "dfii10.csv")
    ry_fix = pd.Series([er.piecewise(v, RY_FIX) for v in ryf["value"]], index=ryf["date"])
    ry_5y = pd.Series(rolling_pct(ryf["value"].to_numpy(), 1260, 504), index=ryf["date"])
    ry_blend = 0.5 * ry_fix + 0.5 * ry_5y

    dgs2 = pd.read_csv(er.INPUTS / "dgs2.csv")
    dgs2_d = dgs2["value"] - dgs2["value"].shift(62)
    fed = pd.Series([er.piecewise(v, FED_MAP) if not np.isnan(v) else np.nan for v in dgs2_d],
                    index=dgs2["date"])

    def asof(series: pd.Series) -> np.ndarray:
        idx = np.searchsorted(series.index.to_numpy(), m["date"].to_numpy(), side="right") - 1
        vals = series.to_numpy()
        return np.where(idx >= 0, vals[np.clip(idx, 0, None)], np.nan)

    m["ry_blend"] = asof(ry_blend)
    m["fed"] = asof(fed)
    m["mom12s"] = [er.piecewise(v * 100.0, MOM_MAP) if not np.isnan(v) else np.nan for v in m["mom_12"]]

    m["F_v2"] = 0.55 * m["ry_blend"] + 0.25 * m["mom12s"] + 0.20 * m["fed"]
    m["F_v2_nofed"] = (0.55 * m["ry_blend"] + 0.25 * m["mom12s"]) / 0.80   # no-DGS2 degraded mode
    m["F_v2_momonly"] = m["mom12s"]                                        # no-FRED-key mode

    cols = ["F_v2", "F_v2_nofed", "F_v2_momonly", "forward"]
    for hz, ret in [("63d", "ret63f"), ("126d", "ret126f")]:
        print(f"== Era ICs ({hz}) ==")
        print(f"{'config':12s} | " + " ".join(f"{e[0]:>9s}" for e in ERAS) + f" | {'train':>7s} {'test':>7s}")
        for c in cols:
            vals = [spearman(sub(m, lo, hi)[c], sub(m, lo, hi)[ret]) for _, lo, hi in ERAS]
            tr = spearman(sub(m, *TRAIN)[c], sub(m, *TRAIN)[ret])
            te = spearman(sub(m, *TEST)[c], sub(m, *TEST)[ret])
            print(f"{c:12s} | " + " ".join(f"{v:+9.3f}" for v in vals) + f" | {tr:+7.3f} {te:+7.3f}")
        print()

    print("== Bootstrap CIs for F_v2 (63d) ==")
    for name, lo_, hi_ in [("train", *TRAIN), ("test", *TEST), ("full", "2005-01", "2026-12")]:
        s = sub(m, lo_, hi_)
        ic = spearman(s["F_v2"], s["ret63f"])
        lo, hi = block_bootstrap_ci(s["F_v2"], s["ret63f"])
        print(f"  {name:5s} IC={ic:+.3f}  CI [{lo:+.3f}, {hi:+.3f}]")

    print("\n== F_v2 label calibration by era ==")
    label_calibration(m, "F_v2")

    print("\n== F_v2 label time-share (full sample) ==")
    v = m["F_v2"].dropna()
    print(f"  BULL {np.mean(v >= 70):.1%}  NEUT {np.mean((v >= 45) & (v < 70)):.1%}  BEAR {np.mean(v < 45):.1%}  mean={v.mean():.1f}")

    print("\n== F_v2 quintiles, TEST period only (2019-2026) ==")
    s = sub(m, *TEST)[["F_v2", "ret63f"]].dropna()
    q = pd.qcut(s["F_v2"], 5, duplicates="drop")
    tab = s.groupby(q, observed=True)["ret63f"].agg(["count", "mean"])
    tab["P(up)"] = s.groupby(q, observed=True)["ret63f"].apply(lambda x: (x > 0).mean())
    print(tab.to_string(float_format=lambda x: f"{x:+.2%}" if abs(x) < 1 else f"{x:.0f}"))

    print("\n== Sanity: F_v2 at known turning points ==")
    for dt in ["2011-08", "2012-09", "2013-01", "2015-11", "2018-09", "2020-07", "2022-10", "2024-02", "2025-03"]:
        r = m[m["ym"] == dt]
        if len(r):
            r = r.iloc[0]
            print(f"  {r['date']}: F_v2={r['F_v2']:.0f} shipped_fwd={r['forward']:.0f}  "
                  f"next63d={r['ret63f']:+.1%}" if not np.isnan(r["ret63f"]) else f"  {r['date']}: F_v2={r['F_v2']:.0f}")

    last = m.iloc[-1]
    print(f"\n== Current (as of {last['date']}) ==")
    print(f"  F_v2 = {last['F_v2']:.1f} ({'BULLISH' if last['F_v2'] >= 70 else 'NEUTRAL' if last['F_v2'] >= 45 else 'BEARISH'})"
          f"   [ry_blend={last['ry_blend']:.0f}, mom12s={last['mom12s']:.0f}, fed={last['fed']:.0f}]")
    print(f"  shipped forward = {last['forward']:.1f} ({'BULLISH' if last['forward'] >= 70 else 'NEUTRAL' if last['forward'] >= 45 else 'BEARISH'})")

    m.to_csv(er.ROOT / "results" / "monthly_v2.csv", index=False)

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt

        fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(13, 8), sharex=True,
                                       gridspec_kw={"height_ratios": [2, 1]})
        d = pd.to_datetime(m["date"])
        ax1.plot(d, m["close"], color="#b8860b", lw=1.2)
        ax1.set_yscale("log")
        ax1.set_ylabel("GLD (log)")
        ax1.set_title("Gold vs Forward Signal: shipped vs v2 (monthly, 2005-2026)")
        ax2.plot(d, m["forward"], color="#888", lw=1.0, label="shipped forward")
        ax2.plot(d, m["F_v2"], color="#1565c0", lw=1.4, label="F_v2 (real-rate + trend + fed)")
        ax2.axhline(70, color="g", ls=":", lw=0.8)
        ax2.axhline(45, color="r", ls=":", lw=0.8)
        ax2.set_ylabel("signal 0-100")
        ax2.legend(loc="upper left", fontsize=8)
        fig.tight_layout()
        out = er.ROOT / "results" / "forward_signal_v2.png"
        fig.savefig(out, dpi=110)
        print(f"\nchart -> {out}")
    except ImportError:
        print("\n(matplotlib not available — skipping chart)")


if __name__ == "__main__":
    main()
