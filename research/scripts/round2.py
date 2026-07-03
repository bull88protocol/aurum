#!/usr/bin/env python3
"""Round 2 — POST-HOC configs (declared after seeing the single-factor table, so held to a
stricter bar: sign-consistency across four sub-eras, both horizons, bootstrap CI, plus a
factor-redundancy check). Candidates are built only from the two factors that were stable
in BOTH halves of round 1: real-yield LEVEL (high = bullish, IC +0.42/+0.42) and 12-1
momentum (+0.08/+0.16), with dgs2 delta as an optional Fed-cycle sleeve.

Also: spot-index diagnostics — how often the RY/INF percentile components sit pinned at
their 5/95 floors (uninformative), by era.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import engine_replica as er
from evaluate_shipped import build_monthly, spearman, block_bootstrap_ci
from variants import add_factors, expanding_rank, sub, TRAIN, TEST

ERAS = [("2005-2012", "2005-01", "2012-12"), ("2013-2018", "2013-01", "2018-12"),
        ("2019-2021", "2019-01", "2021-12"), ("2022-2026", "2022-01", "2026-12")]


def main() -> None:
    m = build_monthly()
    m = add_factors(m)

    pr_ry = expanding_rank(m["ry_level"])
    pr_mom = expanding_rank(m["mom_12_1"])
    pr_dgs2 = expanding_rank(m["dgs2_delta63"])
    pr_a200 = expanding_rank(m["above200"])

    m["C_rylevel"] = 100 * pr_ry
    m["C_value_mom"] = 100 * (0.5 * pr_ry + 0.5 * pr_mom)
    m["C_vmf"] = 100 * (0.40 * pr_ry + 0.40 * pr_mom + 0.20 * (1 - pr_dgs2))
    m["C_vmt"] = 100 * (0.40 * pr_ry + 0.30 * pr_mom + 0.30 * pr_a200)

    print("== Era-stability of headline factors/configs (Spearman IC vs fwd 63d) ==")
    cols = ["ry_level", "mom_12_1", "C_rylevel", "C_value_mom", "C_vmf", "C_vmt", "forward"]
    hdr = f"{'series':12s} | " + " ".join(f"{e[0]:>9s}" for e in ERAS) + f" | {'train':>7s} {'test':>7s}"
    print(hdr)
    for c in cols:
        vals = [spearman(sub(m, lo, hi)[c], sub(m, lo, hi)["ret63f"]) for _, lo, hi in ERAS]
        tr = spearman(sub(m, *TRAIN)[c], sub(m, *TRAIN)["ret63f"])
        te = spearman(sub(m, *TEST)[c], sub(m, *TEST)["ret63f"])
        print(f"{c:12s} | " + " ".join(f"{v:+9.3f}" for v in vals) + f" | {tr:+7.3f} {te:+7.3f}")

    print("\n== Same at 126d horizon ==")
    for c in cols:
        vals = [spearman(sub(m, lo, hi)[c], sub(m, lo, hi)["ret126f"]) for _, lo, hi in ERAS]
        tr = spearman(sub(m, *TRAIN)[c], sub(m, *TRAIN)["ret126f"])
        te = spearman(sub(m, *TEST)[c], sub(m, *TEST)["ret126f"])
        print(f"{c:12s} | " + " ".join(f"{v:+9.3f}" for v in vals) + f" | {tr:+7.3f} {te:+7.3f}")

    print("\n== Bootstrap 95% CIs (63d) ==")
    for c in ["C_rylevel", "C_value_mom", "C_vmf", "C_vmt"]:
        for name, lo_, hi_ in [("train", *TRAIN), ("test", *TEST)]:
            s = sub(m, lo_, hi_)
            ic = spearman(s[c], s["ret63f"])
            lo, hi = block_bootstrap_ci(s[c], s["ret63f"])
            print(f"  {c:12s} {name:5s} IC={ic:+.3f}  CI [{lo:+.3f}, {hi:+.3f}]")

    print("\n== Factor redundancy (Spearman among candidate ingredients, full sample) ==")
    ing = pd.DataFrame({"pr_ry": pr_ry, "pr_mom": pr_mom, "pr_dgs2i": 1 - pr_dgs2,
                        "pr_a200": pr_a200, "cb": m["cb"]})
    print(ing.corr(method="spearman").to_string(float_format=lambda x: f"{x:+.2f}"))

    print("\n== C_value_mom quintiles (train | test) ==")
    for name, lo_, hi_ in [("train", *TRAIN), ("test", *TEST)]:
        s = sub(m, lo_, hi_)[["C_value_mom", "ret63f"]].dropna()
        q = pd.qcut(s["C_value_mom"], 5, duplicates="drop")
        tab = s.groupby(q, observed=True)["ret63f"].agg(["count", "mean"])
        tab["P(up)"] = s.groupby(q, observed=True)["ret63f"].apply(lambda x: (x > 0).mean())
        print(f"  {name}:")
        print(tab.to_string(float_format=lambda x: f"{x:+.2%}" if abs(x) < 1 else f"{x:.0f}"))

    print("\n== Current readings (latest month-end) ==")
    last = m.iloc[-1]
    print(f"  date={last['date']}  shipped composite={last['composite']:.1f} forward={last['forward']:.1f}")
    for c in ["C_rylevel", "C_value_mom", "C_vmf", "C_vmt"]:
        print(f"  {c:12s} = {last[c]:.1f}")
    print(f"  inputs: ry_level={last['ry_level']:.2f}  mom_12_1={last['mom_12_1']:+.1%}  "
          f"dgs2_d63={last['dgs2_delta63']:+.2f}  above200={last['above200']:+.1%}")

    print("\n== Spot RY/INF components: fraction of months pinned at the 5/95 clamps ==")
    for c in ["ry", "inf"]:
        m["pin"] = (m[c] <= 5.001) | (m[c] >= 94.999)
        by = m.groupby(m["ym"].str[:4])["pin"].mean()
        pinned_yrs = ", ".join(f"{y}:{v:.0%}" for y, v in by.items() if v > 0.25)
        print(f"  {c}: overall {m['pin'].mean():.0%} pinned; years >25% pinned -> {pinned_yrs}")


if __name__ == "__main__":
    main()
