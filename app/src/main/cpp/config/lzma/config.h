/*
 * liblzma config for libxfarchive: decoders only, single-threaded, no CPU-specific CRC paths.
 * Mirrors what xz's CMakeLists.txt defines with XZ_ENCODERS="" and XZ_THREADS=no.
 */
#define HAVE_STDBOOL_H 1
#define HAVE__BOOL 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_VISIBILITY 0
#define TUKLIB_SYMBOL_PREFIX lzma_
#define TUKLIB_PHYSMEM_SYSCONF 1

#define HAVE_DECODERS 1
#define HAVE_DECODER_LZMA1 1
#define HAVE_DECODER_LZMA2 1
#define HAVE_DECODER_DELTA 1
#define HAVE_DECODER_X86 1
#define HAVE_DECODER_ARM 1
#define HAVE_DECODER_ARMTHUMB 1
#define HAVE_DECODER_ARM64 1
#define HAVE_DECODER_POWERPC 1
#define HAVE_DECODER_IA64 1
#define HAVE_DECODER_SPARC 1
#define HAVE_DECODER_RISCV 1

#define HAVE_CHECK_CRC32 1
#define HAVE_CHECK_CRC64 1
#define HAVE_CHECK_SHA256 1

#define HAVE_LZIP_DECODER 1
