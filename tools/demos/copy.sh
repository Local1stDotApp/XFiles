#!/usr/bin/env bash
# Demo driver: file copy with an explicit destination. Open Download, multi-select
# two files, Copy to… → browse the full-screen picker to Documents → Copy here.
# Run via tools/record-demo.sh --prep tools/demos/_reset-shallow.sh.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh "${1:-${SERIAL:-}}"

hold 0.8                              # settle on the home screen
tap_text "Download" 1.2              # expand Download in the tree

select_row "GPhotosUnlimited-v2.zip" 0.6
select_row "icf_probe.bin" 0.8       # two files ticked -> selection toolbar

tap_desc "Copy to…" 1.4              # full-screen destination picker (defaults to
hold 1.0                             #   the other pane's folder) …
tap_text "Copy here" 1.4            # … commit the copy → back to the browser
hold 2.4
