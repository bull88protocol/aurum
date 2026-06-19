# Central-Bank data — how to keep it current

The Gold Index's **Central Bank Demand** component reads a small public file:

```
data/cb_quarterly.json   →   served at
https://raw.githubusercontent.com/bull88protocol/aurum/master/data/cb_quarterly.json
```

The app **downloads** this file (read-only; nothing about the user is sent) and refreshes it
weekly. **To update the numbers you edit the file — you do NOT ship an app update.** Every
installed phone picks up your change on its next refresh.

---

## The recurring task (~4× a year)

When the World Gold Council publishes a new quarter in **Gold Demand Trends**
(<https://www.gold.org/goldhub> → central banks → *net purchases*, in tonnes):

```bash
cd /home/sun/gold
python3 release-2.0/cb-data/cb_update.py set 2026-Q1 250 --push
```

That validates the number, rewrites `data/cb_quarterly.json`, prints the new live read, and
(`--push`) commits + pushes it. Done — no Android Studio, no Play release. Drop `--push` to
review first and commit yourself.

## Commands

```bash
python3 release-2.0/cb-data/cb_update.py show              # all quarters + the live trailing-12-month read
python3 release-2.0/cb-data/cb_update.py set 2026-Q2 180   # add/replace one quarter (tonnes)
python3 release-2.0/cb-data/cb_update.py rm  2026-Q2       # remove a quarter
python3 release-2.0/cb-data/cb_update.py init              # create the seed file (first time only)
# add --push to set/rm to also git add + commit + push
```

`show` example output:
```
  2025-Q4:   250.0 t
  2026-Q1:   250.0 t
  live read: TTM 1000 t/yr  (= 2025-Q2 + 2025-Q3 + 2025-Q4 + 2026-Q1)  -> CB score 84.0/100
```

## One-time setup

1. The file must live on the **`master`** branch for the URL above to resolve. It gets there
   automatically when you merge `release-2.0` into `master` at the v2.0 release — or push it to
   `master` sooner if you want the feed live during testing.
2. Open the raw URL in a browser to confirm it loads.
3. (If you ever move hosting) the URL is `FEED_URL` in
   `app/src/main/java/com/sun/aurum/network/CentralBankClient.kt` — changing it needs an app release.

## How the app uses it

- **Trailing 12 months.** The CB score is driven by the sum of the **last 4 published quarters**
  (a TTM net-purchase figure), mapped to 0–100 by the engine's tonnage anchors.
- **Publication lag, look-ahead-free.** A quarter only counts once it would realistically be
  published (~6 weeks after quarter-end: Q1→May, Q2→Aug, Q3→Nov, Q4→Feb). So the historical
  chart/CSV never use a number that wasn't public at that date. You can safely add a quarter the
  day WGC prints it.
- **Graceful fallback.** If the file is unreachable, or a date isn't covered by ≥4 quarters, the
  app uses its **bundled annual** WGC series instead — the feature never breaks offline.

## About the seed

`init` fills every quarter with an **even split of the WGC annual total** — a placeholder that
exactly matches the annual basis (so turning the feed on changes nothing at first). To get real
*intra-year* movement, replace the **recent** quarters (last ~1–2 years is plenty) with the
actual WGC quarterly figures via `set`. Old history can stay as the even split — its exact
quarterly split barely moves the index.

## Privacy

This is an anonymous, one-way download of a **public** file: no key, no user data, no tracking.
It's listed in `PRIVACY.md` §3 alongside Yahoo/FRED. The "no backend / we collect nothing about
you" promise is unchanged — the app only ever *reads* this file.
