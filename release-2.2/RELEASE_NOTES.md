# Aurum88 Protocol v2.2 — Release Notes

## v2.2.0 (versionCode 9)

**Status:** merged to `master` 2026-07-12 (`cafbb5e`), signed AAB built — awaiting Play
**Internal testing** upload. **Supersedes v2.1.1 (code 8), which is skipped** — its Clear-Cache
fix is contained here and its AAB was never uploaded; Play has no problem with skipped
versionCodes (last uploaded release stays 7 / 2.1.0).

Both changes come from the 2026-07-10 independent audit (`research/VALIDATION_2026-07-10.md`),
which re-derived every claim in `research/README.md` against fresh LBMA data: shipped math
confirmed, no engine-weight changes; these are the audit's only two recommended adjustments.

## What's new (Play "What's new" copy)

> • Central-bank data: the built-in 2025 figure now uses the published WGC actual (863 t), for
> installs running without the live feed.
>
> • The Forward Signal card shows a caution note whenever current conditions read HOT (index ≥ 70)
> — historically a sign of stretched conditions, not strength.
>
> • Clear Cache now also refreshes the central-bank data feed on demand.

## Technical summary

### CB bundled fallback: 2025 → 863 t actual (`GoldIndexEngine.kt`)
`cbByYear` had `2025 to 1000.0` as a placeholder; WGC FY-2025 actual = **863 t** (sum of the
published feed quarters 249+166+218+230). `CB_ESTIMATE_FROM_YEAR` moved to 2026, so the CB
"as of" label now reads "2025" (actual) rather than "2025 est." when the annual fallback drives
the score. Affects **only the no-feed path** (fresh installs before the feed fetch, or feed
failure > 7 days): those installs scored CB ≈ 90 where feed users correctly get 75.5. 2026 stays
a 1000 t estimate (Q1 run-rate 244×4 ≈ 976 supports it). Test expectation updated; 29/29 green.

### Spot-HOT caution chip (`fragment_quote.xml` + `QuoteFragment`)
Amber chip under the Forward Signal header, visible only while the spot composite ≥ 70 (HOT):
*"⚠ Conditions stretched: after HOT readings gold averaged −0.3% over 3M (47% up) vs +3.2%
base."* Basis (audit §5, 254 month-ends): spot-HOT systematically degrades forward outcomes —
fwd-BEARISH & HOT: **−4.4% mean, 17.6% up**; fwd-NEUTRAL & HOT: +0.8%. Copy/UI only; engine,
labels and scores untouched. It is a caution, **not** an override of the Forward label
(BULLISH & HOT is n=3 and benign).

### Carried from unreleased v2.1.1 (code 8, skipped)
Clear Cache (⋮ menu) also invalidates the 7-day CB feed cache — on-demand feed refresh.

## Play upload checklist

1. Play Console → Aurum88 Protocol → Test and release → **Internal testing** → Create new release.
2. Upload `app/build/outputs/bundle/release/app-release.aab` (release name auto-fills
   "9 (2.2.0)"). Built 2026-07-12 from `cafbb5e`, jarsigner-verified with the upload key.
3. Paste the "What's new" copy above → Save → Review → Roll out to internal testing.
4. On the test device (internal opt-in link): confirm version **2.2.0** in app settings; Gold tab
   CB row still shows a live-feed label ("as of 2026-Q1"). The caution chip is correctly
   **absent** right now — spot is ~32 (WEAK); it appears only at spot ≥ 70. The 863 t fallback is
   likewise dormant while the feed is healthy — both changes are safety-net/telltale paths, so
   "nothing looks different" plus a working index **is** the expected smoke-test result.
5. Promote to **Production** when satisfied.

## Not blocking
- Store screenshot `store/screenshots/02_*.png` still shows the v1 forward card (pre-2.1 note —
  retake whenever convenient).
- `research/results/monthly_final.csv` predates the 2026-07-03 CB feed update — regenerate on the
  next research-pipeline run (audit housekeeping note).
