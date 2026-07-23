# Shared helpers for scripted XFiles UI demos, driven over adb.
# Source this from a driver script; it expects $SERIAL (arg $1 or env).
#
# Rows are located by their on-screen text via `uiautomator dump` — XFiles is
# Jetpack Compose but exposes row labels to the accessibility tree, so bounds
# lookups survive layout shifts (positions move as the tree expands) and work
# across devices/resolutions. Much sturdier than hardcoded pixel taps.

SERIAL="${SERIAL:-${1:-}}"
[[ -n "$SERIAL" ]] || { echo "lib.sh: no device serial" >&2; return 1 2>/dev/null || exit 1; }
adb() { command adb -s "$SERIAL" "$@"; }

_UI="$(mktemp)"
trap 'rm -f "$_UI"' EXIT

# Dump the current UI hierarchy to $_UI. Retries: uiautomator occasionally fails
# to reach an idle state, and one empty dump mid-run would derail a whole capture.
dump_ui() {
  local i
  for i in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/xf-ui.xml >/dev/null 2>&1 || true
    adb pull /sdcard/xf-ui.xml "$_UI" >/dev/null 2>&1 || true
    [[ -s "$_UI" ]] && grep -q '<hierarchy' "$_UI" && return 0
    sleep 0.4
  done
  return 1
}

# Echo "cx cy" — the center of the first node whose text exactly equals $1.
# Empty output if not currently visible.
bounds_center() {
  local text="$1"
  # Match one node's tag: text="..." ... bounds="[x1,y1][x2,y2]"
  local re='text="'"$text"'"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"'
  local hit
  hit="$(grep -oE "$re" "$_UI" 2>/dev/null | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')"
  [[ -n "$hit" ]] || return 1
  echo "$hit" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk '{printf "%d %d", int(($1+$3)/2), int(($2+$4)/2)}'
}

# tap_text "Label" [pause] — dump, find the row, tap its center, then pause.
tap_text() {
  local text="$1" pause="${2:-1.4}"
  dump_ui
  local xy; xy="$(bounds_center "$text")" || { echo "tap_text: '$text' not found" >&2; return 1; }
  echo "  tap  '$text'  @ $xy"
  adb shell input tap $xy
  sleep "$pause"
}

# tap_xy X Y [pause]
tap_xy() { echo "  tap  @ $1 $2"; adb shell input tap "$1" "$2"; sleep "${3:-1.4}"; }

# tap_desc "content-desc" [pause] — tap a node located by its accessibility label
# (toolbar actions like "Copy to…" expose these, not text).
tap_desc() {
  local desc="$1" pause="${2:-1.4}"
  dump_ui
  local xy; xy="$(python3 - "$_UI" "$desc" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r'content-desc="' + re.escape(sys.argv[2]) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups()); print((x1 + x2) // 2, (y1 + y2) // 2)
PY
)"
  [[ -n "$xy" ]] || { echo "tap_desc: '$desc' not found" >&2; return 1; }
  echo "  tap  [$desc] @ $xy"; adb shell input tap $xy; sleep "$pause"
}

# select_row "Label" [pause] — tick the right-edge Select circle on the row whose
# label equals Label (used to multi-select files before a copy/move).
select_row() {
  local text="$1" pause="${2:-0.7}"
  dump_ui
  local xy; xy="$(python3 - "$_UI" "$text" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
lab = [m for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
       if m.group(1) == sys.argv[2]]
if lab:
    ly = (int(lab[0].group(3)) + int(lab[0].group(5))) // 2
    best = None
    for m in re.finditer(r'content-desc="Select"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.groups()); cy = (y1 + y2) // 2
        if best is None or abs(cy - ly) < abs(best[1] - ly):
            best = ((x1 + x2) // 2, cy)
    if best and abs(best[1] - ly) < 60:
        print(best[0], best[1])
PY
)"
  [[ -n "$xy" ]] || { echo "select_row: '$text' not found" >&2; return 1; }
  echo "  select '$text' @ $xy"; adb shell input tap $xy; sleep "$pause"
}

# hold [seconds] — a deliberate pause so the viewer can read the frame.
hold() { sleep "${1:-1.2}"; }

# swipe_up [pause] — scroll the list up by ~40% of the screen.
swipe_up() {
  local h w; read -r w h < <(adb shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/')
  adb shell input swipe $((w/2)) $((h*62/100)) $((w/2)) $((h*22/100)) 450
  sleep "${1:-1.0}"
}

# top_reached — true when the first storage root sits high on screen (i.e. the
# list is scrolled to the very top). Used to stop scroll_top reliably even when a
# long app list is open above it.
top_reached() {
  dump_ui || return 1
  local xy; xy="$(python3 "$_TS" "$_UI" center "Internal shared storage")"
  [[ -n "$xy" ]] || return 1
  (( ${xy#* } < 700 ))
}

# scroll_top — drag the list to the very top and confirm it. Returns immediately
# when already there, so it's cheap to call between collapses.
scroll_top() {
  local h w; read -r w h < <(adb shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/')
  local i
  for i in $(seq 1 14); do
    top_reached && break
    adb shell input swipe $((w/2)) $((h*20/100)) $((w/2)) $((h*90/100)) 160
  done
  sleep 0.2
}

_TS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tree_state.py"

# reset_home — normalize to the clean storage-roots home, with an EMPTY remembered
# expansion set, regardless of what the restored session left open. The app keeps
# sub-expansions remembered under a collapsed parent (by design), so simply
# collapsing a top-level root would leave e.g. /data still in the set and it would
# spring back open on the next expand. Instead: expose each demo subtree, then
# collapse bottom-up (deepest row first) until nothing is expanded. Run OFF-camera
# (via --prep). Settings and permissions are untouched.
# reset_shallow — fast: just collapse whatever is currently open and scroll to top.
# Enough for demos that don't re-expand a deep tree (e.g. file ops). Does NOT clear
# remembered sub-expansions hidden under collapsed parents — use reset_home for that.
reset_shallow() {
  scroll_top
  local i xy
  for i in $(seq 1 12); do
    dump_ui; xy="$(python3 "$_TS" "$_UI" collapse-deepest)"
    [[ -n "$xy" ]] || break
    echo "  reset: collapse @ $xy"; adb shell input tap $xy; sleep 0.4; scroll_top
  done
}

reset_home() {
  scroll_top
  # Expose remembered descendants along the demo paths so every open chevron
  # renders and can be collapsed — including buggy open-but-empty ones. Order
  # matters: a parent must be open before its child row exists.
  local node xy i s
  for node in "Root" "data" "App manager" "Installed"; do
    dump_ui
    xy="$(python3 "$_TS" "$_UI" expand-chevron "$node")" && [[ -n "$xy" ]] && {
      echo "  reset: expose $node"; adb shell input tap $xy; sleep 0.7; scroll_top; }
  done
  # One level deeper on the app path: expose the first Installed app so a remembered
  # Components/APK expansion under it renders and can be collapsed too.
  dump_ui
  xy="$(python3 "$_TS" "$_UI" first-app)" && [[ -n "$xy" ]] && {
    echo "  reset: expose first app"; adb shell input tap $xy; sleep 0.9; scroll_top; }
  # Pass 1 — collapse DEEP open chevrons (depth >= 2, x1 >= 100) first. These can
  # sit below the fold (e.g. /data/app, five rows into a 40-entry listing), so scan
  # downward to find them. Collapsing a parent would only hide, not clear, them.
  for i in $(seq 1 20); do
    xy=""; scroll_top
    for s in 1 2 3 4 5 6; do
      dump_ui
      xy="$(python3 "$_TS" "$_UI" collapse-deepest 100)"
      [[ -n "$xy" ]] && break
      swipe_up 0.3
    done
    [[ -n "$xy" ]] || break
    echo "  reset: collapse deep @ $xy"; adb shell input tap $xy; sleep 0.5
  done
  # Pass 2 — collapse the shallow trunk (data/Installed, then Root/App manager),
  # all on-screen from the top.
  scroll_top
  for i in $(seq 1 12); do
    dump_ui; xy="$(python3 "$_TS" "$_UI" collapse-deepest)"
    [[ -n "$xy" ]] || break
    echo "  reset: collapse @ $xy"; adb shell input tap $xy; sleep 0.4; scroll_top
  done
}
