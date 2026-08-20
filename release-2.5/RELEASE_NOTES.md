# Aurum88 Protocol v2.5 — Release Notes

## v2.5.0 (versionCode 13)

**Why this release exists:** the daily notification was a pointer, not a deliverable, and it fired
at the wrong time of day.
Tapping it launched `MainActivity`, which re-ran the whole fetch — Yahoo quotes, the DXY series,
three FRED series, the CB feed, and a fresh Gemini call — to redraw data the worker had
*already* fetched minutes earlier. The user waited through a full refresh to read a report that
existed the moment the notification fired.

v2.5.0 makes the report a real artifact. The worker now renders the day's data to a **PDF** while
it is still in hand, and the notification hands that file over directly: tap to read it, or save it
to Downloads — **without opening the app and without refetching anything**.

## What changed

| | Before | After |
|---|---|---|
| Send time | 9:00 AM ET, every day | **6:00 PM ET, weekdays only** |
| Daily worker | fetch → cache → notify | fetch → cache → **render PDF** → notify |
| Notification tap | `MainActivity` + full refetch | **opens the PDF** in the device viewer |
| Notification text | "…are ready — tap to view" | **the numbers** — as actually posted on 2026-08-09: `Index 63/100 MIXED · Outlook BULLISH · Gold $398.47 +2.26%` |
| Notification actions | none | **Save PDF** (→ Downloads) · **Share** |
| Getting the report on demand | — | overflow menu → **Today's Report (PDF)** |

### Why the send moved from 9 AM to 6 PM ET

At 9 AM everything the report is built from was a full day old. Nothing it reads changes between the
4 PM close and the next one:

| Input | Final at | At a 9 AM send you got |
|---|---|---|
| GLD daily bar → Technicals + the index history point | 4:00 PM ET | yesterday's |
| FRED `DFII10` / `DGS2` / `T10YIE` — H.15 posts **4:15 PM ET** | ~4:15 PM ET | yesterday's |
| DXY daily bar → USD component | ~5:00 PM ET | yesterday's |
| GLD quote in the header | live | today's pre-market |

So the index and **four of the scored components** (Real Yields, Inflation, Real-Rate Regime, Fed
Cycle) were a day behind, under a heading that read *"today's conditions"*. 6 PM is the first hour
at which all of it is final — and it is also exactly when CME gold reopens for the next trading day,
so the same instant is both the close of one gold day and the start of the next.

6 PM rather than 5 PM (the literal COMEX close) because 5:00-6:00 PM is the CME maintenance break,
nothing trades in it, and the 4:15 PM H.15 release needs a little time to reach FRED.

**Weekends are skipped.** Saturday and Sunday close no US session, so a weekend send just repeated
Friday's report under a weekend date — observed during testing on Sunday 2026-08-09, which produced
Friday the 7th's data. `nextSendTimeMs` rolls a weekend target forward to Monday.

**`WORK_NAME` is deliberately still `aurum_9am_refresh`.** It is an opaque unique-work key; renaming
it would leave the old 9 AM work enqueued on upgraded installs *and* add a second one under the new
name — two reports a day. Keeping the key means `ExistingWorkPolicy.REPLACE` swaps the old schedule
for the new one. Upgraded installs that have not yet opened the app self-correct after at most one
more 9 AM firing, because `doWork()` re-schedules through the new code.

The AI brief needed no change: `GeminiClient.getTradingSessionDates()` already pivots at 4 PM ET, so
a 6 PM run automatically frames itself as *today's recap / tomorrow's outlook* instead of
*yesterday's recap / today's outlook*.

### What's in the PDF

It is the gold report, not a summary of it — the same content the app's three gold tabs show, in
one A4 document:

1. **Quote header** — price, change, session state (pre / regular / after-hours with the right
   reference close), day range, open, previous close, volume, fetch time in ET.
2. **Gold Index** — composite score and conditions label, the history chart with the same
   WEAK / MIXED / HOT zone bands as the in-app chart, then every component with its bar and detail.
3. **Forward Signal** — 3-6M score, label, components, and the spot-HOT caution carried over from
   `research/VALIDATION_2026-07-10.md` §5 when the composite is ≥ 70.
4. **AI Market Brief** — signal, score, description, last-session recap, next-session outlook, key
   factors (Gemini key required).
5. **News** — headline, summary, source, date and URL for each item (Gemini key required).
6. Disclaimer footer, running heads, page numbers.

**Key handling matches the app.** Without a Gemini key the AI and News sections stay present and
say so ("Add a free Gemini key in Settings…") rather than silently vanishing; a component that is
unavailable reads *"Add a free FRED key…"* when the key is missing and *"Couldn't load…"* when it
is present. That distinction survives a `DataCache` round-trip here even though
`GoldComponentScore.keyRequired` does not — the builder takes the key presence from `SecurePrefs`
directly.

### How the notification delivers it

Taps route through `ReportActionActivity`, an invisible `noHistory` / `excludeFromRecents` /
empty-`taskAffinity` trampoline. It exists for the fallbacks, which a bare `ACTION_VIEW`
`PendingIntent` cannot provide:

- **Tap** → `ACTION_VIEW` on the FileProvider URI → device PDF viewer. No viewer installed → share
  sheet → toast.
- **Save PDF** → copies into the shared `MediaStore.Downloads` collection, so it lands in Files and
  the browser's download list like any other download. Permission-free on API 29+; on API 26-28 it
  falls back to the share sheet, since the app deliberately holds no `WRITE_EXTERNAL_STORAGE`.
- **Share** → share sheet (Drive, mail, chat).
- **Report pruned / missing** → toast, then opens the app to rebuild it.

Activity trampolines remain permitted on Android 12+; only service and broadcast ones are blocked.

Reports are written to `getExternalFilesDir("reports")` — already covered by the existing
`file_paths.xml` FileProvider entry — and rotate at **7 files**, so yesterday's notification is
still live while the folder cannot grow without bound. A real report measured **169 KB** on device
(most of it the 252-session history path), so a full week costs about 1.2 MB.

## Architecture note

The report is split in two on purpose:

- **`report/ReportContent.kt`** — pure Kotlin. Turns `Map<String, SymbolState>` into an ordered
  `List<Block>`, plus the notification title / summary / big-text strings. No Android imports.
- **`report/GoldReportPdf.kt`** — paints those blocks onto A4 pages via `android.graphics.pdf`.

`PdfDocument` is a stub in JVM unit tests, so a single fused class would have been untestable off
a device. Split, *what the report says* — every section, every key-gated degradation, every string
on the notification — is pinned by 15 JVM tests, and only the pixels need a device. The send time is
likewise pure (`DailyRefreshWorker.nextSendTimeMs`), so the weekend roll and the ET/DST arithmetic
are covered by 8 more without WorkManager.

**No new dependencies.** `android.graphics.pdf.PdfDocument` + `StaticLayout` only; no WebView, no
PDF library, no change to the toolchain (see the v2.3.0 table — unchanged and not to be downgraded).

## Files

| Added | |
|---|---|
| `app/.../report/ReportContent.kt` | block assembly + notification strings (pure Kotlin) |
| `app/.../report/GoldReportPdf.kt` | A4 renderer, pagination, charts |
| `app/.../report/ReportDelivery.kt` | view / share / save-to-Downloads |
| `app/.../report/ReportActionActivity.kt` | invisible notification trampoline |
| `app/src/test/.../report/GoldReportContentTest.kt` | 15 tests — report contents |
| `app/src/test/.../worker/DailyRefreshScheduleTest.kt` | 8 tests — send time, weekend roll, DST |

| Modified | |
|---|---|
| `worker/DailyRefreshWorker.kt` | renders the PDF, richer notification + 2 actions; **6 PM ET weekdays** via a pure, testable `nextSendTimeMs` |
| `AndroidManifest.xml` | registers `ReportActionActivity` |
| `MainViewModel.kt` | `buildReportPdf()` |
| `MainActivity.kt` | overflow menu → Today's Report (PDF) |
| `res/menu/main_menu.xml` | the menu item |
| `app/build.gradle.kts` | versionCode 13 / versionName 2.5.0; **debug `applicationIdSuffix = ".debug"`** |

**No new permissions.** `POST_NOTIFICATIONS` was already declared; the Downloads write is
permission-free under scoped storage.

## Verification performed

All run 2026-08-09 on the Linux box.

- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**. Only warnings are the pre-existing
  `startActivityForResult` / `onActivityResult` deprecations carried from 2.3.0.
- `./gradlew :app:testDebugUnitTest` — **tests=53 failures=0 errors=0** (30 existing + 15 report + 8 schedule).
- `./gradlew clean :app:bundleRelease` — **BUILD SUCCESSFUL from clean**, including
  `lintVitalRelease` and `minifyReleaseWithR8`. AAB 4.4 MB. Rebuilt after the 6 PM schedule change,
  so the artifact matches the source.
- `jarsigner -verify app/build/outputs/bundle/release/app-release.aab` → **jar verified**, upload
  key `CN=Bull88 Protocol, O=CoinTranscend, C=US`. (The PKIX "certificate chain is invalid" note is
  the usual self-signed-upload-key notice.)
- Merged **release** manifest asserts `versionCode="13" versionName="2.5.0" minSdkVersion="26"
  targetSdkVersion="36"`, and still carries `ReportActionActivity` after R8 — `exported="false"`,
  `noHistory="true"`, `excludeFromRecents="true"`, `taskAffinity=""`.

### On-device verification (Pixel 8a, Android 16, 2026-08-09)

Run against the side-by-side debug install (`com.sun.aurum.debug` — see the new
`applicationIdSuffix`, below), leaving the Play build and its stored keys untouched.

- **The real daily worker was force-run** via
  `adb shell cmd jobscheduler run -f com.sun.aurum.debug <jobId>` — it fetched, cached, rendered
  the PDF and posted the notification, with no crash in logcat.
- **Notification, as posted:** `channel=aurum_daily`, **`actions=2`**, title `Gold Report · Aug 9`,
  text `Index 63/100 MIXED · Outlook BULLISH · Gold $398.47 +2.26%`, `BigTextStyle` matching.
- **PDF rendered and inspected page by page** (pulled off the device, rasterised): 2 pages, A4
  595×842, Skia/PDF producer. Title block, section rules, the 3-column stat grid, score headlines,
  the history chart with its zone bands and end-of-series dot, component meters with correctly
  proportioned bars, the running head and page numbers on page 2, and the footnote all render
  correctly. No overlapping text, no clipping at the page break.
- **The key-gated paths render as designed** — and better than the app's own banner: with no FRED
  key the report reads *"Add a free FRED key in Settings to score: Real Yield Pressure · Inflation
  Expectations"*, where the in-app Gold Index banner says *"Couldn't load"* for the same state
  (it has no way to tell the two apart; the report takes key presence from `SecurePrefs`). The AI
  Brief and News sections show their add-a-key lines rather than disappearing.
- **`ACTION_VIEW` handoff works:** tapping the menu item fired the intent and Android raised the
  PDF-viewer chooser.

- **The AI Brief and News sections were rendered too.** The debug install has no Gemini key, so a
  representative brief + three headlines were seeded straight into its `symbol_cache.json` (via
  `run-as`, app force-stopped) and the report regenerated: **3 pages**, the brief's score headline,
  description paragraph, the `AUGUST 8 SESSION` / `AUGUST 9 OUTLOOK` labels picked up from the
  session labels, bulleted key factors, and three news items each with headline, summary, source ·
  date · URL and its left accent bar. All wrapping and spacing correct; the News section opened a
  clean third page.
- **Cleanup:** the debug variant was uninstalled afterwards (it held seeded placeholder headlines,
  which must not sit on a real device). The Play install was never touched — still versionCode 11 /
  2.4.0 with its keys intact.

**Still unverified:** the **Save PDF → Downloads** and **Share** notification actions, and the
report with a **FRED key** present (all five components scoring, which pushes the document longer).
Both are in the smoke test below.

## What's new (Play "What's new" copy)

Paste-ready and within Play's 500-character limit — also kept in
`store/PLAY_STORE_v2.5.0.md` alongside the listing updates this release needs.

> • Your daily report is now a PDF, and it arrives after the close instead of before the open — so
> for the app to reload.
>
> • Save it to Downloads or share it, right from the notification.
>
> • The notification now shows the actual numbers: index score, outlook and gold's move.
>
> • The PDF carries the full report — index, components, history chart, forward signal, AI brief
> and news.
>
> • New: Today's Report (PDF) in the overflow menu, any time you want it.

## Play upload checklist

1. ~~`./gradlew clean :app:bundleRelease`~~ — **done 2026-08-09**, AAB built and verified (above).
2. ~~`jarsigner -verify` the AAB~~ — **done**, jar verified with the Bull88 upload key.
3. Play Console → Aurum88 Protocol → Test and release → **Internal testing** → Create new release.
4. Upload the AAB (release name auto-fills "13 (2.5.0)"), paste the "What's new" copy → Save →
   Review → Roll out.
5. **On-device smoke test.** Most of this is already done on the debug install (see above); what
   remains is marked **[open]**.
   - ~~Overflow menu → Today's Report (PDF) renders a clean document~~ — done, 2 pages inspected.
   - ~~Without a FRED key the report says "Add a free FRED key", not "Couldn't load"~~ — done.
   - **[open]** With a **FRED key** (the Play install has one): all five index components score,
     and the report grows past 2 pages — check the page break lands cleanly.
   - ~~With a Gemini key the AI Brief and News sections render fully~~ — done with seeded content
     (3 pages); re-confirm once with a real Gemini response.
   - ~~The notification carries the numbers, not the old generic text~~ — done
     (`Index 63/100 MIXED · Outlook BULLISH · Gold $398.47 +2.26%`, `actions=2`).
   - **[open]** **Tapping the notification must not open the app** — it goes straight to the PDF.
   - **[open]** **Save PDF** → confirm the file appears in Files → Downloads. **Share** → chooser
     appears and the file arrives intact at the destination.
   - **[open]** The notification fires at **6 PM ET on a weekday**, and **not** on Saturday or
     Sunday. (The schedule arithmetic is unit-tested; what is untested is that WorkManager actually
     honours it across a doze/reboot cycle.)
   - **[open]** Swipe the notification away, then use the menu item — same document.
   - **[open]** Confirm the report folder rotates: after several days only 7 files remain in
     `Android/data/com.sun.aurum/files/reports/`.
6. Promote to **Production** when satisfied. This also clears Play's target-API warning, which
   internal testing alone never does (carried from 2.3.0 — API 36 first shipped there).

## Correction to the v2.4.0 record

`release-2.4/RELEASE_NOTES.md` and `CLAUDE.md` both said v2.4.0's **"AAB and Play upload still to
do"**. That is stale: the phone was inspected on 2026-08-09 and reports

```
versionCode=11  versionName=2.4.0  installerPackageName=com.android.vending
lastUpdateTime=2026-08-08 01:13:48
```

— i.e. v2.4.0 **was** uploaded to internal testing and installed from Play on 2026-08-08, after
those lines were written. `CLAUDE.md` has been corrected. Whether v2.4.0's own smoke-test items
were actually run is not recorded anywhere; if they were not, run them alongside this release
(they are listed in `release-2.4/RELEASE_NOTES.md` step 5).

## Decisions taken

- **The Dollar (DXY) tab is not in the PDF.** This is the *gold* report; the dollar's influence
  already enters through the Gold Index's USD component. Adding an HMAI appendix is a small change
  if it is ever wanted.
- **The PDF bypasses the biometric gate**, which is inherent to "without opening the app". It
  carries public market data only — no keys, no account material. The notification now also puts
  the index score and gold's price on the lock screen; if that is unwanted,
  `setVisibility(VISIBILITY_PRIVATE)` with a generic public version is a two-line change in
  `DailyRefreshWorker`.
- **An on-demand menu item was added** beyond the notification path. It covers a dismissed
  notification and was the only way to exercise the feature without waiting for the scheduled send.

## Follow-ups (not blocking)

- On-device visual pass over the PDF (above) — the one real gap in this release.
- `SettingsActivity.kt` still uses the deprecated `startActivityForResult` / `onActivityResult`
  pair (carried from 2.3.0). Migrate to the Activity Result API.
- `DataCache` does not serialize `GoldComponentScore.keyRequired`; the report works around it via
  `SecurePrefs`, but the in-app Gold Index banner still degrades after a cache reload.
- Store screenshot `store/screenshots/02_*.png` still shows the v1 forward card (carried from 2.2),
  and there is now no screenshot of the PDF report — see `store/PLAY_STORE_v2.5.0.md`.
- `research/results/monthly_final.csv` predates the 2026-07-03 CB feed update (carried from 2.2).
