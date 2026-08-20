# Google Play — v2.5.0 (versionCode 13) release sheet

Everything to paste into the Play Console for this release. The permanent listing (name, category,
descriptions, data safety) lives in **`store/STORE_LISTING.md`**; this file is the per-release
delta. Engineering detail and the smoke-test list are in **`release-2.5/RELEASE_NOTES.md`**.

| | |
|---|---|
| **App** | Aurum88 Protocol · `com.sun.aurum` |
| **Version** | 2.5.0 (**versionCode 13**) — 12 was consumed by an earlier upload of the 9 AM build; see §6 |
| **Track** | Internal testing → Production once smoke-tested |
| **Artifact** | `app/build/outputs/bundle/release/app-release.aab` (4.4 MB, rebuilt + verified 2026-08-09 after the schedule change) |
| **Tested** | Force-run on a Pixel 8a / Android 16 side-by-side debug install: PDF renders, notification posts with both actions |
| **Schedule** | Moved 9 AM → **6 PM ET, weekdays only** |
| **Signing** | Upload key `CN=Bull88 Protocol, O=CoinTranscend, C=US`; Play App Signing re-signs |
| **targetSdk** | 36 (Android 16) — unchanged from 2.3.0 |
| **New permissions** | **None** |

---

## 1. "What's new" — paste as-is

Play caps this field at **500 characters per language**. The copy below is 471 including bullets
and blank lines; adding to it risks silent truncation on the store page.

```
• Your daily report is now a PDF, and it arrives after the close instead of before the open — so it covers the session that just finished.

• Save it to Downloads or share it, right from the notification.

• The notification now shows the actual numbers: index score, outlook and gold's move.

• The PDF carries the full report — index, components, history chart, forward signal, AI brief and news.

• New: Today's Report (PDF) in the overflow menu, any time you want it.
```

### Shorter variant (224 chars), if you want the store page tighter

```
Your daily report is now a PDF you can read straight from the notification — no app reload, no waiting. It moves to 6 PM ET on weekdays, so it covers the session that just closed. Save it to Downloads or share it in one tap.
```

---

## 2. Listing changes this release requires

`store/STORE_LISTING.md` has been updated to match; these are the edits to carry into the Console.

**Full description — replace this line:**

> • Daily background refresh and a market-open notification

**with:**

> • A daily post-close report delivered as a PDF — read, save or share it from the notification
> • Daily background refresh, so the report is ready before you open the app

Short description, app name, category, tagline, icon and feature graphic are **unchanged**.

---

## 3. Data safety — unchanged, and why

No answer on the Data Safety form changes. Worth being able to say why if it is ever queried:

- The PDF is generated **on-device** from data the app already holds. Nothing is uploaded, and no
  new data is collected or shared.
- "Save PDF" writes to the device's own Downloads folder via scoped storage
  (`MediaStore.Downloads`). It is **user-initiated**, needs no permission on Android 10+, and the
  file never leaves the device unless the user chooses Share.
- "Share" opens the system share sheet. Where the file goes is the user's choice, made in the
  system UI — the app has no destination of its own.
- No new permission is declared. `POST_NOTIFICATIONS` was already in the manifest.

Content rating, target audience, ads and financial-features answers are all unchanged.

---

## 4. Screenshots — one gap worth closing

The store currently shows four phone screenshots (`store/screenshots/`): Gold hero, components +
forward signal, AI brief, news. **The headline feature of this release is not pictured.**

Recommended before promoting to Production:

- [ ] **New screenshot: the PDF report** — the notification expanded with its Save/Share actions,
      or the first page of the PDF open in a viewer. This is the release's selling point.
- [ ] Re-shoot `02_*.png`, which still shows the **v1 forward card** (stale since 2.2).

Neither blocks an internal-testing upload; both should land before Production. Play requires
1080×2160 or similar at ≤ 2:1.

---

## 5. Rollout steps

1. Play Console → **Aurum88 Protocol** → Test and release → **Internal testing** → Create new
   release.
2. Upload `app/build/outputs/bundle/release/app-release.aab`. Release name auto-fills
   **"13 (2.5.0)"**.
3. Paste the "What's new" copy from §1.
4. Save → Review release → **Roll out to internal testing**.
5. Install from the internal-testing opt-in link and run the smoke test in
   `release-2.5/RELEASE_NOTES.md` § "Play upload checklist" step 5. The PDF **has** been rendered
   and inspected on a Pixel 8a, so the remaining gaps are narrow: the AI Brief and News sections
   with real Gemini content, and the **Save PDF** / **Share** actions.
6. v2.4.0 (versionCode 11) is already live on internal testing — confirmed from the device on
   2026-08-09, despite `release-2.4/RELEASE_NOTES.md` still saying the upload was pending. If its
   own smoke-test items were never run, fold them in here (that file, step 5).
7. Apply the §2 listing edits and the §4 screenshots, then promote to **Production**.

> **Note:** Play only clears the target-API warning once a **production** release targets API 36.
> Internal testing never clears it, no matter how many builds go through. Carried from 2.3.0.

---

## 6. Why this is versionCode 13, not 12

Play rejected the upload with **"Version code 12 has already been used."** A version code is
consumed the moment a bundle is uploaded — including into a draft release that is never rolled out —
and it can never be reused, even by an identical rebuild.

What almost certainly happened: the versionCode 12 AAB built earlier on 2026-08-09 was uploaded,
then the bundle was rebuilt after the send time moved from 9 AM to 6 PM. Two different bundles, one
version code — Play refuses the second.

**The bundle Play holds under 12 is the 9 AM / every-day build.** It does not have the 6 PM weekday
schedule. So:

- [ ] If 12 sits in a **draft** release: discard the draft, then upload 13.
- [ ] If 12 was already **rolled out** to internal testing: just roll out 13 over it. Play always
      serves the highest version code, so testers upgrade to 13 and the 9 AM schedule never sticks
      (and even a device that did install 12 self-corrects on upgrade — `WORK_NAME` is unchanged, so
      `ExistingWorkPolicy.REPLACE` swaps the schedule).
- [ ] Either way, open **Release → App bundle explorer** and confirm what 12 actually is. The
      project's version history has already proved incomplete once (`release-2.4` still claimed its
      upload was pending when the device showed it installed from Play), so it is worth checking
      that no other unexpected code has been consumed.

Next free code after this release is **14**.

---

## 7. Reminders that bite

- **Never commit the AAB.** `*.aab` / `*.apk` are gitignored; distribution is store-only.
- **Play App Signing re-signs the bundle**, so a Play install presents neither the upload key nor
  the debug key. The Play app-signing certificate already has its own Android OAuth client
  (added 2026-08-08) — do not remove it, or Google Sign-In returns `ApiException` code 10 /
  `DEVELOPER_ERROR` on Play builds while debug builds keep working. All three SHA-1s are in
  `CLAUDE.md` → "Google Sign-In / OAuth (Cloud Console — the SHA-1 trap)".
- **Personal developer account rule:** production access needs a closed test with ~12-20 testers
  for 14 continuous days. Internal testing does not count toward it.
