#!/usr/bin/env python3
"""Engine parity: GoldDriversEngine's own replay (EngineHistoryDumpTest.dumpDriversHistory) vs
drivers_replica.py, day by day over the full research history. Run both producers first."""
from pathlib import Path

import pandas as pd

ENGINE = Path(__file__).resolve().parents[2] / "cache" / "engine"
e = pd.read_csv(ENGINE / "engine_drivers_daily.csv")
r = pd.read_csv(ENGINE / "replica_drivers_daily.csv")
m = e.merge(r, on="date", suffixes=("_eng", "_rep"), how="outer", indicator=True)
print("rows engine", len(e), "replica", len(r), "merge:", m["_merge"].value_counts().to_dict())
both = m[m["_merge"] == "both"]
for col in ["ry_s", "usd_s", "drivers"]:
    a, b = both[f"{col}_eng"], both[f"{col}_rep"]
    print(f"{col:8s} availability mismatches={(a.isna() != b.isna()).sum()}  max|diff|={(a - b).abs().max():.2e}")
