#!/usr/bin/env python3
"""Fairness check on the label buckets: bullish vs bearish readings, prior and next 20d, and 63d."""
import numpy as np, pandas as pd
from pathlib import Path
code = open(Path(__file__).resolve().parent / "cgs_backtest.py").read().split("\nbase = build()")[0]
exec(code)
b = build(); b = b[b.index >= "2007-01-01"].dropna(subset=["cgs"])
def side(x): return "bullish (>=30)" if x >= 30 else ("bearish (<=-30)" if x <= -30 else "neutral")
nz = every(b, 20).dropna(subset=["fwd20"]).copy(); nz["s"] = nz["cgs"].map(side)
print("20d non-overlapping: base up %.1f%% mean %+.2f%%" % (100*(nz.fwd20>0).mean(), nz.fwd20.mean()))
print(nz.groupby("s").agg(n=("fwd20","size"), prev_up=("back20", lambda x: 100*(x>0).mean()), prev_mean=("back20","mean"),
      next_up=("fwd20", lambda x: 100*(x>0).mean()), next_mean=("fwd20","mean")).round(1).to_string())
for lo, hi in [("2007","2018"),("2019","2026")]:
    a = sub(nz, lo, hi)
    print(lo, hi, a.groupby("s").agg(n=("fwd20","size"), next_up=("fwd20", lambda x: 100*(x>0).mean()), next_mean=("fwd20","mean")).round(1).to_dict("index"))
me = month_ends(b).dropna(subset=["fwd63"]).copy(); me["s"] = me["cgs"].map(side)
print("63d month-ends: base up %.1f%% mean %+.2f%%" % (100*(me.fwd63>0).mean(), me.fwd63.mean()))
print(me.groupby("s").agg(n=("fwd63","size"), next63_up=("fwd63", lambda x: 100*(x>0).mean()), next63_mean=("fwd63","mean")).round(1).to_string())
from math import sqrt
bu = nz[nz.s.str.startswith("bull")].fwd20; be = nz[nz.s.str.startswith("bear")].fwd20
p1, p2 = (bu>0).mean(), (be>0).mean(); p = ((bu>0).sum()+(be>0).sum())/(len(bu)+len(be))
z = (p1-p2)/sqrt(p*(1-p)*(1/len(bu)+1/len(be))); print(f"bull vs bear next-20d up-rate z={z:.2f}")
