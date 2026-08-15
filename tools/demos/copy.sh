#!/usr/bin/env bash
# Demo driver: direct dual-pane file copy. Set Documents in the other pane, return
# to Download, multi-select two files, then copy straight to that visible target.
# Run via tools/record-demo.sh --prep tools/demos/_reset-shallow.sh.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh "${1:-${SERIAL:-}}"

hold 0.8                              # settle on the source pane
tap_desc_prefix "Switch pane:" 1.0   # open the destination pane
reset_shallow                         # normalize this pane too (prep reset the source)
tap_text "Internal shared storage" 1.0
swipe_up 0.5
tap_text "Documents" 0.8             # focus the destination folder
tap_desc_prefix "Switch pane:" 1.0   # return to the source pane
tap_text "Download" 1.2              # expand Download in the tree

select_row "GPhotosUnlimited-v2.zip" 0.6
select_row "icf_probe.bin" 0.8       # two files ticked -> selection toolbar

tap_desc "Copy to Documents" 1.4     # no picker: the other pane is the destination
hold 2.4
