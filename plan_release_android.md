# Aurum — Android Release Runbook

Aurum (`com.sun.aurum`) is a fully independent app. Nothing here touches
QuotePulse (`com.sun.quotepulse`), Bull88 (`com.sun.optionandroid`), or their
folders. The only shared, read-only resources are the Android SDK and the upload
keystore.

## Signing — reusing the Bull88 upload key (intentional)
Google Play allows **one upload key to sign multiple apps**. Each app has its own
`applicationId` and gets its own Play-managed *app signing key*; the upload key
only authenticates uploads. Aurum therefore reuses:

- Keystore: `/home/sun/keystores/bull88-upload.jks`
- Alias: `bull88`
- Credentials: `keystore.properties` (gitignored, mirrors Bull88's)

If you later want a fully separate upload key for Aurum, generate a new `.jks`,
point `keystore.properties` at it, and register it as the upload key for the
Aurum app in Play Console — no code change needed.

## Build
```bash
source /home/sun/option_android/android_env.sh        # SDK + gradle on PATH
./gradlew :app:assembleDebug                           # quick sanity APK
./gradlew :app:assembleRelease                         # signed release APK
./gradlew :app:bundleRelease                           # Play AAB (upload this)
```
Outputs:
- APK: `app/build/outputs/apk/release/app-release.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`

Verify signing:
```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# expect: CN=Bull88 Protocol, O=CoinTranscend
```

## Versioning
Bump in `app/build.gradle.kts` for every Play upload:
- `versionCode` — integer, must strictly increase (start: 1).
- `versionName` — public label (start: `1.0.0-beta`).

## Play Console steps
1. Create app → name **Aurum**, default language, app/free, Finance category.
2. Internal testing track → upload `app-release.aab`.
3. Accept Play App Signing (let Google manage the app signing key; upload key is
   the Bull88 key).
4. Complete: Store listing (see `store/STORE_LISTING.md`), Data Safety, Content
   Rating, Privacy Policy URL (host `PRIVACY.md`).
5. Add screenshots (capture index hero, five-pillar breakdown, history chart).
6. Roll out internal → closed → production.

## Independence checklist
- [x] Own package namespace `com.sun.aurum` (no `quotepulse` refs remain).
- [x] Own git repo, own version counter.
- [x] Builds, signs, and bundles standalone.
- [x] No writes to QuotePulse / Bull88 source trees.
