#!/usr/bin/env bash
# Demo driver: Root access. From the storage-roots home, open the real
# filesystem and reveal /data — adb, anr, app, app-private, dalvik-cache — the
# dirs a normal app can't even list. Run via tools/record-demo.sh --cold.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh "${1:-${SERIAL:-}}"

hold 1.0                 # settle on the home screen
tap_text "Root" 1.5      # expand / -> apex, bin, cache, config, data, ...
hold 0.7
tap_text "data" 1.8      # expand /data -> adb, anr, app, app-private, dalvik-cache, ...
swipe_up 1.6             # lift /data's children clear of the floating toolbar
hold 1.8                 # hold on the payoff — dirs a normal app can't list
