#!/usr/bin/env bash
# Builds the whole narrated demo in one go: narration audio, then the recording (which waits for each spoken line), then the film.
#
#   ELEVENLABS_API_KEY=... [ELEVENLABS_VOICE_ID=...] scripts/demo/build-demo.sh [work-folder]
#
# Without ELEVENLABS_API_KEY it uses a macOS system voice, as a stand-in. The key is read from the environment only; this script never
# writes it down or prints it. The local stack must be running first (see scripts/demo/README.md). Takes about 6 minutes.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="${1:-/tmp/ridehail-demo}"
mkdir -p "$work"
export NODE_PATH="${NODE_PATH:-$HOME/node_modules}"   # where Playwright is installed

node "$here/narrate.cjs" "$work/audio"
NARRATION_TIMING="$work/audio/timing.json" node "$here/record-demo.cjs" "$work/clips"
node "$here/assemble.cjs" "$work/clips" "$work/audio" "$(cd "$here/../.." && pwd)/docs/demo"
