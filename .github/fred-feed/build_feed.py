#!/usr/bin/env python3
"""Builds fred_daily.json, the hosted FRED feed the app's 6 PM daily report reads.

Run by .github/workflows/fred-feed.yml with the maintainer's FRED key (repository secret
FRED_API_KEY). Fetches exactly what DataRepository.kt fetches with a user's key (same series,
history window and row limit) so the engines score identically from either source.

Refuses to write a feed that looks wrong (too short, stale, out of range, out of order) and exits
non-zero instead, which fails the run and makes GitHub email the repo owner: a FRED hiccup must
not ship bad yields to every install. The request URL carries the key, so it is never printed.

  FRED_API_KEY=... python3 build_feed.py --out feed/fred_daily.json [--previous published.json]

Writes changed=true|false and last_date=YYYY-MM-DD to $GITHUB_OUTPUT when that is set.
FRED_API_BASE overrides the API endpoint (for testing against a local mock only).
"""
import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = os.environ.get("FRED_API_BASE", "https://api.stlouisfed.org") + "/fred/series/observations"
NOTICE = ("This product uses the FRED® API but is not endorsed or certified by the "
          "Federal Reserve Bank of St. Louis.")
TERMS = "https://fred.stlouisfed.org/docs/api/terms_of_use.html"

# id -> (years of history, row limit, plausible range). Years and limits mirror DataRepository.kt.
SERIES = {
    "DFII10": (6, 2000, (-3.0, 6.0)),
    "T10YIE": (3, 1000, (-1.0, 6.0)),
    "DGS2":   (3, 1000, (-1.0, 20.0)),
}
MAX_STALE_DAYS = 10          # FredFeedClient.MAX_STALE_DAYS drops anything older anyway
MIN_OBS_PER_YEAR = 200       # ~250 trading days a year, less holidays and missing prints


def years_ago(day, n):
    try:
        return day.replace(year=day.year - n)
    except ValueError:       # Feb 29
        return day.replace(year=day.year - n, day=28)


def fetch(series_id, key, start, limit):
    query = urllib.parse.urlencode({
        "series_id": series_id, "api_key": key, "file_type": "json",
        "sort_order": "asc", "limit": limit, "observation_start": start,
    })
    last_error = "unknown"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(f"{API}?{query}", timeout=60) as resp:
                return json.load(resp)["observations"]
        except urllib.error.HTTPError as e:
            last_error = f"HTTP {e.code}"          # never str(e) or e.url: the URL holds the key
            if 400 <= e.code < 500 and e.code != 429:
                break                              # bad key or bad request: retrying won't help
        except (urllib.error.URLError, TimeoutError, OSError, KeyError, ValueError) as e:
            last_error = type(e).__name__
        time.sleep(15 * (attempt + 1))
    sys.exit(f"{series_id}: FRED request failed ({last_error})")


def clean(series_id, observations, today, years, value_range):
    dates, values = [], []
    for o in observations:
        if o.get("value") in (".", "", None):     # FRED's marker for a missing print (holidays)
            continue
        dates.append(o["date"])
        values.append(float(o["value"]))
    problems = []
    if len(values) < years * MIN_OBS_PER_YEAR:
        problems.append(f"only {len(values)} observations")
    if dates and dt.date.fromisoformat(dates[-1]) < today - dt.timedelta(days=MAX_STALE_DAYS):
        problems.append(f"latest observation {dates[-1]} is stale")
    lo, hi = value_range
    if any(not (lo <= v <= hi) for v in values):
        problems.append(f"values outside {lo}..{hi}")
    if any(a >= b for a, b in zip(dates, dates[1:])):
        problems.append("dates not strictly ascending")
    if problems:
        sys.exit(f"{series_id}: refusing to publish: " + "; ".join(problems))
    return {"dates": dates, "values": values}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--previous", help="the currently published feed, to skip no-op publishes")
    args = ap.parse_args()

    key = os.environ.get("FRED_API_KEY", "").strip()
    if not key:
        sys.exit("FRED_API_KEY is not set (add it under Settings > Secrets and variables > Actions)")

    today = dt.datetime.now(dt.timezone.utc).date()
    series = {}
    for sid, (years, limit, value_range) in SERIES.items():
        obs = fetch(sid, key, years_ago(today, years).isoformat(), limit)
        series[sid] = clean(sid, obs, today, years, value_range)
        print(f"{sid}: {len(series[sid]['dates'])} observations through {series[sid]['dates'][-1]}")

    previous = None
    if args.previous and os.path.exists(args.previous) and os.path.getsize(args.previous) > 0:
        try:
            with open(args.previous) as f:
                previous = json.load(f).get("series")
        except (OSError, ValueError):
            previous = None
    changed = series != previous
    last_date = max(s["dates"][-1] for s in series.values())

    feed = {
        "schema": 1,
        "generated_utc": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "source": "FRED, Federal Reserve Bank of St. Louis",
        "notice": NOTICE,
        "terms": TERMS,
        "series": series,
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(feed, f, separators=(",", ":"), ensure_ascii=False)
    print(f"{'changed' if changed else 'unchanged'} · latest observation {last_date}")

    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(f"changed={'true' if changed else 'false'}\nlast_date={last_date}\n")


if __name__ == "__main__":
    main()
