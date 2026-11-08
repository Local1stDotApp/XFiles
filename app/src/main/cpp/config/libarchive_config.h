/*
 * Platform config for the read-only libarchive subset in libxfarchive (PLATFORM_CONFIG_H).
 *
 * libarchive normally gets this from its configure step. The subset only reads archives, so it
 * needs much less than a full build: no disk reader or writer, no ACLs, xattrs, crypto, iconv or
 * XML. Everything below exists on Android (bionic, API 26 and up) and on the Linux and macOS hosts
 * that run the JVM unit tests, so one header serves all of them.
 */
#ifndef XF_LIBARCHIVE_CONFIG_H
#define XF_LIBARCHIVE_CONFIG_H

#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LANGINFO_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_TIME_H 1
#define HAVE_UNISTD_H 1
#define HAVE_WCHAR_H 1

#define HAVE_EILSEQ 1
#define HAVE_GETEGID 1
#define HAVE_GETEUID 1
#define HAVE_LOCALTIME_R 1
#define HAVE_MBRTOWC 1
#define HAVE_MKSTEMP 1
/* bionic has had nl_langinfo since API 26. On Android it reports UTF-8. */
#define HAVE_NL_LANGINFO 1
#define HAVE_TIMEGM 1
#define HAVE_WCRTOMB 1
#define HAVE_WCSLEN 1
#define HAVE_WCTOMB 1
#define HAVE_WMEMCMP 1
#define HAVE_WMEMCPY 1
#define HAVE_WMEMMOVE 1

#define HAVE_DECL_INT32_MAX 1
#define HAVE_DECL_INT32_MIN 1
#define HAVE_DECL_INT64_MAX 1
#define HAVE_DECL_INT64_MIN 1
#define HAVE_DECL_INTMAX_MAX 1
#define HAVE_DECL_INTMAX_MIN 1
#define HAVE_DECL_SIZE_MAX 1
#define HAVE_DECL_SSIZE_MAX 1
#define HAVE_DECL_UINT32_MAX 1
#define HAVE_DECL_UINT64_MAX 1
#define HAVE_DECL_UINTMAX_MAX 1

/* Codecs: the system zlib, plus decoders built from third_party/. */
#define HAVE_LIBZ 1
#define HAVE_ZLIB_H 1
#define HAVE_LIBBZ2 1
#define HAVE_BZLIB_H 1
#define HAVE_LIBLZMA 1
#define HAVE_LZMA_H 1
#define HAVE_LIBZSTD 1
#define HAVE_ZSTD_H 1
#define HAVE_LIBLZ4 1
#define HAVE_LZ4_H 1

#endif /* XF_LIBARCHIVE_CONFIG_H */
