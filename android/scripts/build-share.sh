#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew assembleShare
mkdir -p ../dist
cp app/build/outputs/apk/share/app-share.apk ../dist/home-voice-0.1.0-arm64.apk
cp SHARING.md ../dist/Home-Voice-setup.md
python3 scripts/check-native-alignment.py ../dist/home-voice-0.1.0-arm64.apk
sha256sum ../dist/home-voice-0.1.0-arm64.apk
