#!/usr/bin/env bash
# Prep step: normalize XFiles to the clean storage-roots home before a capture.
# Use with tools/record-demo.sh --prep tools/demos/_reset.sh (runs off-camera).
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh "${1:-${SERIAL:-}}"
reset_home
