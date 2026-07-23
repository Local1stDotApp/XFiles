#!/usr/bin/env bash
# Concatenate recorded demo clips into one sped-up tour GIF (+ MP4). The scripted
# drivers pause on uiautomator dumps, so a >1x speed tightens those beats.
#
# Example (the README hero tour):
#   tools/combine-demo.sh demo --speed 2.0 --width 320 \
#     docs/assets/copy.mp4 docs/assets/app-manager.mp4 docs/assets/root.mp4
set -euo pipefail

OUT=""; SPEED=2.0; WIDTH=320; FPS=12; TRIM=0.4; OUTDIR="docs/assets"
clips=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --speed)   SPEED="$2"; shift 2;;
    --width)   WIDTH="$2"; shift 2;;
    --fps)     FPS="$2"; shift 2;;
    --trim)    TRIM="$2"; shift 2;;   # seconds shaved off the head of each clip
    --out-dir) OUTDIR="$2"; shift 2;;
    -h|--help) sed -n '2,9p' "$0"; exit 0;;
    *) if [[ -z "$OUT" ]]; then OUT="$1"; else clips+=("$1"); fi; shift;;
  esac
done
[[ -n "$OUT" && ${#clips[@]} -ge 1 ]] || { echo "usage: combine-demo.sh OUT [opts] clip1.mp4 [clip2.mp4 ...]" >&2; exit 1; }
command -v ffmpeg >/dev/null || { echo "error: ffmpeg not on PATH" >&2; exit 1; }
mkdir -p "$OUTDIR"

# Build the concat filtergraph: trim each clip's head, reset PTS, concat, then speed.
inputs=(); fc=""; labels=""
for i in "${!clips[@]}"; do
  inputs+=(-i "${clips[$i]}")
  fc+="[$i:v]trim=start=$TRIM,setpts=PTS-STARTPTS[v$i];"
  labels+="[v$i]"
done
fc+="${labels}concat=n=${#clips[@]}:v=1,setpts=PTS/${SPEED}[o]"

ffmpeg -y -hide_banner -loglevel error "${inputs[@]}" \
  -filter_complex "$fc" -map "[o]" -an "$OUTDIR/$OUT.mp4"

ffmpeg -y -hide_banner -loglevel error -i "$OUTDIR/$OUT.mp4" \
  -vf "fps=$FPS,scale=$WIDTH:-2:flags=lanczos,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
  "$OUTDIR/$OUT.gif"

dur=$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$OUTDIR/$OUT.mp4")
echo "gif : $OUTDIR/$OUT.gif  (${WIDTH}px, ${FPS}fps, ${SPEED}x, ${dur}s, $(du -h "$OUTDIR/$OUT.gif" | cut -f1))"
