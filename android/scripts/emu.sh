#!/usr/bin/env bash
# Drive the app on the emulator (or any adb device) with no microphone needed.
#   scripts/emu.sh boot                 start the HomeVoice_Tablet AVD headless and wait for boot
#   scripts/emu.sh install              build, install, grant permissions, configure from agent/.dev.vars, start
#   scripts/emu.sh say "what is 7x6"    text turn (skips STT)
#   scripts/emu.sh speak <file.raw|"words">  inject PCM s16le 16 kHz into the mic path; words are espeak'd,
#                                       prefixed with "Hey Jarvis." so the real wake word runs
#   scripts/emu.sh logs                 follow app logs
#   scripts/emu.sh shot [out.png]       screenshot
set -euo pipefail
cd "$(dirname "$0")/.."
ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
PKG=net.littlesavage.homevoice
RX="$PKG/.debug.DebugReceiver"
AGENT_URL=${AGENT_URL:-https://home-voice-agent.cjols.workers.dev}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}

wait_boot() {
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
  "$ADB" shell input keyevent KEYCODE_WAKEUP; "$ADB" shell wm dismiss-keyguard || true
}

case "${1:-}" in
  boot)
    (nohup "$HOME/Android/Sdk/emulator/emulator" -avd HomeVoice_Tablet -no-window -no-snapshot -no-boot-anim \
      -gpu swiftshader_indirect >/tmp/homevoice-emu.log 2>&1 &)
    wait_boot; echo booted ;;
  install)
    [ -f app/src/main/assets/wakeword/wakeword.onnx ] || scripts/fetch-models.sh
    ./gradlew -q assembleDebug
    "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
    for p in RECORD_AUDIO POST_NOTIFICATIONS; do "$ADB" shell pm grant $PKG android.permission.$p; done
    "$ADB" shell appops set $PKG SYSTEM_ALERT_WINDOW allow
    "$ADB" shell dumpsys deviceidle whitelist +$PKG >/dev/null
    TOKEN=$(grep -oP 'DEVICE_TOKEN=\K\S+' ../agent/.dev.vars)
    "$ADB" shell am broadcast -n $RX -a $PKG.debug.CONFIG --es serverUrl "$AGENT_URL" --es token "$TOKEN" >/dev/null
    echo "installed and started against $AGENT_URL" ;;
  say)
    "$ADB" shell am broadcast -n $RX -a $PKG.debug.SAY --es text "'${2:?text}'" >/dev/null ;;
  speak)
    src=${2:?file.raw or words}
    if [ ! -f "$src" ]; then
      tmp=$(mktemp -d)
      espeak -v en-us -s 125 -g 4 -w "$tmp/w.wav" "Hey Jarvis."
      espeak -v en-us -s 125 -g 4 -w "$tmp/q.wav" "$src"
      ffmpeg -loglevel error -y -i "$tmp/w.wav" -f lavfi -t 1.0 -i anullsrc=r=22050:cl=mono -i "$tmp/q.wav" \
        -f lavfi -t 4 -i anullsrc=r=22050:cl=mono -filter_complex "[0][1][2][3]concat=n=4:v=0:a=1" \
        -ar 16000 -ac 1 -f s16le "$tmp/in.raw"
      src="$tmp/in.raw"
    fi
    "$ADB" push "$src" /data/local/tmp/hv_in.raw >/dev/null
    "$ADB" shell run-as $PKG sh -c "'mkdir -p files && cp /data/local/tmp/hv_in.raw files/'"
    "$ADB" shell am broadcast -n $RX -a $PKG.debug.INJECT --es path /data/data/$PKG/files/hv_in.raw >/dev/null ;;
  logs)
    "$ADB" logcat --pid="$("$ADB" shell pidof $PKG)" -s VoiceService MicCapture AgentClient DebugReceiver AndroidRuntime ;;
  shot)
    "$ADB" exec-out screencap -p > "${2:-/tmp/homevoice.png}"; echo "${2:-/tmp/homevoice.png}" ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
