#!/usr/bin/env bash
# Demo driver: App manager. From the storage-roots home, open the app tree,
# expand the Installed group (real icons load in), then drill into one app to
# reveal its Components node + base.apk. Run via tools/record-demo.sh --cold.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh "${1:-${SERIAL:-}}"

hold 1.0                      # settle on the home screen
tap_text "App manager" 1.6    # expand -> Installed (N) / System (N)
tap_text "Installed" 1.8      # expand -> installed apps with real icons
hold 1.0

# Drill into the first installed app to show its Components + APK splits.
# The first app is the row just below the "Installed / N apps" header; grab it
# from the freshly-dumped hierarchy rather than hardcoding a name.
first_app_tap() {
  dump_ui
  python3 - "$_UI" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
nodes = []
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
    nodes.append((t, x1, y1, x2, y2))
inst = next((n for n in nodes if n[0] == "Installed"), None)
if not inst:
    sys.exit(1)
# Header subtitle "N apps" sits just under "Installed"; app names start below it,
# indented to the same left edge. Pick the shallowest-left label below the header.
below = [n for n in nodes if n[2] > inst[4] and not re.fullmatch(r"\d+ apps", n[0])
         and n[0] not in ("System",)]
below.sort(key=lambda n: n[2])
if not below:
    sys.exit(1)
t, x1, y1, x2, y2 = below[0]
print(int((x1 + x2) / 2), int((y1 + y2) / 2))
PY
}

if xy="$(first_app_tap)" && [[ -n "$xy" ]]; then
  echo "  tap  first app @ $xy"
  adb shell input tap $xy       # expand app -> Components, base.apk, split_config.*
  sleep 1.9
  swipe_up 1.2                  # show the components / apk children
fi
hold 1.2
