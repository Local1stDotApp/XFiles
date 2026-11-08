/*
 * Random-access zip reader.
 *
 * Opening an archive parses the central directory once (Zip64 included). After that the archive
 * is immutable, so any number of threads can each open their own entry stream: a stream reads its
 * entry's bytes with pread at the entry's own offset, never walking the other entries. That is
 * what lets extraction run one stream per core, and lets a single entry open in constant time.
 *
 * Methods: stored (0), shrink (1), implode (6), deflate (8), deflate64 (9), bzip2 (12), lzma (14),
 * zstd (93), xz (95) and PPMd I (98). Every stream checks the entry's size and CRC-32 at its end.
 * Encrypted entries are refused.
 */
#ifndef XF_ZIP_READER_H
#define XF_ZIP_READER_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

typedef struct {
    uint64_t usize;
    uint64_t csize;
    uint64_t local_offset;
    int64_t mtime_ms;   /* from an NTFS or extended-timestamp extra field; INT64_MIN if neither */
    uint32_t dos_time;  /* (date << 16) | time, local time */
    uint32_t crc;
    uint32_t ext_attr;
    uint32_t name_off;  /* into xf_zip.names */
    uint16_t name_len;
    uint16_t method;
    uint16_t flags;
    uint16_t made_by;
    uint8_t name_utf8;  /* the UTF-8 flag is set, or the name came from a Unicode Path field */
} xf_zip_entry;

typedef struct xf_zip xf_zip;
typedef struct xf_zip_stream xf_zip_stream;

#define XF_ZIP_ERRLEN 256

/* Returns NULL and fills err on failure. */
xf_zip *xf_zip_open(const char *path, char *err);
/* Drops the caller's reference; the archive is freed once its last stream closes too. */
void xf_zip_close(xf_zip *z);

uint32_t xf_zip_count(const xf_zip *z);
const xf_zip_entry *xf_zip_entry_at(const xf_zip *z, uint32_t index);
const char *xf_zip_name(const xf_zip *z, const xf_zip_entry *e);

/* Opens entry index for reading. Returns NULL and fills err on failure. */
xf_zip_stream *xf_zip_stream_open(xf_zip *z, uint32_t index, char *err);
/* Returns bytes read, 0 at the end of the entry, -1 with err filled on failure. */
ssize_t xf_zip_stream_read(xf_zip_stream *s, uint8_t *out, size_t len, char *err);
void xf_zip_stream_close(xf_zip_stream *s);

#endif /* XF_ZIP_READER_H */
