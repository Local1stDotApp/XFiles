# libxfarchive: the native archive reader behind ArchiveFileSystem and zip extraction.
#
# It is a read-only subset of libarchive (7z, RAR, tar, cpio, ar, ISO 9660 and the compressed
# streams) plus our own random-access zip reader, statically linked with decoder-only builds of
# zstd, liblzma, bzip2 and lz4, and the system zlib. third_party/README.md lists the versions.
#
# Android builds come from the app module's externalNativeBuild. The JVM unit tests build the same
# sources for the host with -DXF_HOST_BUILD=ON (see app/build.gradle.kts).
cmake_minimum_required(VERSION 3.22.1)
project(xfarchive C)

option(XF_HOST_BUILD "Build for the machine running the JVM unit tests" OFF)
set(XF_JNI_INCLUDE_DIRS "" CACHE STRING "JNI headers for host builds")

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(CONFIG ${CMAKE_CURRENT_SOURCE_DIR}/config)

# Keep these lists in sync with third_party/update.sh.
set(LIBARCHIVE_SOURCES
    archive_acl archive_blake2s_ref archive_blake2sp_ref archive_check_magic archive_crc32
    archive_entry archive_entry_sparse archive_entry_xattr archive_options archive_ppmd7
    archive_ppmd8 archive_read archive_read_open_filename archive_read_set_options
    archive_read_support_filter_bzip2 archive_read_support_filter_compress
    archive_read_support_filter_gzip archive_read_support_filter_lz4
    archive_read_support_filter_xz archive_read_support_filter_zstd
    archive_read_support_format_7zip archive_read_support_format_ar
    archive_read_support_format_cpio archive_read_support_format_empty
    archive_read_support_format_iso9660
    archive_read_support_format_rar archive_read_support_format_rar5
    archive_read_support_format_raw archive_read_support_format_tar archive_string
    archive_string_sprintf archive_time archive_util archive_virtual xxhash)
list(TRANSFORM LIBARCHIVE_SOURCES PREPEND ${TP}/libarchive/)
list(TRANSFORM LIBARCHIVE_SOURCES APPEND .c)

set(ZSTD_SOURCES
    common/debug.c common/entropy_common.c common/error_private.c common/fse_decompress.c
    common/xxhash.c common/zstd_common.c decompress/huf_decompress.c decompress/zstd_ddict.c
    decompress/zstd_decompress.c decompress/zstd_decompress_block.c)
list(TRANSFORM ZSTD_SOURCES PREPEND ${TP}/zstd/lib/)

set(XZ_SOURCES
    common/tuklib_physmem.c
    liblzma/check/check.c liblzma/check/crc32_fast.c liblzma/check/crc64_fast.c
    liblzma/check/sha256.c
    liblzma/common/alone_decoder.c liblzma/common/auto_decoder.c
    liblzma/common/block_buffer_decoder.c liblzma/common/block_decoder.c
    liblzma/common/block_header_decoder.c liblzma/common/block_util.c liblzma/common/common.c
    liblzma/common/easy_decoder_memusage.c liblzma/common/easy_preset.c
    liblzma/common/file_info.c liblzma/common/filter_buffer_decoder.c
    liblzma/common/filter_common.c liblzma/common/filter_decoder.c
    liblzma/common/filter_flags_decoder.c liblzma/common/hardware_physmem.c
    liblzma/common/index.c liblzma/common/index_decoder.c liblzma/common/index_hash.c
    liblzma/common/lzip_decoder.c liblzma/common/stream_buffer_decoder.c
    liblzma/common/stream_decoder.c liblzma/common/stream_flags_common.c
    liblzma/common/stream_flags_decoder.c liblzma/common/string_conversion.c
    liblzma/common/vli_decoder.c liblzma/common/vli_size.c
    liblzma/delta/delta_common.c liblzma/delta/delta_decoder.c
    liblzma/lz/lz_decoder.c liblzma/lzma/lzma2_decoder.c liblzma/lzma/lzma_decoder.c
    liblzma/lzma/lzma_encoder_presets.c
    liblzma/simple/arm.c liblzma/simple/arm64.c liblzma/simple/armthumb.c
    liblzma/simple/ia64.c liblzma/simple/powerpc.c liblzma/simple/riscv.c
    liblzma/simple/simple_coder.c liblzma/simple/simple_decoder.c liblzma/simple/sparc.c
    liblzma/simple/x86.c)
list(TRANSFORM XZ_SOURCES PREPEND ${TP}/xz/src/)

set(BZIP2_SOURCES blocksort.c bzlib.c compress.c crctable.c decompress.c huffman.c randtable.c)
list(TRANSFORM BZIP2_SOURCES PREPEND ${TP}/bzip2/)

set(XFARCHIVE_SOURCES
    xfarchive/inflate64.c xfarchive/jni_libarchive.c xfarchive/jni_zip.c
    xfarchive/legacy_zip.c xfarchive/zip_reader.c)

# Size first: -Os, and every function and object in its own section so the linker drops what the
# JNI entry points never reach. The RAR5 reader is the exception: at -O2 it decodes about a third
# faster for 6 KB more per ABI. The other decoders run as fast at -Os.
add_compile_options(-Os -ffunction-sections -fdata-sections -fvisibility=hidden
                    -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.)
add_compile_definitions(_FILE_OFFSET_BITS=64)
# zstd's and lz4's headers mark their APIs visible, which would export them and make the linker
# keep all of them, compressors included. Only the JNI entry points are exported.
add_compile_definitions(ZSTDLIB_VISIBLE= ZSTDERRORLIB_VISIBLE= LZ4LIB_VISIBILITY=)
set_source_files_properties(${TP}/libarchive/archive_read_support_format_rar5.c
                            PROPERTIES COMPILE_OPTIONS -O2)

set(CODEC_INCLUDES ${TP}/zstd/lib ${TP}/xz/src/liblzma/api ${TP}/bzip2 ${TP}/lz4)

add_library(xf_zstd OBJECT ${ZSTD_SOURCES})
target_compile_definitions(xf_zstd PRIVATE
    ZSTD_LEGACY_SUPPORT=0 ZSTD_DISABLE_ASM ZSTD_TRACE=0 XXH_NAMESPACE=ZSTD_)
target_include_directories(xf_zstd PRIVATE ${TP}/zstd/lib ${TP}/zstd/lib/common)

add_library(xf_lzma OBJECT ${XZ_SOURCES})
target_compile_definitions(xf_lzma PRIVATE HAVE_CONFIG_H)
target_include_directories(xf_lzma PRIVATE
    ${CONFIG}/lzma ${TP}/xz/src/liblzma/api ${TP}/xz/src/liblzma/common
    ${TP}/xz/src/liblzma/check ${TP}/xz/src/liblzma/lz ${TP}/xz/src/liblzma/rangecoder
    ${TP}/xz/src/liblzma/lzma ${TP}/xz/src/liblzma/delta ${TP}/xz/src/liblzma/simple
    ${TP}/xz/src/common)

add_library(xf_bzip2 OBJECT ${BZIP2_SOURCES})

add_library(xf_lz4 OBJECT ${TP}/lz4/lz4.c)

add_library(xf_libarchive OBJECT ${LIBARCHIVE_SOURCES})
target_compile_definitions(xf_libarchive PRIVATE PLATFORM_CONFIG_H="libarchive_config.h")
target_include_directories(xf_libarchive PRIVATE ${CONFIG} ${TP}/libarchive ${CODEC_INCLUDES})

# Upstream code builds without warnings of its own here; ours gets the full set.
foreach(target xf_zstd xf_lzma xf_bzip2 xf_lz4 xf_libarchive)
    target_compile_options(${target} PRIVATE -w)
endforeach()

add_library(xfarchive SHARED ${XFARCHIVE_SOURCES}
    $<TARGET_OBJECTS:xf_libarchive> $<TARGET_OBJECTS:xf_zstd> $<TARGET_OBJECTS:xf_lzma>
    $<TARGET_OBJECTS:xf_bzip2> $<TARGET_OBJECTS:xf_lz4>)
target_compile_definitions(xfarchive PRIVATE PLATFORM_CONFIG_H="libarchive_config.h")
target_include_directories(xfarchive PRIVATE ${CONFIG} ${TP}/libarchive ${CODEC_INCLUDES})
target_compile_options(xfarchive PRIVATE -Wall -Wextra)
target_link_libraries(xfarchive PRIVATE z)

if(XF_HOST_BUILD)
    target_include_directories(xfarchive PRIVATE ${XF_JNI_INCLUDE_DIRS})
    if(APPLE)
        target_link_options(xfarchive PRIVATE -Wl,-dead_strip)
    else()
        target_link_options(xfarchive PRIVATE -Wl,--gc-sections -Wl,--no-undefined)
    endif()
else()
    target_link_options(xfarchive PRIVATE
        -Wl,--gc-sections -Wl,--no-undefined -Wl,--build-id=sha1 -Wl,--exclude-libs,ALL)
endif()
