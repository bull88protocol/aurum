# RESUME HERE — Aurum88 v2.0

**Start-here doc for picking the v2.0 work back up.** Read this, then `CHANGELOG.md` (what shipped,
in order) and `NEXT_RELEASE_PLAN.md` §0.5 (the done/remaining status table).

_Last updated: 2026-06-24._

---

## Where things stand

- **Branch:** `release-2.0` (cut from `master` @ 1.3.0-beta / versionCode 5). **`master` is frozen**
  on the Play-submitted build — do all v2.0 work on `release-2.0`. **Now pushed to GitHub**
  (`origin/release-2.0`, tracking set 2026-06-24) — the branch is public on `bull88protocol/aurum`,
  so the CB feed file resolves at the `release-2.0` raw URL, but the app reads the **`master`** URL,
  which stays 404 until the v2.0 merge.
- **Head commit:** the **v2.0.0** version-bump commit (versionName 2.0.0 / versionCode 6).
  `master` was fast-forwarded to this same tip, so **`master` == `release-2.0`** now. Both pushed.
- **Build status:** `assembleDebug` clean at versionCode 6; **23/23** JVM engine tests green —
  Gold Index 13 + HMAI 10 (`./gradlew :app:testDebugUnitTest`).
- **On the test phone (adb `44251JEKB01464`):** the `c635f82` v2.0 **debug** build (versionCode 5)
  is installed in-place and verified — Gold + Dollar tabs, CB freshness label, FRED/Gemini keys
  migrated, edge-to-edge top/bottom fixed, and the daily notification now shows the branded
  `ic_notification` (confirmed via `dumpsys notification`: `pkg=com.sun.aurum id=0x7f0800b2`).

## Done (all verified on-device)
All **P0** (1,2,3), all **P1** (1,2,4; P1-3 optional), **P2-2, P2-3, P2-4, P2-5**, an edge-to-edge
inset fix, and the **P2-5 cleanups** (`DataRepository` dedup, branded notification icon, chart
timezone). See the status table in `NEXT_RELEASE_PLAN.md` §0.5 for per-item commits.

## Ship status (2026-06-24) — merged to master, v2.0.0 / versionCode 6

**Done:** `release-2.0` is bumped to **versionName 2.0.0 / versionCode 6** and **merged into `master`**
(fast-forward — master had not diverged). Both branches pushed to `origin`. This landed
`data/cb_quarterly.json` **and** `CentralBankClient` on `master`, so the CB feed URL now resolves
(HTTP 200) instead of 404-ing.

**Still required to actually reach users (owner steps — not code):**
1. **Smoke-test Google sign-in under the new `drive.file` scope (P2-3)** — the one real regression
   risk; the sync-Sheet flow must still read/write under the narrowed scope. On-device. Do before
   uploading to Play.
2. **Build the signed v2.0 release AAB and upload to Play** (the keystore + `keystore.properties`
   secrets live on the owner's machine). WGC data only reaches users once this build ships — the
   merge just made the feed *URL* live; the live 1.3.0-beta has no `CentralBankClient` to read it.

**Deferred to v2.1 / safe to ignore (none block the ship):**
- **P2-3+b Credential Manager migration** — deprecated `GoogleSignIn` still works; risky API swap.
- **Drop `security-crypto`** — gated on testers upgrading past the P2-4 build (migration-read-only;
  alpha dep ships fine — see `app/build.gradle.kts`).
- **P2-1 more tests** — now **23** (Gold Index 13 + HMAI 10); HMAI `CircuitBreaker`,
  `Pillar1Technical`, and the `compute` contract are covered. Still open: HMAI Pillars 2–6 and the
  `TechnicalIndicators` math.
- **P1-5 trader micro-notes** — copy polish.
- **Real WGC quarter granularity** — placeholder is annual÷4, so the trailing-12-month sum the engine
  uses is already right; push real quarters anytime via `cb-data/cb_update.py … --push` (no app
  release — see `cb-data/README.md`).
- **Dollar-tab store screenshot** — Play-listing nicety, addable post-publish.

**Dropped:** **P1-3** (spot XAU) — decided 2026-06-24; GLD is fine.

_Done since last note:_ P2-5 follow-up (DX-Y.NYB candles fetched once per batch + shared),
`HmaiEngineTest` (engine tests 13 → 23), dropped the vestigial `GET_ACCOUNTS` permission, bumped to
**2.0.0 / versionCode 6**, and **merged `release-2.0` → `master`** (pushed). Remaining: the on-device
sign-in smoke test, then build + upload the signed v2.0 AAB to Play.

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
