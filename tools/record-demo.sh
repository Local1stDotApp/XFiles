#!/usr/bin/env bash
# Record an on-device screen capture and turn it into a README-ready GIF (+ the
# source MP4). Reusable for any XFiles demo — drive the UI by hand during the
# capture window, or hand it a driver script with --drive to make it reproducible.
#
# Examples
#   # Record 15s while you tap through something by hand, save docs/assets/foo.{gif,mp4}
#   tools/record-demo.sh foo --duration 15 --width 380
#
#   # Cold-start the app and replay a scripted flow (see tools/demos/*.sh)
#   tools/record-demo.sh root --cold --width 380 --drive tools/demos/root.sh
#
# Defaults match the existing docs/assets/demo.gif (fps 12). Portrait demos want
# a smaller --width (the phone is 1440 wide); 800 is right for landscape.
set -euo pipefail

# ---- defaults ----------------------------------------------------------------
NAME=""
SERIAL="${SERIAL:-}"
DURATION=30            # hard cap; a --drive script that finishes early stops sooner
WIDTH=800
FPS=12
CROP_TOP=0
CROP_BOTTOM=0
BIT_RATE="16M"
COLD=0
LAUNCH="app.local1st.files/.MainActivity"
PKG="app.local1st.files"
SETTLE=2.5             # seconds to let the app settle after a cold launch
TOUCHES=1              # overlay the system touch indicator while recording
PREP=""                # script run AFTER cold start but BEFORE recording (off-camera)
DRIVE=""
OUT_DIR="docs/assets"
DEV_FILE="/sdcard/xfiles-demo-rec.mp4"

usage() { sed -n '2,20p' "$0"; exit "${1:-0}"; }

# ---- args --------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)     SERIAL="$2"; shift 2;;
    -d|--duration)   DURATION="$2"; shift 2;;
    -w|--width)      WIDTH="$2"; shift 2;;
    -f|--fps)        FPS="$2"; shift 2;;
    --crop-top)      CROP_TOP="$2"; shift 2;;
    --crop-bottom)   CROP_BOTTOM="$2"; shift 2;;
    --bit-rate)      BIT_RATE="$2"; shift 2;;
    --cold)          COLD=1; shift;;
    --launch)        LAUNCH="$2"; shift 2;;
    --settle)        SETTLE="$2"; shift 2;;
    --touches)       TOUCHES=1; shift;;
    --no-touches)    TOUCHES=0; shift;;
    --prep)          PREP="$2"; shift 2;;
    --drive)         DRIVE="$2"; shift 2;;
    -o|--out-dir)    OUT_DIR="$2"; shift 2;;
    -h|--help)       usage 0;;
    -* )             echo "unknown option: $1" >&2; usage 1;;
    *)               if [[ -z "$NAME" ]]; then NAME="$1"; shift; else echo "unexpected arg: $1" >&2; usage 1; fi;;
  esac
done
[[ -n "$NAME" ]] || { echo "error: output NAME required" >&2; usage 1; }

# ---- device ------------------------------------------------------------------
if [[ -z "$SERIAL" ]]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi
[[ -n "$SERIAL" ]] || { echo "error: no adb device found" >&2; exit 1; }
adb() { command adb -s "$SERIAL" "$@"; }
export SERIAL
echo "device : $SERIAL"

command -v ffmpeg >/dev/null || { echo "error: ffmpeg not on PATH" >&2; exit 1; }
mkdir -p "$OUT_DIR"

# ---- cleanup trap ------------------------------------------------------------
OLD_TOUCHES=""
cleanup() {
  [[ -n "$OLD_TOUCHES" ]] && adb shell settings put system show_touches "$OLD_TOUCHES" >/dev/null 2>&1 || true
  [[ "$TOUCHES" == 1 && -z "$OLD_TOUCHES" ]] && adb shell settings put system show_touches 0 >/dev/null 2>&1 || true
  adb shell "rm -f $DEV_FILE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---- touch overlay -----------------------------------------------------------
if [[ "$TOUCHES" == 1 ]]; then
  OLD_TOUCHES="$(adb shell settings get system show_touches 2>/dev/null | tr -d '\r')"
  [[ "$OLD_TOUCHES" == "null" || -z "$OLD_TOUCHES" ]] && OLD_TOUCHES=0
  adb shell settings put system show_touches 1 >/dev/null
fi

# ---- cold start --------------------------------------------------------------
if [[ "$COLD" == 1 ]]; then
  echo "cold   : force-stop + launch $LAUNCH"
  adb shell am force-stop "$PKG"
  sleep 1
  adb shell am start -n "$LAUNCH" >/dev/null
  sleep "$SETTLE"
fi

# ---- prep (off-camera normalization) -----------------------------------------
if [[ -n "$PREP" ]]; then
  echo "prep   : $PREP"
  bash "$PREP" "$SERIAL" || echo "warn: prep exited non-zero" >&2
fi

# ---- record ------------------------------------------------------------------
echo "record : up to ${DURATION}s @ ${BIT_RATE} -> $DEV_FILE"
adb shell "screenrecord --time-limit $DURATION --bit-rate $BIT_RATE $DEV_FILE" &
REC_PID=$!
sleep 0.8   # let the encoder spin up before the first frame we care about

if [[ -n "$DRIVE" ]]; then
  echo "drive  : $DRIVE"
  bash "$DRIVE" "$SERIAL" || echo "warn: driver exited non-zero" >&2
else
  echo "drive  : none — perform the demo on the device now (${DURATION}s window)"
  sleep "$DURATION"
fi

# Finalize: SIGINT lets screenrecord flush a valid MP4 moov atom.
adb shell 'pkill -INT screenrecord' >/dev/null 2>&1 || true
wait "$REC_PID" 2>/dev/null || true
sleep 0.5

# ---- pull + convert ----------------------------------------------------------
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; cleanup' EXIT
adb pull "$DEV_FILE" "$TMP/rec.mp4" >/dev/null
cp "$TMP/rec.mp4" "$OUT_DIR/$NAME.mp4"

# Build the ffmpeg filter: optional crop -> fps -> scale -> two-pass palette.
vf="fps=$FPS"
if [[ "$CROP_TOP" -gt 0 || "$CROP_BOTTOM" -gt 0 ]]; then
  vf="crop=iw:ih-${CROP_TOP}-${CROP_BOTTOM}:0:${CROP_TOP},$vf"
fi
vf="$vf,scale=$WIDTH:-2:flags=lanczos"

ffmpeg -y -hide_banner -loglevel error -i "$TMP/rec.mp4" \
  -vf "$vf,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
  "$OUT_DIR/$NAME.gif"

echo "----"
echo "mp4 : $OUT_DIR/$NAME.mp4 ($(du -h "$OUT_DIR/$NAME.mp4" | cut -f1))"
echo "gif : $OUT_DIR/$NAME.gif ($(du -h "$OUT_DIR/$NAME.gif" | cut -f1))"
