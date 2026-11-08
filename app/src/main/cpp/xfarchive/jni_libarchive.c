/*
 * JNI surface of the libarchive subset: every format except zip, read front to back.
 *
 * A handle is used by one thread at a time. Kotlin walks the entries with next() and reads the
 * current entry's bytes with read(); libarchive skips whatever is left of an entry by itself.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "archive.h"
#include "archive_entry.h"
#include "jni_common.h"

/* Must match NativeArchive.FORMAT_*. */
#define FORMAT_7ZIP 0x01
#define FORMAT_RAR 0x02
#define FORMAT_TAR 0x04
#define FORMAT_CPIO 0x08
#define FORMAT_AR 0x10
#define FORMAT_ISO9660 0x20
#define FORMAT_RAW 0x40

#define BLOCK_SIZE (64 * 1024)
#define READ_BUFFER (256 * 1024)

/* Headers in a row that repeat without the reader consuming anything, before giving up. */
#define MAX_STALLS 3

typedef struct {
    struct archive *a;
    /* Loop guard, see next(). */
    int64_t last_pos;
    char *last_name;
    int stalls;
    uint8_t buf[READ_BUFFER];
} reader;

static void throw_archive(JNIEnv *env, struct archive *a) {
    xf_throw_io(env, archive_error_string(a));
}

static int enable(struct archive *a, jint formats) {
    int ok = 1;
    if (formats & FORMAT_7ZIP) ok &= archive_read_support_format_7zip(a) == ARCHIVE_OK;
    if (formats & FORMAT_RAR) {
        ok &= archive_read_support_format_rar(a) == ARCHIVE_OK;
        ok &= archive_read_support_format_rar5(a) == ARCHIVE_OK;
    }
    if (formats & FORMAT_TAR) ok &= archive_read_support_format_tar(a) == ARCHIVE_OK;
    if (formats & FORMAT_CPIO) ok &= archive_read_support_format_cpio(a) == ARCHIVE_OK;
    if (formats & FORMAT_AR) ok &= archive_read_support_format_ar(a) == ARCHIVE_OK;
    if (formats & FORMAT_ISO9660) ok &= archive_read_support_format_iso9660(a) == ARCHIVE_OK;
    if (formats & FORMAT_RAW) ok &= archive_read_support_format_raw(a) == ARCHIVE_OK;
    /* Every decompression filter, so the content decides rather than the file name. */
    ok &= archive_read_support_filter_gzip(a) == ARCHIVE_OK;
    ok &= archive_read_support_filter_bzip2(a) == ARCHIVE_OK;
    ok &= archive_read_support_filter_xz(a) == ARCHIVE_OK;
    ok &= archive_read_support_filter_lzma(a) == ARCHIVE_OK;
    ok &= archive_read_support_filter_lzip(a) == ARCHIVE_OK;
    ok &= archive_read_support_filter_zstd(a) == ARCHIVE_OK;
    ok &= archive_read_support_filter_lz4(a) == ARCHIVE_OK;
    ok &= archive_read_support_filter_compress(a) == ARCHIVE_OK;
    return ok;
}

/*
 * Opens the archive (several paths for a multi-volume RAR). formats is a FORMAT_* mask. With
 * decode_skipped, members passed over on the way to the one read are decoded where later ones
 * continue them (solid RAR 4), so those can be read; listing leaves it off.
 */
JNIEXPORT jlong XF_JNI(open)(JNIEnv *env, jclass clazz, jobjectArray paths, jint formats,
                             jboolean decode_skipped) {
    jsize count = paths != NULL ? (*env)->GetArrayLength(env, paths) : 0;
    char **list;
    reader *r;
    int rc;
    jsize i;
    (void)clazz;
    if (count == 0) {
        xf_throw_io(env, "Missing path");
        return 0;
    }
    r = calloc(1, sizeof *r);
    list = calloc((size_t)count + 1, sizeof *list);
    if (r == NULL || list == NULL || (r->a = archive_read_new()) == NULL) {
        free(r);
        free(list);
        xf_throw_io(env, "Out of memory");
        return 0;
    }
    if (!enable(r->a, formats)) {
        throw_archive(env, r->a);
        goto fail;
    }
    if (decode_skipped && (formats & FORMAT_RAR) &&
        archive_read_set_format_option(r->a, "rar", "solid_skip", "decode") != ARCHIVE_OK) {
        throw_archive(env, r->a);
        goto fail;
    }
    for (i = 0; i < count; i++) {
        jbyteArray path = (jbyteArray)(*env)->GetObjectArrayElement(env, paths, i);
        list[i] = xf_dup_bytes(env, path);
        (*env)->DeleteLocalRef(env, path);
        if (list[i] == NULL) goto fail;
    }
    rc = count == 1 ? archive_read_open_filename(r->a, list[0], BLOCK_SIZE)
                    : archive_read_open_filenames(r->a, (const char **)list, BLOCK_SIZE);
    if (rc != ARCHIVE_OK) {
        throw_archive(env, r->a);
        goto fail;
    }
    for (i = 0; i < count; i++) free(list[i]);
    free(list);
    return (jlong)(intptr_t)r;

fail:
    for (i = 0; i < count; i++) free(list[i]);
    free(list);
    archive_read_free(r->a);
    free(r);
    return 0;
}

/*
 * Moves to the next entry and returns its path as UTF-8 bytes (empty when it has none), or null
 * at the end. info receives the file type (AE_IF*), the size (-1 if unknown), the modification
 * time in ms (Long.MIN_VALUE if unknown) and flags: 1 = hard link, 2 = encrypted.
 */
JNIEXPORT jbyteArray XF_JNI(next)(JNIEnv *env, jclass clazz, jlong handle, jlongArray info) {
    reader *r = (reader *)(intptr_t)handle;
    struct archive_entry *e = NULL;
    const char *name;
    jlong v[4];
    int rc;
    (void)clazz;
    rc = archive_read_next_header(r->a, &e);
    if (rc == ARCHIVE_EOF) return NULL;
    if ((rc != ARCHIVE_OK && rc != ARCHIVE_WARN) || e == NULL) {
        throw_archive(env, r->a);
        return NULL;
    }
    v[0] = (jlong)archive_entry_filetype(e);
    v[1] = archive_entry_size_is_set(e) ? (jlong)archive_entry_size(e) : -1;
    v[2] = archive_entry_mtime_is_set(e)
               ? (jlong)archive_entry_mtime(e) * 1000 + archive_entry_mtime_nsec(e) / 1000000
               : INT64_MIN;
    v[3] = (archive_entry_hardlink_is_set(e) ? 1 : 0) | (archive_entry_is_encrypted(e) ? 2 : 0);
    (*env)->SetLongArrayRegion(env, info, 0, 4, v);
    name = archive_entry_pathname_utf8(e);
    if (name == NULL) name = archive_entry_pathname(e);
    if (name == NULL) name = "";
    /*
     * A damaged archive can make a reader hand back the same header forever without reading on.
     * libarchive issue 3492 is one: after a truncated solid RAR5 fails a header, a caller that
     * carries on gets the same failure and an empty entry again and again. This loop already
     * stops at the failure; the guard catches the same pattern if it ever comes as warnings,
     * since nothing above can interrupt a listing. A header that repeats the previous one's name
     * while the format reader has consumed nothing more counts as a stall. Formats that keep
     * their whole directory in memory (7z, ISO) list entries without consuming, but each entry
     * has a name of its own.
     */
    {
        int64_t pos = archive_filter_bytes(r->a, 0); /* what the format reader consumed */
        if (pos == r->last_pos && r->last_name != NULL && strcmp(name, r->last_name) == 0) {
            if (++r->stalls >= MAX_STALLS) {
                xf_throw_io(env, "Damaged archive: the same entry keeps repeating");
                return NULL;
            }
        } else {
            r->stalls = 0;
        }
        r->last_pos = pos;
        free(r->last_name);
        r->last_name = strdup(name);
    }
    return xf_bytes(env, name, strlen(name));
}

/* Reads the current entry into buf[off..off+len). Returns the count, or -1 at its end. */
JNIEXPORT jint XF_JNI(read)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray buf, jint off,
                            jint len) {
    reader *r = (reader *)(intptr_t)handle;
    la_ssize_t n;
    (void)clazz;
    if (!xf_check_range(env, buf, off, len)) return -1;
    if (len == 0) return 0;
    n = archive_read_data(r->a, r->buf, len < READ_BUFFER ? (size_t)len : READ_BUFFER);
    if (n < 0) {
        throw_archive(env, r->a);
        return -1;
    }
    if (n == 0) return -1;
    (*env)->SetByteArrayRegion(env, buf, off, (jsize)n, (const jbyte *)r->buf);
    return (jint)n;
}

/* The outermost decompression filter (ARCHIVE_FILTER_*), 0 when the data is not compressed. */
JNIEXPORT jint XF_JNI(filter)(JNIEnv *env, jclass clazz, jlong handle) {
    reader *r = (reader *)(intptr_t)handle;
    (void)env;
    (void)clazz;
    return archive_filter_code(r->a, 0);
}

JNIEXPORT void XF_JNI(close)(JNIEnv *env, jclass clazz, jlong handle) {
    reader *r = (reader *)(intptr_t)handle;
    (void)env;
    (void)clazz;
    if (r == NULL) return;
    archive_read_free(r->a);
    free(r->last_name);
    free(r);
}
