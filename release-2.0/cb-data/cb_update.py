#!/usr/bin/env python3
"""
Aurum88 Protocol — Central-Bank quarterly feed updater.

Edits  data/cb_quarterly.json  — the hosted WGC net-purchase series the app downloads
(read-only; no user data is involved). Update the FILE to refresh the app's Central-Bank
component; no app release is needed (every install picks it up on its next weekly refresh).

Usage:
  python3 cb_update.py show                 # print all quarters + the live trailing-12-month read
  python3 cb_update.py set 2026-Q1 250      # add/replace one quarter (tonnes), then re-print
  python3 cb_update.py rm  2026-Q1          # remove a quarter
  python3 cb_update.py init                 # create the seed file if it doesn't exist

Add  --push  to set/rm/init to also git add + commit + push the data file:
  python3 cb_update.py set 2026-Q1 250 --push

Where to get the number: WGC "Gold Demand Trends" -> central banks -> net purchases for the
quarter, in tonnes (https://www.gold.org/goldhub). Enter it once the quarter is published.
"""
import datetime
import json
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA = os.path.join(ROOT, "data", "cb_quarterly.json")
QKEY = re.compile(r"^(\d{4})-Q([1-4])$")

# WGC annual net central-bank purchases (tonnes). Used only to seed `init` as an even split;
# replace the recent quarters with real WGC quarterly figures to get true intra-year movement.
ANNUAL = {
    2009: -34, 2010: 79, 2011: 481, 2012: 544, 2013: 409, 2014: 584, 2015: 580, 2016: 395,
    2017: 379, 2018: 656, 2019: 605, 2020: 255, 2021: 463, 2022: 1082, 2023: 1037,
    2024: 1045, 2025: 1000,
}

# Mirror of GoldIndexEngine.cbScoreFromTonnes so `show` previews the 0-100 score the app derives.
ANCHORS = [(-100, 10), (0, 28), (300, 45), (500, 55), (800, 72), (1100, 90), (1300, 95)]


def qkey_sort(k):
    m = QKEY.match(k)
    return (int(m.group(1)), int(m.group(2)))


def score_from_tonnes(t):
    if t <= ANCHORS[0][0]:
        return ANCHORS[0][1]
    if t >= ANCHORS[-1][0]:
        return ANCHORS[-1][1]
    for (x0, y0), (x1, y1) in zip(ANCHORS, ANCHORS[1:]):
        if x0 <= t <= x1:
            return round(y0 + (y1 - y0) * (t - x0) / (x1 - x0), 1)
    return 50.0


def published_asof(key, y, m):
    qy, qq = qkey_sort(key)
    pub_month = qq * 3 + 2
    pub_year = qy
    if pub_month > 12:
        pub_month -= 12
        pub_year += 1
    return pub_year < y or (pub_year == y and pub_month <= m)


def trailing_ttm(quarterly, y, m):
    pub = sorted((k for k in quarterly if published_asof(k, y, m)), key=qkey_sort)
    if len(pub) < 4:
        return None, pub
    last4 = pub[-4:]
    return sum(quarterly[k] for k in last4), last4


def load():
    if not os.path.exists(DATA):
        return None
    with open(DATA) as f:
        return json.load(f)


def save(quarterly):
    keys = sorted(quarterly, key=qkey_sort)
    doc = {
        "source": "World Gold Council — Gold Demand Trends (central-bank net purchases)",
        "unit": "tonnes",
        "method": "Replace quarters with WGC actuals as published; init seeds an even split of "
                  "WGC annual totals (a placeholder that matches the annual basis).",
        "updated": datetime.date.today().isoformat(),
        "asOf": keys[-1] if keys else None,
        "quarterly": {k: round(float(quarterly[k]), 1) for k in keys},
    }
    os.makedirs(os.path.dirname(DATA), exist_ok=True)
    with open(DATA, "w") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
        f.write("\n")
    return doc


def show(quarterly):
    today = datetime.date.today()
    keys = sorted(quarterly, key=qkey_sort)
    print(f"data/cb_quarterly.json — {len(keys)} quarters "
          f"({keys[0] if keys else '-'} … {keys[-1] if keys else '-'})")
    for k in keys[-8:]:
        flag = "" if published_asof(k, today.year, today.month) else "  (not yet published)"
        print(f"  {k}: {quarterly[k]:>7.1f} t{flag}")
    ttm, last4 = trailing_ttm(quarterly, today.year, today.month)
    if ttm is None:
        print("  live read: <4 published quarters -> app falls back to the bundled annual series")
    else:
        print(f"  live read: TTM {ttm:.0f} t/yr  (= {' + '.join(last4)})  -> CB score "
              f"{score_from_tonnes(ttm)}/100")


def git_push():
    rel = os.path.relpath(DATA, ROOT)

    def git(*a, **k):
        return subprocess.run(["git", "-C", ROOT, *a], **k)

    git("add", rel)
    # Re-entered the same value? Nothing is staged — don't error out.
    if git("diff", "--cached", "--quiet", "--", rel).returncode == 0:
        print("Nothing to commit — the file already has these values.")
        return
    if git("commit", "-m", f"Update CB feed ({rel})", "--", rel).returncode != 0:
        print(f"commit failed; run it manually:  git commit -- {rel}")
        return
    branch = git("rev-parse", "--abbrev-ref", "HEAD", capture_output=True, text=True).stdout.strip()
    if git("push").returncode == 0:
        print("✓ committed + pushed.")
    else:
        print(f"✓ committed locally on '{branch}', but push didn't run (no upstream yet).")
        print(f"  The app reads the data file from the 'master' branch, so it goes live when")
        print(f"  '{branch}' reaches master (e.g. the v2.0 merge). To push this branch now:")
        print(f"    git push -u origin {branch}")


def main():
    args = [a for a in sys.argv[1:] if a != "--push"]
    push = "--push" in sys.argv
    cmd = args[0] if args else "show"

    if cmd == "init":
        if load() is not None:
            print(f"{DATA} already exists — use `set`/`rm` to edit it.")
            return
        q = {}
        for year, total in ANNUAL.items():
            for quarter in range(1, 5):
                q[f"{year}-Q{quarter}"] = round(total / 4.0, 1)
        q["2026-Q1"] = round(ANNUAL[2025] / 4.0, 1)   # current-year placeholder until WGC Q1 prints
        save(q)
        print(f"seeded {DATA} (even split of WGC annual totals — replace recent quarters with actuals)")
        show(q)
        if push:
            git_push()
        return

    quarterly = {}
    doc = load()
    if doc:
        quarterly = {k: float(v) for k, v in doc.get("quarterly", {}).items() if QKEY.match(k)}

    if cmd == "show":
        if not quarterly:
            print("No data file yet — run:  python3 cb_update.py init")
        else:
            show(quarterly)
        return

    if cmd == "set":
        if len(args) < 3 or not QKEY.match(args[1]):
            sys.exit("usage: cb_update.py set YYYY-Qn <tonnes>   e.g.  set 2026-Q1 250")
        key = args[1]
        try:
            tonnes = float(args[2])
        except ValueError:
            sys.exit(f"tonnes must be a number, got '{args[2]}'")
        if not -500 <= tonnes <= 2500:
            sys.exit(f"{tonnes} t looks out of range (expected ~ -500..2500); refusing.")
        quarterly[key] = tonnes
        save(quarterly)
        print(f"set {key} = {tonnes} t")
        show(quarterly)
        if push:
            git_push()
        return

    if cmd == "rm":
        if len(args) < 2 or args[1] not in quarterly:
            sys.exit(f"nothing to remove at '{args[1] if len(args) > 1 else ''}'")
        del quarterly[args[1]]
        save(quarterly)
        print(f"removed {args[1]}")
        show(quarterly)
        if push:
            git_push()
        return

    sys.exit(f"unknown command '{cmd}'. Try: show | set | rm | init")


if __name__ == "__main__":
    main()
