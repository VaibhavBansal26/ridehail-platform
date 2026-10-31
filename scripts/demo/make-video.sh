#!/usr/bin/env bash
# Joins the clips from record-demo.cjs (and the narration from narrate.cjs) into docs/demo/ridehail-demo.mp4 and a preview gif.
#   scripts/demo/make-video.sh <clips-folder> [<audio-folder> | -] [output-folder]
set -euo pipefail
exec node "$(dirname "$0")/assemble.cjs" "$@"
