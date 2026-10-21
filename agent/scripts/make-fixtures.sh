#!/usr/bin/env bash
# Generates spoken test questions as raw PCM s16le 16 kHz mono (what the tablet sends).
# Needs espeak + ffmpeg. Output goes to scripts/fixtures/ (gitignored).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p fixtures
gen() { espeak -v en-us -s 125 -g 4 -w "fixtures/$1.wav" "$2"; ffmpeg -loglevel error -y -i "fixtures/$1.wav" -ar 16000 -ac 1 -f s16le "fixtures/$1.raw"; rm "fixtures/$1.wav"; }
gen math   "What is seven times six?"
gen ui     "Show me a list of three fruits on the screen."
gen joke   "Tell me a very short joke."
ls -l fixtures
