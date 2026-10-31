#!/usr/bin/env bash
# Joins the clips from record-demo.cjs into docs/demo/ridehail-demo.mp4 (the full demo) and ridehail-demo.gif (a short preview
# for the README). Needs ffmpeg.   scripts/demo/make-video.sh <clips-folder> [output-folder]
set -euo pipefail
CLIPS=${1:?clips folder from record-demo.cjs}
OUT=${2:-docs/demo}
mkdir -p "$OUT"
FPS=30
# the admin tour is played at 1.25x because its pages take a moment to load
# title, the customer and driver side by side, the admin panel, the closing card: all scaled to 1280x720
ffmpeg -y -loglevel error \
  -i "$CLIPS/0-title.webm" -i "$CLIPS/ride-customer.webm" -i "$CLIPS/ride-rider.webm" -i "$CLIPS/admin.webm" -i "$CLIPS/9-end.webm" \
  -filter_complex "
    [0:v]fps=$FPS,scale=1280:720,setsar=1,format=yuv420p[t];
    [1:v]fps=$FPS,scale=640:720,setsar=1[c];
    [2:v]fps=$FPS,scale=640:720,setsar=1[r];
    [c][r]hstack=inputs=2,format=yuv420p[ride];
    [3:v]setpts=PTS/1.25,fps=$FPS,scale=1280:720,setsar=1,format=yuv420p[a];
    [4:v]fps=$FPS,scale=1280:720,setsar=1,format=yuv420p[e];
    [t][ride][a][e]concat=n=4:v=1:a=0[v]" \
  -map "[v]" -c:v libx264 -preset slow -crf 28 -movflags +faststart "$OUT/ridehail-demo.mp4"
# a preview that plays inline on GitHub: the ride, from sign-up to the driver's earnings, small and slow
ffmpeg -y -loglevel error -ss 5 -t 50 -i "$OUT/ridehail-demo.mp4" \
  -vf "fps=6,scale=720:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" \
  "$OUT/ridehail-demo.gif"
ls -la "$OUT"
ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/ridehail-demo.mp4" | awk '{printf "duration %.1fs\n",$1}'
