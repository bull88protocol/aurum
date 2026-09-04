# Aurum88 Protocol v2.6 — Release Notes

## v2.6.0 (versionCode 14)

**Why this release exists — two unrelated reasons that ended up in one build.**

1. **Play's release dashboard for 13 (2.5.0)** raised two "User experience" cards, both real. That
   was the original scope, written 2026-08-20 and committed the same day (`e311087`).
2. **An external audit of the 2026-09-01 daily PDF** (run 2026-09-03) found two genuine data
   defects in the report itself. Since versionCode 14 had been committed but never uploaded — the
   code was therefore unclaimed — the fixes were folded in rather than burning a second version
   (`d7d9b02`). A third fix needed no app release at all and is already live (`e08a03f`).

## What changed

| Area | Before | After |
|---|---|---|
| Settings screen | No toolbar at all; content drew under both system bars | Real toolbar with title + back arrow; insets handled |
| `themes.xml` | `statusBarColor` / `navigationBarColor` set | Both removed — no-ops at targetSdk 36 |
| GLD "Open" in the PDF | Previous close, mislabelled | The real session open |
| AI market brief | Free-floating on grounding snippets | Anchored to the app's own market data |
| Central-bank demand | 858 t/yr (stale, pre-revision) | 794 t/yr (WGC revisions applied) |

---

## Part 1 — edge-to-edge and the Settings toolbar (`e311087`)

Clears both Play "User experience" cards. Full reasoning in
`release-2.5/NEXT_RELEASE_PLAN.md` §1–§2; summary:

- **`themes.xml`** — dropped `android:statusBarColor` and `android:navigationBarColor`. Deprecated
  as of API 35 and outright no-ops at targetSdk 36, where edge-to-edge is enforced and the system
  bars are always transparent. They were the literal subject of the *"deprecated APIs or parameters
  for edge-to-edge"* card. `windowLightStatusBar` / `windowLightNavigationBar` **stay** — not
  deprecated, and still the only thing setting system-icon contrast over `bg_window_gradient` via
  `values-night/bools.xml`.
- **`SettingsActivity`** — had no inset handling, so under enforced edge-to-edge its content drew
  under both bars (the *"may not display for all users"* card). Now pads the toolbar by the
  status-bar inset and the scroll view by the nav-bar inset, matching `MainActivity`.
- That required a toolbar the screen never had: `Theme.Aurum` is `NoActionBar` and nothing called
  `setSupportActionBar()`, so `supportActionBar` was null — the `setDisplayHomeAsUpEnabled()` call
  was a no-op and `onSupportNavigateUp()` could never fire. **No title, no back arrow, ever.**

---

## Part 2 — report data correctness (`d7d9b02`, `e08a03f`)

### 2a. The GLD open was never the open

`YahooFinanceClient.parseQuote` read:

```kotlin
open = meta.optDouble("regularMarketOpen", prevClose)
```

The **v8 chart `meta` block has no `regularMarketOpen`** — that field belongs to the v7 quote API.
Confirmed against the live endpoint. The fallback therefore fired on *every single call* since the
field was introduced: the "Open" tile has never shown anything but a relabelled previous close.

It was invisible on most days because the previous close usually sits inside the day's range. It
only surfaces on a gap — and then it prints an impossible number. On 2026-09-01 the PDF showed
`OPEN $408.42` against a `DAY HIGH $401.25`.

**Fix.** The open now comes from the first non-null bar in `indicators.quote[0].open`, which the
same intraday response already carries, validated against the session's own high/low.
`QuoteData.open` became `Double?`, and the report prints an em dash when no open can be
determined — pre-market there is genuinely no open yet, and a dash is honest where the previous
close is not.

**Blast radius is display-only.** `QuoteData.open` had exactly one consumer
(`ReportContent.kt:150`). No score reads it. The HMAI engine uses `Candle.open` from the real OHLC
arrays and is untouched.

### 2b. The AI brief never saw the app's own numbers

`buildPrompt` passed only the symbol and two session labels, so Gemini free-floated on whatever its
grounding snippets said. The 2026-09-01 brief consequently carried three contradictions:

- a *"critical floor at $4,400"* beneath a stated spot of **$4,369.19** — a floor already broken
- an intraday low of **$4,374.10** above that same **$4,369.19** close — physically impossible
- **−1.5%** for the day against the app's own **−2.86%**, printed two inches higher in the same PDF

**Fix.** The prompt now carries a `VERIFIED MARKET DATA` block built from the app's own
`QuoteData`, plus shared `CONSISTENCY_RULES`: support below price, resistance above, a broken level
described as broken support rather than an intact floor, low ≤ close, high ≥ close, and a
percentage move that matches the verified change even when the model quotes spot rather than the ETF.

Also fixed here: interpolating a multi-line block into a `trimIndent()` raw string **defeats
`trimIndent()`** — the block's lines sit at column 0, dropping the common indent to zero and
leaving every *other* prompt line indented eight spaces. Added `blockIndent()`.

### 2c. Central-bank demand was a quarter stale and pre-revision — **already live, not in this AAB**

`data/cb_quarterly.json` read `asOf: 2026-Q1`, last updated 2026-07-03, giving a trailing-12-month
figure of **858 t/yr**. The World Gold Council has since **revised 2026-Q1 from 244 t to 57 t** and
published **2026-Q2 at 289 t** (H1 2026 = 345 t, the weakest first half since 2022).

| | trailing 4Q | CB component | Gold Index |
|---|---|---|---|
| before | 858 t | 75.5 → prints **75** BULLISH | 45.75 → prints **46** |
| after | 794 t | 71.7 → prints **72** BULLISH | 44.91 → prints **45** |

The label does not change and the headline moves one point — worth correcting for accuracy, not
because it was distorting the index.

**No code changed.** The engine already sums the last four *published* quarters from an
overwritable map, and `cb_update.py` already overwrites revised values — the ingestion design the
audit asked for was the design in place. This was an ops omission: nobody had run the update since
July. Pushed as `e08a03f` and **verified live** at the raw feed URL, so every install picks it up
within the 7-day feed cache regardless of this release.

---

## What the audit recommended and was NOT done

Recorded so a future session does not "helpfully" implement it. The audit's headline
recommendation was to rebuild the Forward Signal around *expected change* in real yields instead of
the real-rate **level**, and to dial the 78 reading down to 60–65.

**Rejected — that is the model that was already built, measured and discarded.**

| factor | train IC | test IC | |
|---|---|---|---|
| **DFII10 level (high = bullish)** — what ships | **+0.42** | **+0.42** | +0.70 at 126d; positive in all four eras |
| Forward signal **v1** (RYΔ / USDΔ / INFΔ / ROC60) — what was proposed | — | **−0.05** | its BEARISH months returned **+3.2%** vs BULLISH **+2.7%** |

Three further specifics:

- *"Include the expected Fed path"* — already shipping as the 0.20 Fed Cycle sleeve, and in the very
  report audited it read **49/100 "Tightening (Bearish)"**, actively dragging the score down.
- *"Dial it to 60–65"* — `research/VALIDATION_2026-07-10.md` §8 already rejected a blow-off/trend
  penalty: *"one +79% episode in 21y; would be curve-fitting a single point."*
- **Sample size.** ~85 independent 3M samples in 21 years; the current miss is 3–4 *overlapping*
  observations. Not enough to recalibrate an out-of-sample +0.42 factor without fitting noise.

**The audit's observation was nonetheless correct, and already on the books.** §4 of that same
validation doc records v2 staying BULLISH through the −24% crash — three consecutive misses — and
names the mechanism: *"DFII10 rising = its failure regime."* Watch item at line 200 stays open;
revisit with COT/positioning data in the next research round, per `research/README.md`.

One structural note: the audit praised the conditions-vs-forward split as *"a very good design
idea"* and then treated 46-vs-78 disagreement as evidence of a bug. The disagreement is the design.

---

## Files

| File | Change |
|---|---|
| `app/src/main/res/values/themes.xml` | two deprecated bar-colour attributes removed |
| `app/src/main/java/com/sun/aurum/ui/SettingsActivity.kt` | toolbar + inset handling |
| `app/src/main/res/layout/activity_settings.xml` | rewrapped around a Toolbar + divider |
| `app/src/main/java/com/sun/aurum/network/YahooFinanceClient.kt` | `resolveOpen()` / `firstBarOpen()`; `OHLC_EPS` |
| `app/src/main/java/com/sun/aurum/network/GeminiClient.kt` | `marketFacts()`, `CONSISTENCY_RULES`, `blockIndent()` |
| `app/src/main/java/com/sun/aurum/data/DataRepository.kt` | passes the quote into the Gemini call |
| `app/src/main/java/com/sun/aurum/report/ReportContent.kt` | null open renders as an em dash |
| `shared/.../model/Models.kt` | `QuoteData.open` → `Double?` |
| `app/src/test/.../GoldReportContentTest.kt` | +2 regression tests |
| `data/cb_quarterly.json` | 2026-Q1 → 57 t, 2026-Q2 → 289 t *(shipped separately)* |

## Verification performed

- `./gradlew :app:testDebugUnitTest` — **55 tests, 0 failures** (53 before; +2 for open rendering).
- `./gradlew :app:bundleRelease` — clean, R8 full mode.
- `jarsigner -verify` → **jar verified**, `CN=Bull88 Protocol, O=CoinTranscend, C=US` (upload key).
- AAB manifest inspected directly: **versionCode 14 · versionName 2.6.0 · `com.sun.aurum`**
  (no debug suffix). Built 2026-09-03 22:10, 4.4 MB.
- **`resolveOpen` verified against the live Yahoo API**, not just by unit test — `org.json` is
  stubbed in Android unit tests, so the logic was ported and run against fresh responses:

  | symbol | resolved open | day range | old behaviour |
  |---|---|---|---|
  | GLD | 410.71 ✅ | 408.54–413.54 | 402.78 — **out of range** |
  | DX-Y.NYB | 99.41 ✅ | 98.83–99.61 | 99.60 — in range by luck |
  | ^VIX | 15.25 ✅ | 14.23–15.44 | 15.20 — in range by luck |

- CB feed confirmed live at the raw GitHub URL: `asOf 2026-Q2`, `2026-Q1: 57.0`, `2026-Q2: 289.0`.

**Not yet verified: anything on a device.** See the checklist below.

## What's new (Play "What's new" copy)

Paste-ready, 448 characters — within Play's 500-character limit.

> Fixes in this update:
> • The daily PDF report showed the previous close where the session's opening price should be — it now shows the real open.
> • The AI market brief is anchored to the app's own price data, so its levels and percentages match the rest of the report.
> • Central-bank demand picks up the World Gold Council's latest revisions.
> • Layout fixes for Android 15 and 16, including a proper title bar and back button on the Settings screen.

## Play upload checklist

**Testing decision (2026-09-03):** verification happens on the **Play-installed build**, not a
debug install. Deliberate, and for this release the stronger choice — Google Sign-In does not work
in the debug variant (`com.sun.aurum.debug` has no OAuth client), the release build is the only one
carrying R8 full mode, and the in-app **Today's Report (PDF)** menu item means the PDF is reachable
on demand without waiting for the 6 PM worker. Cost: each re-spin burns a versionCode, since Play
claims a code permanently on upload even into an unrolled draft (see versionCode 12).

1. ~~`./gradlew :app:bundleRelease`~~ — **done 2026-09-03**, AAB built and verified above.
2. ~~`jarsigner -verify`~~ — **done**, jar verified with the Bull88 upload key.
3. ~~Play Console → Create new release~~ — **done 2026-09-03. Uploaded straight to Production**,
   not to a testing track, at the user's direction. **Submitted and awaiting Play review.**
4. ~~Upload the AAB, paste the "What's new" copy~~ — **done.**

   **Upload snag worth remembering:** the first attempt was rejected with *"Your APK or Android App
   Bundle needs to have the package name com.sun.aurum"*. That was the **debug APK** being picked by
   mistake — `app/build/outputs/apk/debug/app-debug.apk` is `com.sun.aurum.debug` (the
   `applicationIdSuffix` that lets it install alongside the Play build) and sits one directory away
   from the bundle. It is **not** a versionCode problem and bumping the version does not fix it; a
   code collision reads *"Version code 14 has already been used"* instead. The right file is the
   ~4.4 MB `.aab`, not the ~9 MB `.apk`. A rejected upload does not burn the versionCode.
5. **On-device smoke test, from the Play install:**
   - **[open]** Settings screen: title and back arrow present; content clears both system bars;
     rotate and re-check. *This is the highest-risk item — no human has seen this screen since it
     was rewritten.*
   - **[open]** Settings in dark mode: system-icon contrast still legible over the gradient.
   - **[open]** Overflow → **Today's Report (PDF)** → the **Open** tile is a real open, not equal
     to **Prev close**. Best checked on a gap day, when the two genuinely differ.
   - **[open]** AI Brief: no support level quoted above the current price; stated % move matches
     the quote card. **Note the 8-hour `GeminiCache`** — force a refresh or wait for the 6 PM
     worker's `forceGemini` run, or you will be reading the pre-fix brief.
   - **[open]** Google Sign-In still works (Play cert path — the reason this is worth testing here).
   - **[open]** Carry over the two items still open from 2.5.0: notification tap does **not** open
     the app, and Save-to-Downloads / Share both land the file intact.
6. ~~Promote to Production~~ — **superseded: went straight to Production on 2026-09-03.** The
   device pass in step 5 therefore happens *after* users have the build, not before. Do the
   **Settings screen** check the moment the rollout reaches your phone; it is the only item in this
   release that no human has seen render, and the only realistic cause of a versionCode 15.

## Follow-ups (not blocking)

- **`data/cb_quarterly.json` `method` field was flattened** by `cb_update.py`, which regenerates it
  from a generic template. The hand-written provenance note is gone; restore it to record the
  244→57 revision.
- **`CLAUDE.md` points at `cb-data/`; the tool is at `release-2.0/cb-data/`.** Fix the path.
- **`resolveOpen` has no unit test** — `org.json` is stubbed in Android unit tests. Either add a
  JSON test dependency or move the resolution into a pure-Kotlin helper in `:shared`.
- **Store screenshots still stale** — carried from 2.5.0: no screenshot shows the PDF report, and
  `store/screenshots/02_*.png` still pictures the v1 forward card (stale since 2.2).
- **Forward Signal watch item stays open** — see the rejected-changes section above.
