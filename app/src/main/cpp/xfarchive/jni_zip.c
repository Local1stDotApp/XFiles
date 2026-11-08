/* JNI surface of the zip reader (zip_reader.h). */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "jni_common.h"
#include "zip_reader.h"

/*
 * Most a zipRead hands over. It reads into the stack, so an entry stream costs no allocation
 * here: with one per entry and several threads extracting, a large per-entry buffer kept the
 * threads waiting on the allocator.
 */
#define READ_CHUNK (64 * 1024)
#define ENTRY_RECORD 45 /* fixed part of one packed listing record, see zipListing */

static void put16(uint8_t **p, uint16_t v) {
    (*p)[0] = (uint8_t)v;
    (*p)[1] = (uint8_t)(v >> 8);
    *p += 2;
}

static void put32(uint8_t **p, uint32_t v) {
    put16(p, (uint16_t)v);
    put16(p, (uint16_t)(v >> 16));
}

static void put64(uint8_t **p, uint64_t v) {
    put32(p, (uint32_t)v);
    put32(p, (uint32_t)(v >> 32));
}

JNIEXPORT jlong XF_JNI(zipOpen)(JNIEnv *env, jclass clazz, jbyteArray path) {
    char err[XF_ZIP_ERRLEN] = "";
    char *p = xf_dup_bytes(env, path);
    xf_zip *z;
    (void)clazz;
    if (p == NULL) return 0;
    z = xf_zip_open(p, err);
    free(p);
    if (z == NULL) xf_throw_io(env, err);
    return (jlong)(intptr_t)z;
}

/*
 * Every entry at once, little-endian: a u32 count, then per entry u64 size, u64 compressed size,
 * i64 extra-field mtime (ms, or Long.MIN_VALUE), u32 DOS time, u32 CRC, u32 external attributes,
 * u16 method, u16 flags, u16 version made by, u8 name-is-UTF-8, u16 name length, name bytes.
 */
JNIEXPORT jbyteArray XF_JNI(zipListing)(JNIEnv *env, jclass clazz, jlong handle) {
    xf_zip *z = (xf_zip *)(intptr_t)handle;
    uint32_t count = xf_zip_count(z), i;
    size_t total = 4;
    uint8_t *buf, *p;
    jbyteArray out;
    (void)clazz;
    for (i = 0; i < count; i++) total += ENTRY_RECORD + xf_zip_entry_at(z, i)->name_len;
    if (total > INT32_MAX) {
        xf_throw_io(env, "Too many zip entries");
        return NULL;
    }
    buf = malloc(total);
    if (buf == NULL) {
        xf_throw_io(env, "Out of memory");
        return NULL;
    }
    p = buf;
    put32(&p, count);
    for (i = 0; i < count; i++) {
        const xf_zip_entry *e = xf_zip_entry_at(z, i);
        put64(&p, e->usize);
        put64(&p, e->csize);
        put64(&p, (uint64_t)e->mtime_ms);
        put32(&p, e->dos_time);
        put32(&p, e->crc);
        put32(&p, e->ext_attr);
        put16(&p, e->method);
        put16(&p, e->flags);
        put16(&p, e->made_by);
        *p++ = e->name_utf8;
        put16(&p, e->name_len);
        memcpy(p, xf_zip_name(z, e), e->name_len);
        p += e->name_len;
    }
    out = xf_bytes(env, buf, total);
    free(buf);
    return out;
}

/* Opens entry index; safe to call from several threads at once on one archive. */
JNIEXPORT jlong XF_JNI(zipOpenEntry)(JNIEnv *env, jclass clazz, jlong handle, jint index) {
    char err[XF_ZIP_ERRLEN] = "";
    xf_zip_stream *s;
    (void)clazz;
    if (index < 0) {
        xf_throw_io(env, "No such zip entry");
        return 0;
    }
    s = xf_zip_stream_open((xf_zip *)(intptr_t)handle, (uint32_t)index, err);
    if (s == NULL) xf_throw_io(env, err);
    return (jlong)(intptr_t)s;
}

/* Returns bytes read into buf[off..off+len), or -1 at the end of the entry. */
JNIEXPORT jint XF_JNI(zipRead)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray buf, jint off,
                               jint len) {
    char err[XF_ZIP_ERRLEN] = "";
    uint8_t chunk[READ_CHUNK];
    ssize_t n;
    (void)clazz;
    if (!xf_check_range(env, buf, off, len)) return -1;
    if (len == 0) return 0;
    n = xf_zip_stream_read((xf_zip_stream *)(intptr_t)handle, chunk,
                           len < READ_CHUNK ? (size_t)len : READ_CHUNK, err);
    if (n < 0) {
        xf_throw_io(env, err);
        return -1;
    }
    if (n == 0) return -1;
    (*env)->SetByteArrayRegion(env, buf, off, (jsize)n, (const jbyte *)chunk);
    return (jint)n;
}

JNIEXPORT void XF_JNI(zipCloseEntry)(JNIEnv *env, jclass clazz, jlong handle) {
    xf_zip_stream *s = (xf_zip_stream *)(intptr_t)handle;
    (void)env;
    (void)clazz;
    if (s != NULL) xf_zip_stream_close(s);
}

JNIEXPORT void XF_JNI(zipClose)(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    xf_zip_close((xf_zip *)(intptr_t)handle);
}
