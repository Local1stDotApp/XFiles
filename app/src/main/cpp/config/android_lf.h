/*
 * libarchive's archive.h includes this on Android. Upstream's copy (contrib/android) renames the
 * file functions to their 64-bit forms for builds that can't use _FILE_OFFSET_BITS. libxfarchive
 * builds with _FILE_OFFSET_BITS=64, which bionic honours at our API level (26), so files past
 * 2 GB already work on 32-bit ABIs and there is nothing to add here.
 */
#ifndef XF_ANDROID_LF_H
#define XF_ANDROID_LF_H
#endif
