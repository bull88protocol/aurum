#!/usr/bin/env python3
"""Round 3 — the implementation question. ry_level is the robust forward driver, but HOW
it is mapped to a 0-100 score decides whether whole eras get mislabeled:

  (a) fixed absolute anchors      -> would have called 2019-2021 (low/negative yields,
                                     gold +40%) BEARISH for three straight years
  (b) rolling 5y percentile       -> adapts to secular drift, look-ahead-free,
                                     engine-implementable (one longer FRED fetch)

Candidates (final, engine-shaped):
  F_A_fix : 1.00 * anchored ry_level
  F_A_5y  : 1.00 * rolling-5y-percentile ry_level
  F_B     : 0.70 * ry5y + 0.30 * mom12 (anchored ROC252 map)
  F_C     : 0.55 * ry5y + 0.25 * mom12 + 0.20 * fed (DGS2 63-obs delta, falling=bullish)

Metrics: era ICs (63/126d), test CIs, LABEL calibration by era (P(up)|BULLISH/BEARISH at
the app's >=70 / <45 thresholds), current reading. Then the spot-index fix: RY/INF
percentile windows 252 -> 504 (pin rates + coincident quality).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import engine_replica as er
from evaluate_shipped import build_monthly, spearman, block_bootstrap_ci
from variants import add_factors, sub, TRAIN, TEST

ERAS = [("2005-2012", "2005-01", "2012-12"), ("2013-2018", "2013-01", "2018-12"),
        ("2019-2021", "2019-01", "2021-12"), ("2022-2026", "2022-01", "2026-12")]

RY_FIX = [(-1.0, 12.0), (-0.5, 22.0), (0.0, 32.0), (0.5, 42.0), (1.0, 52.0),
          (1.5, 62.0), (2.0, 74.0), (2.5, 86.0), (3.0, 92.0)]
MOM_MAP = [(-25.0, 15.0), (-10.0, 30.0), (0.0, 45.0), (10.0, 58.0), (20.0, 70.0),
           (35.0, 82.0), (50.0, 90.0)]
FED_MAP = [(-2.0, 90.0), (-1.0, 75.0), (-0.25, 60.0), (0.25, 50.0), (1.0, 35.0), (2.0, 15.0)]


def rolling_pct(values: np.ndarray, window: int, min_n: int) -> np.ndarray:
    out = np.full(len(values), np.nan)
    for i in range(len(values)):
        lo = max(0, i + 1 - window)
        w = values[lo:i + 1]
        if len(w) < min_n:
            continue
        out[i] = np.clip((w < values[i]).sum() / len(w), 0.05, 0.95) * 100.0
    return out


def label_calibration(m: pd.DataFrame, col: str) -> None:
    for name, lo, hi in ERAS:
        s = sub(m, lo, hi)
        rows = []
        for lab, mask in [("BULL", s[col] >= 70), ("NEUT", (s[col] >= 45) & (s[col] < 70)), ("BEAR", s[col] < 45)]:
            r = s[mask]["ret63f"].dropna()
            rows.append(f"{lab} n={len(r):2d} " + (f"P(up)={np.mean(r > 0):3.0%} m={r.mean():+5.1%}" if len(r) else "--"))
        base = s["ret63f"].dropna()
        print(f"    {name}: " + " | ".join(rows) + f"   [era base {np.mean(base > 0):.0%}, {base.mean():+.1%}]")


def main() -> None:
    m = build_monthly()
    m = add_factors(m)

    # RY mappings on the DFII10 spine, as-of joined to month-ends
    ryf = pd.read_csv(er.INPUTS / "dfii10.csv")
    ry_fix = pd.Series([er.piecewise(v, RY_FIX) for v in ryf["value"]], index=ryf["date"])
    ry_5y = pd.Series(rolling_pct(ryf["value"].to_numpy(), 1260, 504), index=ryf["date"])

    dgs2 = pd.read_csv(er.INPUTS / "dgs2.csv")
    dgs2_d = dgs2["value"] - dgs2["value"].shift(62)
    fed = pd.Series([er.piecewise(v, FED_MAP) if not np.isnan(v) else np.nan for v in dgs2_d], index=dgs2["date"])

    def asof(series: pd.Series) -> np.ndarray:
        idx = np.searchsorted(series.index.to_numpy(), m["date"].to_numpy(), side="right") - 1
        vals = series.to_numpy()
        return np.where(idx >= 0, vals[np.clip(idx, 0, None)], np.nan)

    m["ry_fix"] = asof(ry_fix)
    m["ry_5y"] = asof(ry_5y)
    m["fed"] = asof(fed)
    m["mom12s"] = [er.piecewise(v * 100.0, MOM_MAP) if not np.isnan(v) else np.nan for v in m["mom_12"]]

    m["F_A_fix"] = m["ry_fix"]
    m["F_A_5y"] = m["ry_5y"]
    m["F_B"] = 0.70 * m["ry_5y"] + 0.30 * m["mom12s"]
    m["F_C"] = 0.55 * m["ry_5y"] + 0.25 * m["mom12s"] + 0.20 * m["fed"]

    cols = ["F_A_fix", "F_A_5y", "F_B", "F_C", "forward"]
    for hz, ret in [("63d", "ret63f"), ("126d", "ret126f")]:
        print(f"== Era ICs ({hz}) ==")
        print(f"{'config':9s} | " + " ".join(f"{e[0]:>9s}" for e in ERAS) + f" | {'train':>7s} {'test':>7s}")
        for c in cols:
            vals = [spearman(sub(m, lo, hi)[c], sub(m, lo, hi)[ret]) for _, lo, hi in ERAS]
            tr = spearman(sub(m, *TRAIN)[c], sub(m, *TRAIN)[ret])
            te = spearman(sub(m, *TEST)[c], sub(m, *TEST)[ret])
            print(f"{c:9s} | " + " ".join(f"{v:+9.3f}" for v in vals) + f" | {tr:+7.3f} {te:+7.3f}")
        print()

    print("== Test-period bootstrap CIs (63d) ==")
    for c in ["F_A_5y", "F_B", "F_C"]:
        s = sub(m, *TEST)
        ic = spearman(s[c], s["ret63f"])
        lo, hi = block_bootstrap_ci(s[c], s["ret63f"])
        print(f"  {c:8s} test IC={ic:+.3f}  CI [{lo:+.3f}, {hi:+.3f}]")

    print("\n== Label calibration by era (app thresholds >=70 BULL, <45 BEAR) ==")
    for c in ["F_A_fix", "F_A_5y", "F_B", "F_C"]:
        print(f"  {c}:")
        label_calibration(m, c)

    print("\n== Signal distribution (time share of labels, full sample) ==")
    for c in ["F_A_5y", "F_B", "F_C", "forward"]:
        v = m[c].dropna()
        print(f"  {c:8s} BULL {np.mean(v >= 70):5.1%}  NEUT {np.mean((v >= 45) & (v < 70)):5.1%}  "
              f"BEAR {np.mean(v < 45):5.1%}   mean={v.mean():.1f}")

    print("\n== Current readings (2026-07) ==")
    last = m.iloc[-1]
    print(f"  shipped composite={last['composite']:.1f}, shipped forward={last['forward']:.1f}")
    for c in ["F_A_fix", "F_A_5y", "F_B", "F_C"]:
        print(f"  {c:8s} = {last[c]:.1f}")
    print(f"  raw: DFII10={last['ry_level']:.2f}%  ry5y_pct={last['ry_5y']:.0f}  mom12={last['mom_12']:+.1%}  "
          f"dgs2_d63={last['dgs2_delta63']:+.2f}")

    # ── Spot fix: widen RY/INF percentile windows to 504d ────────────────────
    print("\n== Spot-index fix: RY/INF percentile window 252 -> 504 ==")
    d = er.load_inputs()
    ry_504 = pd.Series(er.rolling_pct_count(ryf["value"].to_numpy(), 504, "inverted"), index=ryf["date"])
    inff = pd.read_csv(er.INPUTS / "t10yie.csv")
    inf_504 = pd.Series(er.rolling_pct_count(inff["value"].to_numpy(), 504, "direct"), index=inff["date"])
    m["ry504"] = asof(ry_504)
    m["inf504"] = asof(inf_504)

    for c, base in [("ry504", "ry"), ("inf504", "inf")]:
        pin_new = ((m[c] <= 5.001) | (m[c] >= 94.999)).mean()
        pin_old = ((m[base] <= 5.001) | (m[base] >= 94.999)).mean()
        print(f"  {base}: pinned {pin_old:.0%} -> {pin_new:.0%} with 504d window")

    # rebuild composite with 504d RY/INF, same weights, and compare nowcast quality
    comp504 = (0.30 * m["ry504"] + 0.23 * m["usd"] + 0.22 * m["cb"] + 0.13 * m["inf504"] + 0.12 * m["tech"])
    mret = m["close"].pct_change()
    for name, c in [("shipped composite", m["composite"]), ("504d-window composite", comp504)]:
        s_lvl = spearman(c, m["ret63t"])
        p_d = float(pd.concat([c.diff(), mret], axis=1).dropna().corr().iloc[0, 1])
        ic = spearman(c, m["ret63f"])
        print(f"  {name:22s} coincident S(level,trail63)={s_lvl:+.3f}  P(dLevel,mret)={p_d:+.3f}  fwd IC={ic:+.3f}")
    m["comp504"] = comp504
    m.to_csv(er.ROOT / "results" / "monthly_final.csv", index=False)


if __name__ == "__main__":
    main()
