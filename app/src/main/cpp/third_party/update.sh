#!/usr/bin/env bash
# Refreshes the vendored C sources under third_party/ from upstream release tarballs.
#
# Only the files libxfarchive compiles are kept, plus the headers they include and each
# project's licence. Extract the release tarballs listed in README.md, check their published
# checksums, then run:
#
#   update.sh <libarchive-x.y.z> <zstd-x.y.z> <xz-x.y.z> <bzip2-x.y.z> <lz4-x.y.z>
#
# and update the versions in README.md. The source lists below must match CMakeLists.txt.
set -euo pipefail

if [ $# -ne 5 ]; then
  sed -n '2,10p' "$0"
  exit 2
fi
LA=$(cd "$1" && pwd)
ZSTD=$(cd "$2" && pwd)
XZ=$(cd "$3" && pwd)
BZ=$(cd "$4" && pwd)
LZ4=$(cd "$5" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
CPP=$(dirname "$HERE")
CC=${CC:-cc}

LA_SOURCES="archive_acl archive_blake2s_ref archive_blake2sp_ref archive_check_magic archive_crc32
archive_entry archive_entry_sparse archive_entry_xattr archive_options archive_ppmd7 archive_ppmd8
archive_read archive_read_open_filename archive_read_set_options
archive_read_support_filter_bzip2 archive_read_support_filter_compress
archive_read_support_filter_gzip archive_read_support_filter_lz4 archive_read_support_filter_xz
archive_read_support_filter_zstd archive_read_support_format_7zip archive_read_support_format_ar
archive_read_support_format_cpio archive_read_support_format_empty archive_read_support_format_iso9660
archive_read_support_format_rar archive_read_support_format_rar5 archive_read_support_format_raw
archive_read_support_format_tar archive_string archive_string_sprintf archive_time archive_util
archive_virtual xxhash"
ZSTD_SOURCES="common/debug.c common/entropy_common.c common/error_private.c common/fse_decompress.c
common/xxhash.c common/zstd_common.c decompress/huf_decompress.c decompress/zstd_ddict.c
decompress/zstd_decompress.c decompress/zstd_decompress_block.c"
XZ_SOURCES="common/tuklib_physmem.c
liblzma/check/check.c liblzma/check/crc32_fast.c liblzma/check/crc64_fast.c liblzma/check/sha256.c
liblzma/common/alone_decoder.c liblzma/common/auto_decoder.c liblzma/common/block_buffer_decoder.c
liblzma/common/block_decoder.c liblzma/common/block_header_decoder.c liblzma/common/block_util.c
liblzma/common/common.c liblzma/common/easy_decoder_memusage.c liblzma/common/easy_preset.c
liblzma/common/file_info.c liblzma/common/filter_buffer_decoder.c liblzma/common/filter_common.c
liblzma/common/filter_decoder.c liblzma/common/filter_flags_decoder.c
liblzma/common/hardware_physmem.c liblzma/common/index.c liblzma/common/index_decoder.c
liblzma/common/index_hash.c liblzma/common/lzip_decoder.c liblzma/common/stream_buffer_decoder.c
liblzma/common/stream_decoder.c liblzma/common/stream_flags_common.c
liblzma/common/stream_flags_decoder.c liblzma/common/string_conversion.c
liblzma/common/vli_decoder.c liblzma/common/vli_size.c
liblzma/delta/delta_common.c liblzma/delta/delta_decoder.c
liblzma/lz/lz_decoder.c liblzma/lzma/lzma2_decoder.c liblzma/lzma/lzma_decoder.c
liblzma/lzma/lzma_encoder_presets.c
liblzma/simple/arm.c liblzma/simple/arm64.c liblzma/simple/armthumb.c liblzma/simple/ia64.c
liblzma/simple/powerpc.c liblzma/simple/riscv.c liblzma/simple/simple_coder.c
liblzma/simple/simple_decoder.c liblzma/simple/sparc.c liblzma/simple/x86.c"
BZ_SOURCES="blocksort.c bzlib.c compress.c crctable.c decompress.c huffman.c randtable.c"

# Prints the headers under $1 that the given compile pulls in, relative to $1.
headers_under() {
  local root=$1 src=$2
  shift 2
  "$CC" -MM "$@" "$src" | tr ' \\' '\n\n' | grep '\.h$' | while read -r h; do
    case $(cd "$(dirname "$h")" && pwd)/$(basename "$h") in
      "$root"/*) echo "${h#"$root"/}" ;;
    esac
  done
}

copy_rel() { # root relpath dest
  mkdir -p "$3/$(dirname "$2")"
  cp "$1/$2" "$3/$2"
}

echo "libarchive"
rm -rf "$HERE/libarchive" && mkdir -p "$HERE/libarchive"
LA_FLAGS=(-DPLATFORM_CONFIG_H="\"$CPP/config/libarchive_config.h\"" -I"$LA/libarchive"
  -I"$ZSTD/lib" -I"$XZ/src/liblzma/api" -I"$BZ" -I"$LZ4/lib")
for f in $LA_SOURCES; do
  cp "$LA/libarchive/$f.c" "$HERE/libarchive/"
  headers_under "$LA/libarchive" "$LA/libarchive/$f.c" "${LA_FLAGS[@]}"
done | sort -u | while read -r h; do copy_rel "$LA/libarchive" "$h" "$HERE/libarchive"; done
cp "$LA/COPYING" "$HERE/libarchive/COPYING"
# Our changes to libarchive (README.md). A patch that no longer applies needs merging by hand.
for p in "$HERE"/patches/libarchive-*.patch; do
  patch -s -p1 -d "$HERE/libarchive" < "$p"
done

echo "zstd"
rm -rf "$HERE/zstd" && mkdir -p "$HERE/zstd/lib"
Z_FLAGS=(-DZSTD_LEGACY_SUPPORT=0 -DZSTD_DISABLE_ASM -DZSTD_TRACE=0 -I"$ZSTD/lib" -I"$ZSTD/lib/common")
for f in $ZSTD_SOURCES; do
  copy_rel "$ZSTD/lib" "$f" "$HERE/zstd/lib"
  headers_under "$ZSTD/lib" "$ZSTD/lib/$f" "${Z_FLAGS[@]}"
done | sort -u | while read -r h; do copy_rel "$ZSTD/lib" "$h" "$HERE/zstd/lib"; done
cp "$ZSTD/LICENSE" "$HERE/zstd/LICENSE"

echo "xz"
rm -rf "$HERE/xz" && mkdir -p "$HERE/xz/src"
X_FLAGS=(-DHAVE_CONFIG_H -I"$CPP/config/lzma" -I"$XZ/src/liblzma/api" -I"$XZ/src/liblzma/common"
  -I"$XZ/src/liblzma/check" -I"$XZ/src/liblzma/lz" -I"$XZ/src/liblzma/rangecoder"
  -I"$XZ/src/liblzma/lzma" -I"$XZ/src/liblzma/delta" -I"$XZ/src/liblzma/simple" -I"$XZ/src/common")
for f in $XZ_SOURCES; do
  copy_rel "$XZ/src" "$f" "$HERE/xz/src"
  headers_under "$XZ/src" "$XZ/src/$f" "${X_FLAGS[@]}"
done | sort -u | while read -r h; do copy_rel "$XZ/src" "$h" "$HERE/xz/src"; done
cp "$XZ/COPYING" "$XZ/COPYING.0BSD" "$HERE/xz/"

echo "bzip2"
rm -rf "$HERE/bzip2" && mkdir -p "$HERE/bzip2"
for f in $BZ_SOURCES bzlib.h bzlib_private.h LICENSE; do cp "$BZ/$f" "$HERE/bzip2/"; done

echo "lz4"
rm -rf "$HERE/lz4" && mkdir -p "$HERE/lz4"
cp "$LZ4/lib/lz4.c" "$LZ4/lib/lz4.h" "$HERE/lz4/"
cp "$LZ4/lib/LICENSE" "$HERE/lz4/LICENSE"

du -sh "$HERE"/libarchive "$HERE"/zstd "$HERE"/xz "$HERE"/bzip2 "$HERE"/lz4
