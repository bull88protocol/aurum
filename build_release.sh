#!/usr/bin/env bash
# Aurum — build signed release APK + Play AAB. Independent of other projects.
set -e
source /home/sun/option_android/android_env.sh
echo "▶ assembleRelease (signed APK)…"
./gradlew :app:assembleRelease
echo "▶ bundleRelease (Play AAB)…"
./gradlew :app:bundleRelease
APK=app/build/outputs/apk/release/app-release.apk
AAB=app/build/outputs/bundle/release/app-release.aab
echo "✓ APK : $APK  (local install / QA only — not for distribution)"
echo "✓ AAB : $AAB  (upload this to Play)"
APKSIGNER=$(ls "$ANDROID_SDK_ROOT"/build-tools/*/apksigner | sort | tail -1)
"$APKSIGNER" verify --print-certs "$APK" | grep "certificate DN" || true
