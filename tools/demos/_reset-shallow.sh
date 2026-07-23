#!/usr/bin/env bash
# Prep step: fast shallow reset (collapse what's open, scroll to top). For demos
# that don't re-open a deep tree — e.g. the file-copy flow. Off-camera via --prep.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh "${1:-${SERIAL:-}}"
reset_shallow
