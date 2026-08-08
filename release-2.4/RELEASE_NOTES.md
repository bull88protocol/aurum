# Aurum88 Protocol v2.4 — Release Notes

## v2.4.0 (versionCode 11)

**Why this release exists:** the Settings screen displayed the user's saved FRED and Gemini API keys
in cleartext. Both fields used `android:inputType="textVisiblePassword"` (which deliberately does
*not* mask), and `SettingsActivity` re-populated each field with the stored key on every open. The
keys were therefore readable by anyone looking at the screen, by any screenshot or screen recording,
and — the part that actually matters — by any accessibility service, since the plaintext sat in the
view hierarchy. Confirmed on-device 2026-08-08: a plain `adb shell uiautomator dump` returned the
live FRED key, no root required.

**At-rest storage was already correct and is unchanged.** `SecurePrefs` encrypts every key with
AES-256-GCM under an Android Keystore key that never leaves the Keystore (`Crypto.kt`), and the
manifest sets `android:allowBackup="false"`. This release fixes the *display* layer only.

## What changed

| | Before | After |
|---|---|---|
| Field masking (`activity_settings.xml`) | `inputType="textVisiblePassword"` | **`textPassword`** + `app:endIconMode="password_toggle"` |
| Stored key on screen (`SettingsActivity`) | `setText(prefs.fredApiKey)` — full key | **masked summary only** (`Saved · •••• 11d2`), field starts empty |
| Screenshots / recents / screen recording | allowed | **`FLAG_SECURE`** on `SettingsActivity` |

Three layers, each closing a different hole:

1. **The stored key is never written back into the view tree.** On open, the field is empty and a
   new `tvFredKeyStatus` / `tvGeminiKeyStatus` line shows `Saved · •••• 11d2` — the last four
   characters, enough to tell two keys apart and confirm a save landed, useless to a reader. This
   is the only one of the three that closes the accessibility/UI-dump path, because `FLAG_SECURE`
   does not hide text from accessibility services.
2. **Typing is masked, with a reveal toggle.** `textPassword` keeps the no-autocorrect,
   no-suggestion-learning behaviour that `textVisiblePassword` was presumably chosen for, so
   nothing regresses; the Material `password_toggle` eye lets a user verify a paste.
3. **`FLAG_SECURE`** blocks screenshots, screen recording, and the recents thumbnail.

**Behaviour notes.** Save now refuses a blank field instead of silently wiping the stored key
(previously `Save` on an empty field cleared it — `Clear` is the explicit way to do that). Test
uses what is being typed and falls back to the stored key when the field is empty, so it still
works on a screen that no longer shows the key.

## Also fixed since 2.3.0 — Google Sign-In (no code change, not in this build)

Sign-In failed on the Play build with `ApiException` code 10 / `DEVELOPER_ERROR`. Cause: Play App
Signing re-signs the AAB, so the installed APK presents neither the upload key nor the debug key,
and no Android OAuth client was registered for the Play app-signing certificate. Fixed 2026-08-08
by adding that client in Cloud Console — **server-side, no rebuild, no versionCode bump**, and it
already applies to installs running 2.3.0. Details and all three SHA-1s: `CLAUDE.md` →
"Google Sign-In / OAuth (Cloud Console — the SHA-1 trap)". Deliberately left out of the Play
"What's new" below, since it is not a change in this build.

## Verification performed

- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL**. Only warnings are the pre-existing
  `startActivityForResult` / `onActivityResult` deprecations carried from 2.3.0.
- `./gradlew :app:testDebugUnitTest` — **tests=30 failures=0 errors=0**.

**Not yet done — on-device smoke test.** The debug build cannot be installed over the Play build
(different signing key, and there is no `applicationIdSuffix` for debug), and uninstalling the Play
build would destroy the tester's stored API keys and Google sign-in — `allowBackup="false"` means
nothing comes back. Verify from the internal-testing track instead; see step 4 below.

## What's new (Play "What's new" copy)

> • Your API keys are now hidden as you type, with a tap-to-reveal option.
>
> • Settings no longer shows a saved key in full — just the last four characters, so you can still
> tell which key is stored.
>
> • The Settings screen is now excluded from screenshots and screen recordings.

## Play upload checklist

1. `./gradlew clean :app:bundleRelease` (needs `keystore.properties`).
2. Play Console → Aurum88 Protocol → Test and release → **Internal testing** → Create new release.
3. Upload `app/build/outputs/bundle/release/app-release.aab` (release name auto-fills "11 (2.4.0)").
4. Paste the "What's new" copy above → Save → Review → Roll out to internal testing.
5. **On-device smoke test** — the outstanding verification:
   - Settings shows `Saved · •••• ****` for each key already stored (upgrade preserves them —
     `SecurePrefs` is untouched, so this is the real regression check).
   - Both key fields start empty and show dots while typing; the eye icon reveals.
   - Save with an empty field warns instead of wiping the stored key; Clear still clears.
   - Test works with the field left empty (falls back to the stored key).
   - Attempting a screenshot on Settings is blocked by the system.
   - Gold tab still populates — i.e. the stored FRED key survived the upgrade.
6. Promote to **Production** when satisfied. This also clears Play's target-API warning, which
   internal testing alone never does (carried over from 2.3.0 — API 36 first shipped there).

## Follow-ups (not blocking)

- `SettingsActivity.kt` still uses the deprecated `startActivityForResult` / `onActivityResult`
  pair (carried from 2.3.0). Migrate to the Activity Result API.
- `MainActivity` has no `FLAG_SECURE`; it shows no secrets, so this was left alone deliberately.
  Revisit if a future screen surfaces account or key material.
- Store screenshot `store/screenshots/02_*.png` still shows the v1 forward card (carried from 2.2).
- `research/results/monthly_final.csv` predates the 2026-07-03 CB feed update (carried from 2.2).
