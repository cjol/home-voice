#!/usr/bin/env bash
# Downloads openWakeWord ONNX models into app assets (not committed).
# Usage: ./scripts/fetch-models.sh [wakeword_model_name]   (default: hey_jarvis_v0.1)
set -euo pipefail
cd "$(dirname "$0")/.."
REL=https://github.com/dscripka/openWakeWord/releases/download/v0.5.1
DEST=app/src/main/assets/wakeword
WW=${1:-hey_jarvis_v0.1}
mkdir -p "$DEST"
curl -fL -o "$DEST/melspectrogram.onnx" "$REL/melspectrogram.onnx"
curl -fL -o "$DEST/embedding_model.onnx" "$REL/embedding_model.onnx"
curl -fL -o "$DEST/wakeword.onnx" "$REL/$WW.onnx"
echo "Wake word model: $WW" && ls -l "$DEST"
