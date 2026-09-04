# Aurum88 Protocol v2.7 — Release Notes

## v2.7.0 (versionCode 15)

**Why this release exists:** the app could sit on a loading spinner indefinitely, with no error, no
timeout and no way out except force-quitting. Reported from the field on 2026-09-04 against the
live 2.6.0 build ("it keeps loading… it happened one time too"), reproduced and traced the same day.

**Shipped immediately rather than after a soak week**, at the owner's direction — the hang was
actively affecting use. The trade-off is that 2.6.0 (live 2026-09-04) and 2.7.0 overlap in Play
vitals, so a new crash/ANR signal in the next fortnight cannot be cleanly attributed to one release.
Watch ANR rate in particular: this release changes network and cancellation behaviour app-wide.

## What changed

| | Before | After |
|---|---|---|
| HTTP call ceiling | none — only connect/read | `callTimeout` on all five clients |
| Yahoo retries | 3 attempts × 2 hosts (~5 min/series) | 2 × 2 |
| Worst-case refresh | ~30 min of spinner | bounded at 180s, then an error |
| Sheets sync failure | killed the whole refresh | guarded; quotes continue |
| Failed first load | bare spinner, no way out | error + **Retry** button |
| Transient Sheets error | created a duplicate Drive spreadsheet | keeps the saved sheet |

---

## The five causes

None were introduced by 2.6.0 — all had been shipping for months. They only bite together.

**1. No `callTimeout` on any client.** This is the one that makes "forever" possible.
`connectTimeout` and `readTimeout` bound *individual socket operations*, not the call: a server that
accepts the connection and trickles bytes resets the read timer indefinitely, and nothing upstream
ever returns. Added to all five — Yahoo/Sheets/FRED 45s, CentralBank 30s, Gemini 150s (its read
alone is 120s).

**2. The retry arithmetic was indefensible.** `YahooFinanceClient.get()` ran 3 attempts × 2 hosts ×
(20s connect + 30s read) ≈ **5 minutes for one series**, and `fetchAll` runs six of them in series —
**~30 minutes** of spinner on a Yahoo outage. Cut to 2 attempts; the `query2` mirror already covers
the flaky-host case the third attempt was added for.

**3. `fetchAll`'s pre-loop section ran unguarded.** The Sheets sync, the VIX fetch and the shared
DXY fetch all sat *outside* the per-symbol `try/catch`, so a failure there killed the refresh before
a single symbol was attempted. The Sheets call only runs when signed in — **which is why the hang
looked login-specific.** Sync is optional and must never block quotes; each is now guarded.

**4. `refresh()` had no overall ceiling.** Now `withTimeout(180_000)` with its own user-facing
message. **This frees the UI, not the socket** — `fetchAll` does blocking OkHttp work that coroutine
cancellation cannot interrupt, so the orphaned request runs until its own `callTimeout` fires. Fixes
1 and 4 are both required; neither is sufficient alone.

**5. A failed first load was a dead end.** `QuoteFragment` shows a full-screen spinner while
`loading && quote == null && goldIndexReport == null` and returns early, so the error branch was
unreachable and there was no visible pull-to-refresh target. The error state now carries a
**Retry** button.

## Also fixed: duplicate spreadsheets in the user's Drive

Found while tracing the above. `GoogleSheetsClient.fetchLiveQuotes` recreated the sync spreadsheet
whenever `tryRead` returned null — and `tryRead` returned null on **any** failure: a timeout, a 500,
a dropped connection. Every transient blip therefore minted a fresh "Aurum Market Data" sheet in the
user's Drive, saved the new id and orphaned the previous one, so a flaky connection quietly piled up
duplicates. `tryRead` now returns `Missing` (404/403 → the id really is unusable, recreate) or
`Failed` (transient → keep the saved id and report no quotes).

**Existing duplicates are not cleaned up by this release** — the fix stops new ones. Users who have
accumulated them must delete them from Drive by hand.

## Files

| File | Change |
|---|---|
| `network/YahooFinanceClient.kt` | `callTimeout`; `MAX_ATTEMPTS` 3 → 2 |
| `network/GoogleSheetsClient.kt` | `callTimeout`; `ReadResult` Missing vs Failed |
| `network/GeminiClient.kt` · `FredClient.kt` · `CentralBankClient.kt` | `callTimeout` |
| `data/DataRepository.kt` | pre-loop Sheets / VIX / DXY fetches guarded |
| `MainViewModel.kt` | `withTimeout(180s)` + `REFRESH_TIMEOUT_MSG` |
| `ui/QuoteFragment.kt` · `layout/fragment_quote.xml` · `values/strings.xml` | Retry button |

## Verification performed

- `:app:testDebugUnitTest` — **55 tests, 0 failures**.
- `:app:bundleRelease` clean, R8 full mode. `jarsigner -verify` → **jar verified**,
  `CN=Bull88 Protocol, O=CoinTranscend, C=US`.
- AAB manifest inspected: **versionCode 15 · versionName 2.7.0 · `com.sun.aurum`**, no debug suffix.
- **On device (Pixel 8a, debug variant alongside the Play build), 2026-09-04:**
  - Normal load fine — Gold $405.85, Gold Index MIXED, no hang.
  - **With Wi-Fi and mobile data disabled**, a refresh showed `"Failed to fetch data"` and a
    **RETRY** button (`id/btnRetry`, `[424,541][655,667]`) instead of spinning, and kept the stale
    data visible underneath.
  - Radios restored, **RETRY tapped** → error and button both cleared. (A second failure would have
    re-shown them; the repopulated numbers were not screenshotted before the phone locked.)
- **Not exercised: the 180s `withTimeout` path itself.** Killing the radios makes OkHttp fail fast,
  which tests the error branch but not the timeout branch. Reproducing a true stall needs a
  trickling server; the recovery path is the same `catch`, only the message differs.

## What's new (Play "What's new" copy)

Paste-ready, 352 characters — within Play's 500-character limit.

> Fixes in this update:
> • The app could get stuck on a loading spinner with no way out. Refreshes are now time-limited and show a Retry button if something goes wrong.
> • Signing in no longer holds up your quotes when Google Sheet sync is slow or unreachable.
> • Fixed a bug that could leave duplicate "Aurum Market Data" spreadsheets in your Google Drive.

## Play upload checklist

1. ~~`./gradlew :app:bundleRelease`~~ — **done 2026-09-04**, built and verified above.
2. ~~`jarsigner -verify`~~ — **done**, jar verified with the Bull88 upload key.
3. ~~Play Console → Production → Create new release~~ — **done 2026-09-04.** Uploaded as
   "15 (2.7.0)".
4. ~~Paste the "What's new" copy, roll out~~ — **done: submitted 2026-09-04, rolling out to 100%,
   awaiting Play review.**
5. **[open] After rollout, watch ANR rate first.** This release changes network timeout and
   cancellation behaviour on every screen, and ANR is the shape a regression here would take.
   Compounded by shipping days after 2.6.0: the two overlap in vitals, so a new signal cannot be
   cleanly attributed to one release.
6. **[open] Confirm approval**, then update `CLAUDE.md`'s status block. 2.6.0 cleared review in
   ~1 day; 2.5.0 took ~11.

## Follow-ups (not blocking)

- **No unit test covers `resolveOpen` or the timeout paths** — `org.json` is stubbed in Android unit
  tests and `MainViewModel` needs an Android context. Moving the pure logic into `:shared` would
  make both testable; already flagged in `api-37/API_37_UPGRADE_PLAN.md` §4.
- **Existing duplicate Drive spreadsheets are not cleaned up** — see above.
- **PDF "Open" tile still unverified on a gap day** — carried from 2.6.0; the only day the old bug
  was visible.
- **Store screenshots and full description** — still open, still store-side, still deliberately
  skipped.
