# v2.6.0 — next release plan

> ## ⚠️ SPENT — do not follow this document
> **v2.6.0 shipped: approved and live on Play Production 2026-09-04.** Everything below is the plan
> that produced it, kept for the record. The "execute 2.6.0" trigger phrase it describes is
> **retired** — there is no work left in here.
>
> Two items in §5 and §6 (store screenshots, full-description check) were **deliberately skipped**
> as low value; they need no release and can land any time.
> §4 (the R8 card) was triaged as almost certainly a generic promotion card — the build already runs
> R8 full mode.
>
> **For current state, read `CLAUDE.md` first**, then the release-notes doc for the version in
> flight (`release-2.7/RELEASE_NOTES.md` as of 2026-09-04).

Written 2026-08-20, the day **13 (2.5.0)** was approved and went live on Production.

---

## 0. EXECUTE 2.6.0 — START HERE

**Trigger phrase.** The user opens a fresh session in `/home/sun/gold` and says **"execute 2.6.0"**.
That means: finish and ship v2.6.0. Read this section first, then §1–§4. Everything you need is in
this file — a new session has no memory of the one that wrote it.

**State as of 2026-08-20.** All v2.6.0 **code is written, committed and pushed** (`e311087` on
`master`). `app/build.gradle.kts` already reads `versionCode = 14` / `versionName = "2.6.0"` — do
not bump it again. `assembleDebug` clean, **53 tests / 0 failures**. **Nothing has been verified on
a device and nothing has been uploaded to Play.** There is no code left to write unless §1c turns
something up.

Re-orient with:

```bash
git log --oneline -5          # expect 31957d8, e311087, 0ec48c3 (tagged v2.5.0)
git status --short            # expect clean
grep -nE "versionCode|versionName" app/build.gradle.kts   # expect 14 / 2.6.0
adb devices -l                # is the phone attached?
```

### Ask the user for these three things, up front

Ask for all three in one go at the start — two of them gate everything else, and waiting until you
hit each one wastes a round trip.

**1. Plug the phone in.** *(blocking — this is the gate before any AAB)*
The Pixel 8a, USB, unlocked, adb authorized. `release-2.0/RESUME.md` records the serial as
`44251JEKB01464`; re-check with `adb devices -l` rather than trusting it. Needed for §1c, which is
the whole reason to be careful here: v2.6.0 changes the look of a `FLAG_SECURE` screen and **none
of it has been seen by a human**. Also needed for the §5 screenshots.
Install non-destructively — the debug variant is `com.sun.aurum.debug` and sits **alongside** the
Play build, so the stored API keys are never at risk (`allowBackup="false"` means an uninstall
would destroy them permanently). adb recipes are in `CLAUDE.md` → "On-device testing without
destroying the Play install".

**2. Two lookups in the Play Console.** *(blocking for §4 and §6 only — not for §1c)*
- **§4** — Release dashboard for `13 (2.5.0)` → expand *"Improve your app's memory and performance
  with R8 optimization"* and paste what it actually says. The build already has R8 full mode on, so
  there is nothing to change until that text says otherwise. **Do not write code against this card
  without reading it first.**
- **§6** — Grow users → Main store listing → Full description. Confirm the live text carries the
  post-close PDF bullets and not the old "market-open notification" line.

**3. Approve the "What's new" copy.** *(not blocking — draft it and show them)*
≤ **500 characters**, Play truncates silently past that. v2.6.0 is fix-only, so one or two lines is
right. Do **not** reuse v2.5.0's copy — that described the PDF report, which these users already
have. Something like: *"Layout fixes for Android 15 and 16, including the Settings screen, which
now has a proper title bar and back button."*

### What does NOT need the user

- `keystore.properties` and `/home/sun/keystores/bull88-upload.jks` are both **present on this
  box** — `./gradlew :app:bundleRelease` works unaided. (Linux box only: `source
  /home/sun/option_android/android_env.sh` first.)
- All the code, the tests, and drafting the release notes.

### Order of operations

1. Ask for the three things above.
2. `assembleDebug` → install to `com.sun.aurum.debug` → **run the §1c checklist**. This is the gate.
3. Fix whatever §1c turns up; re-verify.
4. §4 once the user pastes the card text; §6 is a yes/no they report back.
5. §5 screenshots while the phone is still attached — the PDF-report shot has been deferred once
   already and the store page is now public.
6. `bundleRelease` → `jarsigner -verify` → upload to **closed testing first**, never straight to
   Production. Full checklist at the end of this file.
7. Commit + **tag** the release (`v2.5.0` is the precedent), update `CLAUDE.md` status, push.

### Do not

- **Do not roll out versionCode 12.** It is a burned code holding the old 9 AM every-day build.
- **Do not** add a `Co-Authored-By:` / Claude trailer to commits — attribution is `aurum88p`.
- **Do not** promote to Production before closed testing sees the edge-to-edge change on a device.

---

## Where things stand

| | |
|---|---|
| **Production track** | `13 (2.5.0)` — **approved and live** 2026-08-20, full rollout, 177 countries / regions |
| **Previous production** | `6 (2.0.0)` — upgrading users jump five releases at once |
| **versionCode 12** | Consumed by a draft upload. **Never roll it out** — it is the 9 AM / every-day build |
| **Next free code** | **14** |
| **Repo** | v2.5.0 committed and tagged `v2.5.0` on `master` 2026-08-20 — §7 is done |

With 13 live, Play's target-API-36 warning is cleared (internal/closed testing never cleared it —
only a Production release targeting 36 does).

---

## Version for this release

**`versionName 2.6.0` / `versionCode 14`** — decided 2026-08-20.

Everything in scope below is a display-layer or tooling fix, so 2.5.1 was the other candidate. The
minor bump follows the project's own precedent: v2.4.0 took a minor for a change that was likewise
display-layer only (Settings key masking). Settled — the versionName is baked into the AAB.

---

## Scope

> **Implementation status 2026-08-20:** §1a, §1b, §2 and §3 are **written and committed**;
> `assembleDebug` clean, **53 tests / 0 failures**. §1c (on-device verification) is **not done** —
> no device was attached to this box. §4 is still open and needs the Console.

### 1. Edge-to-edge — the reason this release exists

Clears **both** "User experience" cards on the Play release dashboard for 13 (2.5.0):

- *Your app uses deprecated APIs or parameters for edge-to-edge*
- *Edge-to-edge may not display for all users*

The app targets API 36, where edge-to-edge is **enforced** — the app draws behind the system bars
whether or not it opts in, and the old bar-colouring attributes are ignored.

**1a. ✅ Done — `app/src/main/res/values/themes.xml`, the two lines deleted**

```xml
<item name="android:statusBarColor">@color/background</item>
<item name="android:navigationBarColor">@color/background</item>
```

Both are deprecated as of API 35 and are **no-ops** at targetSdk 36. They are card #2, precisely.

**Keep the two lines below them.** `android:windowLightStatusBar` and
`android:windowLightNavigationBar` are *not* deprecated and still control system-icon contrast under
edge-to-edge. They read `@bool/light_system_bars`, which has a `values-night/` variant — that
day/night icon switching is the only thing still making the bars legible over
`@drawable/bg_window_gradient` once the colour attributes are gone.

**1b. ✅ Done — `app/src/main/java/com/sun/aurum/ui/SettingsActivity.kt`, inset handling added**

This is card #1. `MainActivity.kt:55-68` already handles insets carefully — the comment there
records the exact bug (toolbar drawing under the status bar, overflow menu landing in the system
pull-down zone and becoming untappable). **`SettingsActivity` has none of that.** It inherits
`Theme.Aurum` from `<application>`, and no layout in the project uses `fitsSystemWindows`, so its
content draws under both bars on Android 15/16.

Because §2 resolved to adding a real toolbar, the inset lands on the toolbar and the scroll view
rather than on a bare root — the toolbar's surface colour then fills the status bar, matching
MainActivity:

```kotlin
ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    val ime  = insets.getInsets(WindowInsetsCompat.Type.ime())
    binding.toolbar.updatePadding(top = bars.top)
    binding.settingsScroll.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
    insets
}
```

Imports: `androidx.core.view.ViewCompat`, `androidx.core.view.WindowInsetsCompat`,
`androidx.core.view.updatePadding` — all already used in `MainActivity`, no new dependency.

**1c. ⚠️ NOT DONE — verify on-device.** This is UI-only; there is nothing to unit-test, and no
device was attached when the code was written. **This is the gate before building the AAB.** On the
Pixel 8a (Android 15+), check both screens in light **and** dark mode, portrait and landscape:

- [ ] Main: toolbar title + overflow still clear of the status bar; tagline footer still clear of
      the nav bar (i.e. no regression from deleting the theme attrs)
- [ ] Settings: new toolbar not under the status bar, Terms row not under the nav bar
- [ ] Settings: the back arrow appears, is accent-coloured, and returns to Main
- [ ] Settings: tap the Gemini key field — it stays visible with the keyboard up (§3)
- [ ] System bar icons legible over the gradient in both themes
- [ ] Gesture-nav **and** 3-button nav

> Optional, same edit: `MainActivity` uses `Type.systemBars()`, which excludes `displayCutout()`.
> On a notched device in landscape, content can still run under the cutout. Low priority — the
> Pixel 8a's cutout is centred in the status bar, so `systemBars()` already covers it in portrait.

---

### 2. ✅ Done — Settings toolbar restored

Found while scoping §1b. `SettingsActivity.kt:41` runs:

```kotlin
supportActionBar?.apply { title = "Settings"; setDisplayHomeAsUpEnabled(true) }
```

`Theme.Aurum` extends `Theme.MaterialComponents.DayNight.NoActionBar`, `SettingsActivity` never
calls `setSupportActionBar()`, and `activity_settings.xml` contains no `Toolbar`. So
`supportActionBar` is **null**, the `?.` swallows it, and the whole line is dead: the screen has no
title and no up affordance. Users get back only via the system back gesture.

**Resolved 2026-08-20: option (a), add a real toolbar.** Confirming the intent, the class *already*
overrides `onSupportNavigateUp(): Boolean { finish(); return true }` at what is now line 216 — the
whole screen was written for a toolbar that was never supplied.

`activity_settings.xml` was rewrapped: the root is now a vertical `LinearLayout` holding a
`Toolbar` (`wrap_content` + `minHeight="?attr/actionBarSize"`, so the status-bar padding grows it
instead of squashing its content), the gold divider from MainActivity's app bar, and the original
`ScrollView` (now `@id/settingsScroll`, `layout_weight="1"`, `clipToPadding="false"`). Body content
is unchanged apart from one level of indentation. `setSupportActionBar(binding.toolbar)` now runs
before the `supportActionBar?.apply`, so the title, the back arrow and `onSupportNavigateUp()` are
all live for the first time.

**This is a visible UI change on a screen that handles API keys — it is the main reason §1c
matters.** `FLAG_SECURE` is untouched, so it still will not screenshot; verify by eye.

---

### 3. IME insets on the Settings screen

`activity_settings.xml` holds **2 `EditText` fields** (the API keys), and `SettingsActivity` has no
`android:windowSoftInputMode` in the manifest — `MainActivity` has `adjustResize`, Settings does
not. Under enforced edge-to-edge, the keyboard can cover the field being typed into.

**✅ Handled in the §1b listener, no manifest change.** `adjustResize` would not have fixed this:
at targetSdk 35+ it no longer resizes an edge-to-edge window. Instead the listener takes
`maxOf(navBar.bottom, ime.bottom)`, so the scroll view gains real room when the keyboard opens.

- [ ] Still verify on device — tap the Gemini key field and confirm it stays visible with the IME up

---

### 4. R8 recommendation — triage only, probably no code change

Third card: *Improve your app's memory and performance with R8 optimization* (Technical quality).

**The build already does this.** `app/build.gradle.kts` release block has `isMinifyEnabled = true`,
`isShrinkResources = true`, and `proguard-android-optimize.txt`; `gradle.properties` does **not**
set `android.enableR8.fullMode=false`, and AGP 8.10 defaults full mode **on**. So R8 full mode is
already running on the shipped bundle.

- [ ] **Expand the card in the Console and read what it actually detected.** Do this before writing
      any code — the current config leaves nothing obvious to change, so either it is a generic
      promotion card or it names something specific worth seeing.
- [ ] If it turns out to be real, the likely surface is `app/proguard-rules.pro` (11 lines, mostly
      `-dontwarn`) or the `:shared` KMP module's contribution.

---

## Store-side — no release required, can land any time

### 5. Screenshots deferred from 2.5.0

Consciously skipped for the production push. Both items from `store/PLAY_STORE_v2.5.0.md` §4 are
still open, and now the store page is public:

- [ ] **A screenshot of the PDF report** — the expanded notification with Save/Share, or page 1 of
      the PDF in a viewer. The headline feature of 2.5.0 is still not pictured anywhere.
- [ ] **Re-shoot `store/screenshots/02_*.png`** — it still shows the **v1 forward card**, stale
      since 2.2.

Play requires 1080×2160 or similar at ≤ 2:1. Store-listing changes go through their own review, so
these can ship without waiting for 14.

### 6. Confirm the full-description edit actually landed

`store/STORE_LISTING.md` was edited locally to replace

> • Daily background refresh and a market-open notification

with

> • A daily post-close report delivered as a PDF — read, save or share it from the notification
> • Daily background refresh, so the report is ready before you open the app

- [ ] Open **Grow users → Main store listing → Full description** and verify the live text matches.
      If the old line is still there, the store page is describing a feature 2.5.0 removed.

---

## Repo hygiene — DONE 2026-08-20

### 7. Commit the v2.5.0 tree ✅

`versionCode 13` was approved and live on Production with **no commit behind it** — the shipped
artifact was unreproducible. Now committed and tagged **`v2.5.0`** on `master`: the `app/report/`
package, its tests, `DailyRefreshScheduleTest`, the manifest / worker / menu / ViewModel changes,
`release-2.5/`, `store/PLAY_STORE_v2.5.0.md`, and the `store/STORE_LISTING.md` description fix.
`53 tests, 0 failures` verified with `--rerun-tasks` immediately before the commit.

This is the **first tagged release in the repo** — no tags existed for 2.0–2.4. Tag future releases
at the commit whose AAB was uploaded, not at whatever `master` happens to be later.

### 8. Untracked scratch ✅

Added to `.gitignore`: `plan.md` and `session.sh` (pasted session transcripts, not source, despite
the `.sh` name), `prod_gold.png` (Console screenshot), `gold_index_history.csv` (the app's own CSV
export pulled off a device — 5,396 rows, regenerate from the app, and note it is *not* what
`EngineHistoryDumpTest` produces; that writes to the already-ignored `research/cache/engine/`).

`claude.sh` was tracked by mistake — it too is a pasted transcript rather than a script.
`git rm --cached` in v2.6.0 untracks it and it now sits in `.gitignore` with the other two. **The
file stays on disk; only its history stops there.** It remains in the history up to `v2.5.0`, so
anything that needs it can still be recovered from the tag.

Also ignored: `.claude/settings.local.json` — per-machine Claude Code state, and `CLAUDE.md` is
already the cross-machine source of truth.

### 9. Stale docs ✅

- `release-2.4/RELEASE_NOTES.md` — its "AAB and Play upload still to do" line is corrected; v2.4.0
  was uploaded and installed from Play on 2026-08-08.
- `CLAUDE.md` — now records 13 (2.5.0) as **live on Production**, the target-API-36 warning as
  cleared, and the two open screenshot items.
- `CLAUDE.md` test count was **wrong in two places** (45) and is now **53** — it had never counted
  the 8 `DailyRefreshScheduleTest` cases that shipped with 2.5.0.

---

## Deferred verification carried over from 2.5.0

`store/PLAY_STORE_v2.5.0.md` §5 step 5 lists smoke tests that were **never run** — the PDF has been
rendered and inspected on a Pixel 8a, but these specific paths have not:

- [ ] **AI Brief** and **News** sections of the PDF with real Gemini content (needs a live key)
- [ ] **Save PDF** action → file lands in Downloads and opens
- [ ] **Share** action → chooser fires, receiving app gets a valid PDF
- [ ] v2.4.0's own smoke items, if they were never run either (§5 step 6)

These are for the **shipping** build, so they are worth running against 13 from Production as soon
as it is live — not held for 14. Recipes are in `CLAUDE.md` → "On-device testing without destroying
the Play install".

---

## Explicitly out of scope

- **API 37 / AGP ≥ 8.13 or 9.x / Kotlin 2.x.** AGP 8.10 tops out at API 36. The next target bump is
  a feature-branch job, and there is no deadline pressure until Play announces the API 37 date.
- **iOS Phase 2.** Parked; needs a Mac. `ios/APPLE_RELEASE_PLAN.md`.
- **Engine or index changes.** The 2026-07-10 audit reproduced every claim; nothing is pending.

---

## Build & release checklist for 14

```bash
source /home/sun/option_android/android_env.sh
./gradlew :app:testDebugUnitTest      # 53 tests must stay green (Gold Index 19 + HMAI 10 + report 15 + schedule 8 + 1)
./gradlew :app:assembleDebug          # installs as com.sun.aurum.debug, alongside the Play build
# ... on-device verification per §1c, §2, §3 ...
./gradlew :app:bundleRelease          # needs keystore.properties
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
```

- [x] Bump `versionCode` to **14** and `versionName` to **2.6.0** in `app/build.gradle.kts`
- [ ] Upload to **closed testing** first — do not promote straight to Production. Edge-to-edge
      changes are exactly the kind that look fine on one device and wrong on another
- [ ] Write "What's new" — ≤ **500 characters**, Play truncates silently. For a fix-only release,
      one line is right ("Layout fixes for Android 15 and 16, including the Settings screen.")
- [ ] Promote to Production once verified

> **Do not roll out versionCode 12** at any point in this process.

---

## Open questions

1. **§2 — toolbar on Settings: restore it (a) or delete the dead line (b)?** Changes §1b's target.
2. **§4 — what does the R8 card actually say** when expanded? Determines whether it is in scope.
3. **§8 — keep or ignore** `gold_index_history.csv`, `session.sh`, `claude.sh`?
4. ~~2.5.1 or 2.6.0?~~ **Resolved 2026-08-20: 2.6.0 / versionCode 14.**
