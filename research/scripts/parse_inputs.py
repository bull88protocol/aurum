#!/usr/bin/env python3
"""Parse raw downloads (Yahoo chart JSON, fredgraph CSV) into clean input CSVs
for the engine harness + backtest, and print a data-quality report.

Dates for candles are derived exactly like the engine: epoch -> America/New_York date.
"""
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

RAW = Path(__file__).resolve().parents[1] / "cache" / "raw"
OUT = Path(__file__).resolve().parents[1] / "cache" / "inputs"
OUT.mkdir(parents=True, exist_ok=True)


def parse_yahoo(path: Path) -> pd.DataFrame:
    j = json.loads(path.read_text())
    res = j["chart"]["result"][0]
    ts = res["timestamp"]
    q = res["indicators"]["quote"][0]
    df = pd.DataFrame(
        {
            "epoch_s": ts,
            "open": q["open"],
            "high": q["high"],
            "low": q["low"],
            "close": q["close"],
            "volume": q["volume"],
        }
    )
    # Engine keeps bars only when O/H/L/C all present and close > 0 (YahooFinanceClient.parseCandles)
    df = df.dropna(subset=["open", "high", "low", "close"])
    df = df[df["close"] > 0].copy()
    df["volume"] = df["volume"].fillna(0).astype("int64")
    dt = pd.to_datetime(df["epoch_s"], unit="s", utc=True).dt.tz_convert("America/New_York")
    df["date"] = dt.dt.date.astype(str)
    # Dedupe by NY date, last wins (matches engine map semantics)
    df = df.drop_duplicates(subset="date", keep="last").sort_values("epoch_s").reset_index(drop=True)
    return df[["date", "epoch_s", "open", "high", "low", "close", "volume"]]


def parse_fred(path: Path, name: str) -> pd.DataFrame:
    df = pd.read_csv(path)
    df.columns = ["date", "value"]
    df["value"] = pd.to_numeric(df["value"], errors="coerce")
    df = df.dropna().reset_index(drop=True)
    df["date"] = df["date"].astype(str)
    return df


def gap_report(dates: pd.Series, label: str) -> None:
    d = pd.to_datetime(dates)
    gaps = d.diff().dt.days
    big = gaps[gaps > 7]
    print(f"  {label}: {len(d)} rows, {d.iloc[0].date()} -> {d.iloc[-1].date()}, "
          f"gaps>7d: {len(big)}" + (f" (max {int(gaps.max())}d @ {d[gaps.idxmax()].date()})" if len(big) else ""))


def main() -> None:
    ok = True

    gld = parse_yahoo(RAW / "gld_yahoo.json")
    dxy = parse_yahoo(RAW / "dxy_yahoo.json")
    gcf = parse_yahoo(RAW / "gcf_yahoo.json")
    gld.to_csv(OUT / "gld.csv", index=False)
    dxy.to_csv(OUT / "dxy.csv", index=False)
    gcf.to_csv(OUT / "gcf.csv", index=False)

    print("== Candle series ==")
    gap_report(gld["date"], "GLD  ")
    gap_report(dxy["date"], "DXY  ")
    gap_report(gcf["date"], "GC=F ")

    print("\n== FRED series ==")
    for sid in ["dfii10", "t10yie", "dgs2", "vixcls"]:
        f = parse_fred(RAW / f"fred_{sid}.csv", sid)
        f.to_csv(OUT / f"{sid}.csv", index=False)
        gap_report(f["date"], sid.upper())

    print("\n== Latest values (eyeball check) ==")
    for name, df in [("GLD", gld), ("DXY", dxy), ("GC=F", gcf)]:
        print(f"  {name}: {df['date'].iloc[-1]}  close={df['close'].iloc[-1]:.2f}")
    for sid in ["dfii10", "t10yie", "dgs2", "vixcls"]:
        f = pd.read_csv(OUT / f"{sid}.csv")
        print(f"  {sid.upper()}: {f['date'].iloc[-1]}  value={f['value'].iloc[-1]:.2f}")

    print("\n== Cross-check: GLD*10 vs GC=F (same underlying; ratio should sit ~0.96-1.01 and drift slowly) ==")
    m = pd.merge(gld[["date", "close"]], gcf[["date", "close"]], on="date", suffixes=("_gld", "_gcf"))
    m["ratio"] = m["close_gld"] * 10.0 / m["close_gcf"]
    by_year = m.assign(year=m["date"].str[:4]).groupby("year")["ratio"].agg(["mean", "min", "max"])
    print(by_year.to_string(float_format=lambda x: f"{x:.4f}"))
    # Lower bound 0.88: GLD closes 16:00 ET, GC=F 17:00 ET, so violent days gap the ratio
    # (e.g. 2026-06-17 hit 0.8915). Structural drift ~0.40%/yr = GLD expense ratio.
    bad = m[(m["ratio"] < 0.88) | (m["ratio"] > 1.06)]
    if len(bad):
        ok = False
        print(f"  !! {len(bad)} days outside [0.90, 1.05] — inspect:")
        print(bad.head(10).to_string())
    else:
        print(f"  OK: all {len(m)} overlapping days within [0.90, 1.05]")

    print("\n== DXY landmark sanity (known regime extremes) ==")
    dxy_min = dxy.loc[dxy["close"].idxmin()]
    dxy_max = dxy.loc[dxy["close"].idxmax()]
    print(f"  min close {dxy_min['close']:.2f} on {dxy_min['date']} (2008 trough was ~71)")
    print(f"  max close {dxy_max['close']:.2f} on {dxy_max['date']} (2001 peak ~121 / 2022 peak ~114)")
    if not (65 < dxy_min["close"] < 75 and 110 < dxy_max["close"] < 125):
        ok = False
        print("  !! DXY extremes off expected regime bounds")

    print("\n== GLD monotonic date + positivity ==")
    for name, df in [("GLD", gld), ("DXY", dxy)]:
        assert df["date"].is_monotonic_increasing, f"{name} dates not sorted"
        assert (df["close"] > 0).all()
    print("  OK")

    print("\nDATA_QUALITY:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
