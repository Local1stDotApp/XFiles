#!/bin/sh
# Builds the RAR 2.x fixtures from gen_sources.py's trees with RAR 2.80 for Linux (2001), which
# needs a 32-bit x86 system. RAR updates archives through a temporary file in the working folder,
# so the sources are copied somewhere writable first.
#
#   docker run --rm --platform linux/386 -v <rar-2.80-dir>:/rar:ro -v <sources>:/src:ro \
#     -v <out>:/out -v <this-dir>:/tools:ro debian:bookworm-slim sh /tools/make.sh
set -e
cp -r /src /tmp/src
mk() { # mk <set> <archive> <switches...>: the set's files in its .order, which -ds keeps
  set_name=$1; archive=$2; shift 2
  (cd "/tmp/src/$set_name" && /rar/rar a -idp -ds -y "$@" "/out/$archive" $(cat "/tmp/src/$set_name.order")) > /dev/null
}
mk edge  rar2_lz.rar          -m3
mk text  rar2_solid_lz.rar    -s -m5 -md64
mk far   rar2_far.rar         -m5 -md1024
mk audio rar2_audio.rar       -m5 -mmf
mk mixed rar2_solid_mm.rar    -s -m5 -mm
mk edge  rar2_solid_edge.rar  -s -m5
mk edge  rar2_volumes.rar     -s -m5 -v4k
mk small rar2_owner_rr.rar    -m5 -ow -rr
mk small rar2_encrypted.rar   -m5 -ppassword
printf 'An archive comment.\r\n' > /tmp/comment.txt
mk small rar2_comments.rar    -m5 -z/tmp/comment.txt
cd /tmp && printf 'A file comment.\r\n' | /rar/rar cf -idp -y /out/rar2_comments.rar readme.txt > /dev/null
ls -l /out
