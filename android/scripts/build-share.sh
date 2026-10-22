#!/usr/bin/env bash
# Builds the signed ARM64 share APK into dist/. Version 0.1.<commit count>, matching build.gradle.kts.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="0.1.$(git rev-list --count HEAD)"
APK="../dist/home-voice-$VERSION-arm64.apk"
./gradlew assembleShare
rm -rf ../dist && mkdir -p ../dist
cp app/build/outputs/apk/share/app-share.apk "$APK"
cp SHARING.md ../dist/Home-Voice-setup.md
python3 scripts/check-native-alignment.py "$APK"
sha256sum "$APK"
