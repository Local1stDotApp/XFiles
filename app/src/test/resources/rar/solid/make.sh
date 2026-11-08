#!/bin/sh
# Builds the solid RAR 4 fixtures from gen_sources.py's trees with RAR 6.24 for Linux, the last
# version that writes RAR 4. Runs in a container; see README.md:
#
#   docker run --rm --platform linux/amd64 -v <rar-6.24-dir>:/rar:ro -v <sources>:/src:ro \
#     -v <out>:/out -v <this-dir>:/tools:ro debian:trixie-slim sh /tools/make.sh
set -e
mk() { # mk <set> <archive> <switches...>: the set's files in its .order, which -ds keeps
  set_name=$1; archive=$2; shift 2
  (cd "/src/$set_name" && /rar/rar a -ma4 -ds -idq -y "$@" "/out/$archive" $(cat "/src/$set_name.order"))
}
mk text   rar4_solid_lz.rar        -s -m5 -md64k -mc-
mk text   rar4_solid_ppmd.rar      -s -m5 -mct+
mk text   rar4_solid_groups.rar    -s3 -m5 -mc-
mk text   rar4_solid_volumes.rar   -s -m5 -md64k -mct -v12k
mk edge   rar4_solid_edge_lz.rar   -s -m5 -mc-
mk edge   rar4_solid_edge_auto.rar -s -m5 -mct
mk edge   rar4_solid_edge_ppmd.rar -s -m5 -mct+
mk x86    rar4_solid_e8.rar        -s -m5 -mcE+
mk x86    rar4_solid_ppmd_e8.rar   -s -m5 -mct+ -mcE+
mk audio  rar4_solid_audio.rar     -s -m5 -mc4a+
mk rgb    rar4_solid_rgb.rar       -s -m5 -mcC+
mk delta  rar4_solid_delta.rar     -s -m5 -mc6d+
mk stored rar4_solid_stored.rar    -s -m5 -ms*.jpg
ls -l /out
