# RESUME HERE — Aurum88 v2.0

**Start-here doc for picking the v2.0 work back up.** Read this, then `CHANGELOG.md` (what shipped,
in order) and `NEXT_RELEASE_PLAN.md` §0.5 (the done/remaining status table).

_Last updated: 2026-06-19._

---

## Where things stand

- **Branch:** `release-2.0` (cut from `master` @ 1.3.0-beta / versionCode 5). **`master` is frozen**
  on the Play-submitted build — do all v2.0 work on `release-2.0`.
- **Head commit:** `dc8e540` (P2-4). 14 commits on the branch.
- **Build status:** `assembleDebug` clean; **13/13** JVM engine tests green
  (`./gradlew :app:testDebugUnitTest`).
- **On the test phone (adb `44251JEKB01464`):** the v2.0 **debug** build (versionCode 5) is
  installed in-place and verified — Gold + Dollar tabs, CB freshness label, FRED/Gemini keys
  migrated, edge-to-edge top/bottom fixed.

## Done (all verified on-device)
All **P0** (1,2,3), all **P1** (1,2,4; P1-3 optional), **P2-2, P2-3, P2-4, P2-5**, plus an
edge-to-edge inset fix. See the status table in `NEXT_RELEASE_PLAN.md` §0.5 for per-item commits.

## Remaining (all minor / optional)
1. **P2-5 cleanups** — dedup the duplicated GLD fetch block in `DataRepository` (`fetchAll` vs
   `fetchSymbol`), a branded notification icon (currently `android.R.drawable.ic_dialog_info`), and
   the `GoldIndexChartView` x-axis timezone (device-local vs ET).
2. **P2-1** — broaden engine tests (currently 13).
3. **Optional:** P1-3 (move price/technicals from the GLD ETF to spot XAU), P1-5 (RSI mean-reversion
   philosophy + RY/USD window asymmetry notes), P2-3 follow-up (Credential Manager migration + drop
   the `GET_ACCOUNTS` permission — needs an on-device auth test).
3. **Drop the `security-crypto` dependency** once testers have upgraded past the P2-4 build (it's
   now migration-read-only — see `app/build.gradle.kts`).

## Operational items (owner — not code)
- **Publish `data/cb_quarterly.json` to `master`** so the live CB feed resolves (happens
  automatically on the v2.0 merge, or push sooner). Then replace the placeholder quarters with real
  WGC numbers via `release-2.0/cb-data/cb_update.py` (see `cb-data/README.md`).
- Add a **Dollar-tab store screenshot** to the Play listing.
- Smoke-test **Google sign-in** once under the new `drive.file` scope (P2-3).
- When 1.3.0-beta clears Play: **merge `release-2.0` → `master`**, bump to **2.0.0** (versionCode 6).

---

## How to build / install / verify on the phone

```bash
cd /home/sun/gold
source /home/sun/option_android/android_env.sh
./gradlew :app:assembleDebug            # build
./gradlew :app:testDebugUnitTest        # 13 engine tests
adb -s 44251JEKB01464 install -r app/build/outputs/apk/debug/app-debug.apk   # in-place (debug-signed; preserves data)
```

On-device verification notes (debug build):
- The app's **biometric gate blacks out `adb screencap`**; `adb shell uiautomator dump` reads the
  view tree through it. To get a clean screenshot, bypass the gate by marking the session valid:
  ```bash
  NOWMS=$(($(date +%s)*1000)); adb -s 44251JEKB01464 shell am force-stop com.sun.aurum
  printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n    <long name="last_auth_ms" value="%s" />\n</map>\n' "$NOWMS" \
    | adb -s 44251JEKB01464 shell run-as com.sun.aurum tee shared_prefs/biometric_session.xml >/dev/null
  ```
- The phone re-locks fast; `adb shell svc power stayon true` (reset to `false` after) keeps it awake.
- Yahoo endpoints can be probed directly with a browser User-Agent to sanity-check what the app
  should show.

---

## To resume in a new window

Open a new session in `/home/sun/gold` and say something like:

> **"Resume the Aurum88 v2.0 work. Read `release-2.0/RESUME.md` and `release-2.0/CHANGELOG.md` for
> where we left off, then continue with the P2-5 cleanups (or tell me what's next)."**

That points the new session at this doc + the changelog so it can pick up with full context.
