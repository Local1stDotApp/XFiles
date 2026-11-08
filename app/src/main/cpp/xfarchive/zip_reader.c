#include "archive_platform.h" /* libarchive's config, needed by its PPMd8 decoder below */
#include "archive_ppmd8_private.h"

#include "zip_reader.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <bzlib.h>
#include <lzma.h>
#include <zlib.h>
#include <zstd.h>

#include "inflate64.h"
#include "legacy_zip.h"

#define SIG_LOCAL 0x04034b50u
#define SIG_CENTRAL 0x02014b50u
#define SIG_EOCD 0x06054b50u
#define SIG_EOCD64 0x06064b50u
#define SIG_EOCD64_LOCATOR 0x07064b50u

#define EOCD_SIZE 22u
#define EOCD64_SIZE 56u
#define LOCATOR_SIZE 20u
#define CENTRAL_SIZE 46u
#define LOCAL_SIZE 30u
#define MAX_COMMENT 65535u

#define INPUT_BUFFER (64 * 1024)
/* The same caps libarchive's own readers use, so a hostile header can't demand gigabytes. */
#define LZMA_MEMLIMIT (1ULL << 30)

#define FLAG_ENCRYPTED 0x0001u
#define FLAG_UTF8 0x0800u

enum {
    METHOD_STORED = 0,
    METHOD_SHRINK = 1,
    METHOD_IMPLODE = 6,
    METHOD_DEFLATE = 8,
    METHOD_DEFLATE64 = 9,
    METHOD_BZIP2 = 12,
    METHOD_LZMA = 14,
    METHOD_ZSTD = 93,
    METHOD_XZ = 95,
    METHOD_PPMD = 98,
    METHOD_AES = 99,
};

struct xf_zip {
    int fd;
    uint64_t size;
    int64_t delta; /* added to recorded offsets when bytes were prepended to the archive */
    uint32_t count;
    xf_zip_entry *entries;
    char *names;
    int refs;
};

typedef struct {
    IByteIn in; /* first, so libarchive's callback can find the rest */
    struct xf_zip_stream *stream;
    int failed;
} ppmd_source;

struct xf_zip_stream {
    xf_zip *z;
    const xf_zip_entry *e;
    int method;
    uint64_t in_off;  /* file offset of the next compressed byte to fetch */
    uint64_t in_left; /* compressed bytes not fetched yet */
    uint8_t *inbuf;
    size_t in_cur, in_end;
    uint64_t out_total;
    uint32_t crc;
    int decoder_ended;
    int done;
    union {
        z_stream zlib;
        bz_stream bzip2;
        lzma_stream lzma;
        ZSTD_DStream *zstd;
        xf_inflate64 *deflate64;
        xf_explode *explode;
        xf_unshrink *unshrink;
        CPpmd8 *ppmd;
    } d;
    int decoder_ready;
    ppmd_source ppmd_in;
    /* The .lzma header made from zip's LZMA header, fed to liblzma before the data. */
    uint8_t lzma_header[13];
    size_t lzma_header_left;
};

static uint16_t le16(const uint8_t *p) {
    return (uint16_t)(p[0] | (p[1] << 8));
}

static uint32_t le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint64_t le64(const uint8_t *p) {
    return (uint64_t)le32(p) | ((uint64_t)le32(p + 4) << 32);
}

static void set_error(char *err, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(err, XF_ZIP_ERRLEN, fmt, ap);
    va_end(ap);
}

/* Reads exactly len bytes at off; errno is 0 when the file ended first. */
static int read_at(int fd, void *buf, size_t len, uint64_t off) {
    uint8_t *p = buf;
    while (len > 0) {
        ssize_t n = pread(fd, p, len, (off_t)off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) {
            errno = 0;
            return -1;
        }
        p += n;
        len -= (size_t)n;
        off += (uint64_t)n;
    }
    return 0;
}

static void set_read_error(char *err) {
    if (errno != 0) {
        set_error(err, "Read error: %s", strerror(errno));
    } else {
        set_error(err, "Truncated zip archive");
    }
}

/* ---------------------------------------------------------------------------------------------
 * Central directory
 * ------------------------------------------------------------------------------------------- */

typedef struct {
    xf_zip_entry *entries;
    uint32_t count, cap;
    char *names;
    size_t names_len, names_cap;
} builder;

static int add_name(builder *b, const uint8_t *name, size_t len, uint32_t *off) {
    if (b->names_len + len + 1 > b->names_cap) {
        size_t cap = b->names_cap ? b->names_cap * 2 : 4096;
        char *grown;
        while (cap < b->names_len + len + 1) cap *= 2;
        grown = realloc(b->names, cap);
        if (grown == NULL) return -1;
        b->names = grown;
        b->names_cap = cap;
    }
    if (b->names_len > UINT32_MAX) return -1;
    *off = (uint32_t)b->names_len;
    memcpy(b->names + b->names_len, name, len);
    b->names[b->names_len + len] = '\0';
    b->names_len += len + 1;
    return 0;
}

/*
 * Applies the extra fields Commons Compress honours: Zip64 sizes and offset, the Info-ZIP Unicode
 * Path, and modification times from the extended timestamp (0x5455) and NTFS (0x000a) fields,
 * NTFS winning when both are present.
 */
static void parse_extra(xf_zip_entry *e, const uint8_t *x, size_t len, uint32_t raw_usize,
                        uint32_t raw_csize, uint32_t raw_offset, const uint8_t **unicode_name,
                        size_t *unicode_len, const uint8_t *name, size_t name_len) {
    int64_t ext_time = INT64_MIN, ntfs_time = INT64_MIN;
    while (len >= 4) {
        uint16_t id = le16(x);
        size_t size = le16(x + 2);
        const uint8_t *d = x + 4;
        if (size > len - 4) break;
        if (id == 0x0001) {
            size_t pos = 0;
            if (raw_usize == 0xffffffffu && pos + 8 <= size) {
                e->usize = le64(d + pos);
                pos += 8;
            }
            if (raw_csize == 0xffffffffu && pos + 8 <= size) {
                e->csize = le64(d + pos);
                pos += 8;
            }
            if (raw_offset == 0xffffffffu && pos + 8 <= size) {
                e->local_offset = le64(d + pos);
            }
        } else if (id == 0x5455 && size >= 5 && (d[0] & 1u)) {
            ext_time = (int64_t)(int32_t)le32(d + 1) * 1000;
        } else if (id == 0x000a && size >= 32) {
            size_t pos = 4;
            while (pos + 4 <= size) {
                uint16_t tag = le16(d + pos);
                size_t tag_size = le16(d + pos + 2);
                if (tag_size > size - pos - 4) break;
                if (tag == 0x0001 && tag_size >= 24) {
                    uint64_t ft = le64(d + pos + 4);
                    if (ft != 0) ntfs_time = (int64_t)(ft / 10000u) - 11644473600000LL;
                    break;
                }
                pos += 4 + tag_size;
            }
        } else if (id == 0x7075 && size >= 5 && d[0] == 1 && !(e->flags & FLAG_UTF8)) {
            if (le32(d + 1) == (uint32_t)crc32(0, name, (uInt)name_len)) {
                *unicode_name = d + 5;
                *unicode_len = size - 5;
            }
        }
        x += 4 + size;
        len -= 4 + size;
    }
    e->mtime_ms = ntfs_time != INT64_MIN ? ntfs_time : ext_time;
}

static int parse_central(xf_zip *z, const uint8_t *cd, size_t cd_len, char *err) {
    builder b;
    size_t pos = 0;
    memset(&b, 0, sizeof b);
    while (pos + CENTRAL_SIZE <= cd_len && le32(cd + pos) == SIG_CENTRAL) {
        const uint8_t *r = cd + pos;
        size_t name_len = le16(r + 28), extra_len = le16(r + 30), comment_len = le16(r + 32);
        const uint8_t *name = r + CENTRAL_SIZE;
        const uint8_t *unicode_name = NULL;
        size_t unicode_len = 0;
        uint32_t raw_csize, raw_usize, raw_offset;
        xf_zip_entry *e;

        if (CENTRAL_SIZE + name_len + extra_len + comment_len > cd_len - pos) break;
        if (b.count == b.cap) {
            uint32_t cap = b.cap ? b.cap * 2 : 256;
            xf_zip_entry *grown = realloc(b.entries, (size_t)cap * sizeof *grown);
            if (grown == NULL) goto oom;
            b.entries = grown;
            b.cap = cap;
        }
        e = &b.entries[b.count];
        memset(e, 0, sizeof *e);
        e->made_by = le16(r + 4);
        e->flags = le16(r + 8);
        e->method = le16(r + 10);
        e->dos_time = ((uint32_t)le16(r + 14) << 16) | le16(r + 12);
        e->crc = le32(r + 16);
        raw_csize = le32(r + 20);
        raw_usize = le32(r + 24);
        e->ext_attr = le32(r + 38);
        raw_offset = le32(r + 42);
        e->csize = raw_csize;
        e->usize = raw_usize;
        e->local_offset = raw_offset;
        parse_extra(e, name + name_len, extra_len, raw_usize, raw_csize, raw_offset,
                    &unicode_name, &unicode_len, name, name_len);
        if (unicode_name != NULL && unicode_len <= 0xffffu) {
            if (add_name(&b, unicode_name, unicode_len, &e->name_off) != 0) goto oom;
            e->name_len = (uint16_t)unicode_len;
            e->name_utf8 = 1;
        } else {
            if (add_name(&b, name, name_len, &e->name_off) != 0) goto oom;
            e->name_len = (uint16_t)name_len;
            e->name_utf8 = (e->flags & FLAG_UTF8) != 0;
        }
        b.count++;
        pos += CENTRAL_SIZE + name_len + extra_len + comment_len;
    }
    if (b.count == 0 && cd_len > 0) {
        free(b.entries);
        free(b.names);
        set_error(err, "Invalid zip central directory");
        return -1;
    }
    z->entries = b.entries;
    z->count = b.count;
    z->names = b.names != NULL ? b.names : calloc(1, 1);
    if (z->names == NULL) goto oom;
    return 0;

oom:
    free(b.entries);
    free(b.names);
    set_error(err, "Out of memory");
    return -1;
}

static int parse(xf_zip *z, char *err) {
    uint64_t tail_len = z->size < EOCD_SIZE + MAX_COMMENT ? z->size : EOCD_SIZE + MAX_COMMENT;
    uint64_t tail_start = z->size - tail_len;
    uint8_t *tail, *cd = NULL;
    int64_t found = -1;
    uint64_t eocd_pos, cd_end, cd_size, cd_off, cd_start;
    const uint8_t *p;
    int ret = -1;
    size_t i;

    if (z->size < EOCD_SIZE) {
        set_error(err, "Not a zip archive");
        return -1;
    }
    tail = malloc((size_t)tail_len);
    if (tail == NULL) {
        set_error(err, "Out of memory");
        return -1;
    }
    if (read_at(z->fd, tail, (size_t)tail_len, tail_start) != 0) {
        set_read_error(err);
        goto done;
    }
    /* The record whose comment ends exactly at the end of the file, or else the last one. */
    for (i = (size_t)tail_len - EOCD_SIZE + 1; i-- > 0;) {
        if (le32(tail + i) != SIG_EOCD) continue;
        if (found < 0) found = (int64_t)i;
        if (i + EOCD_SIZE + le16(tail + i + 20) == tail_len) {
            found = (int64_t)i;
            break;
        }
    }
    if (found < 0) {
        set_error(err, "Not a zip archive");
        goto done;
    }
    p = tail + found;
    eocd_pos = tail_start + (uint64_t)found;
    if ((le16(p + 4) != 0 && le16(p + 4) != 0xffffu) ||
        (le16(p + 6) != 0 && le16(p + 6) != 0xffffu)) {
        set_error(err, "Split zip archives are not supported");
        goto done;
    }
    cd_size = le32(p + 12);
    cd_off = le32(p + 16);
    cd_end = eocd_pos;

    /* Zip64: a locator right before the end record points at the Zip64 end record. */
    if (eocd_pos >= LOCATOR_SIZE + EOCD64_SIZE) {
        uint8_t loc[LOCATOR_SIZE], rec[EOCD64_SIZE];
        if (read_at(z->fd, loc, sizeof loc, eocd_pos - LOCATOR_SIZE) == 0 &&
            le32(loc) == SIG_EOCD64_LOCATOR) {
            uint64_t recorded = le64(loc + 8);
            uint64_t at = recorded;
            int ok = at <= z->size - EOCD64_SIZE && read_at(z->fd, rec, sizeof rec, at) == 0 &&
                     le32(rec) == SIG_EOCD64;
            if (!ok) {
                /* Prepended data shifts it; it normally sits right before the locator. */
                at = eocd_pos - LOCATOR_SIZE - EOCD64_SIZE;
                ok = read_at(z->fd, rec, sizeof rec, at) == 0 && le32(rec) == SIG_EOCD64;
            }
            if (ok) {
                cd_size = le64(rec + 40);
                cd_off = le64(rec + 48);
                cd_end = at;
            }
        }
    }

    if (cd_size > cd_end) {
        set_error(err, "Invalid zip central directory");
        goto done;
    }
    /* Self-extractors and the like record offsets relative to where the zip itself starts. */
    cd_start = cd_end - cd_size;
    z->delta = (int64_t)cd_start - (int64_t)cd_off;
    if (z->delta != 0 && cd_size > 0) {
        uint8_t sig[4];
        if (cd_off + 4 <= z->size && read_at(z->fd, sig, 4, cd_off) == 0 &&
            le32(sig) == SIG_CENTRAL) {
            /* The recorded offset is right after all; something follows the directory. */
            cd_start = cd_off;
            z->delta = 0;
        }
    }
    if (cd_size > SIZE_MAX) {
        set_error(err, "Out of memory");
        goto done;
    }
    cd = malloc(cd_size > 0 ? (size_t)cd_size : 1);
    if (cd == NULL) {
        set_error(err, "Out of memory");
        goto done;
    }
    if (cd_size > 0 && read_at(z->fd, cd, (size_t)cd_size, cd_start) != 0) {
        set_read_error(err);
        goto done;
    }
    ret = parse_central(z, cd, (size_t)cd_size, err);

done:
    free(tail);
    free(cd);
    return ret;
}

xf_zip *xf_zip_open(const char *path, char *err) {
    struct stat st;
    xf_zip *z;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        set_error(err, "%s", strerror(errno));
        return NULL;
    }
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        set_error(err, "Not a regular file");
        close(fd);
        return NULL;
    }
    z = calloc(1, sizeof *z);
    if (z == NULL) {
        set_error(err, "Out of memory");
        close(fd);
        return NULL;
    }
    z->fd = fd;
    z->size = (uint64_t)st.st_size;
    z->refs = 1;
    if (parse(z, err) != 0) {
        close(fd);
        free(z);
        return NULL;
    }
    return z;
}

static void release(xf_zip *z) {
    if (__atomic_sub_fetch(&z->refs, 1, __ATOMIC_ACQ_REL) == 0) {
        close(z->fd);
        free(z->entries);
        free(z->names);
        free(z);
    }
}

void xf_zip_close(xf_zip *z) {
    if (z != NULL) release(z);
}

uint32_t xf_zip_count(const xf_zip *z) {
    return z->count;
}

const xf_zip_entry *xf_zip_entry_at(const xf_zip *z, uint32_t index) {
    return index < z->count ? &z->entries[index] : NULL;
}

const char *xf_zip_name(const xf_zip *z, const xf_zip_entry *e) {
    return z->names + e->name_off;
}

/* ---------------------------------------------------------------------------------------------
 * Entry streams
 * ------------------------------------------------------------------------------------------- */

/* Buffers more compressed input. Returns the bytes buffered, 0 once used up, -1 on error. */
static ssize_t fill(xf_zip_stream *s, char *err) {
    size_t n;
    if (s->in_cur < s->in_end) return (ssize_t)(s->in_end - s->in_cur);
    if (s->in_left == 0) return 0;
    n = s->in_left < INPUT_BUFFER ? (size_t)s->in_left : INPUT_BUFFER;
    if (read_at(s->z->fd, s->inbuf, n, s->in_off) != 0) {
        set_read_error(err);
        return -1;
    }
    s->in_off += n;
    s->in_left -= n;
    s->in_cur = 0;
    s->in_end = n;
    return (ssize_t)n;
}

static int next_byte(void *ctx) {
    xf_zip_stream *s = ctx;
    char err[XF_ZIP_ERRLEN];
    if (fill(s, err) <= 0) return -1;
    return s->inbuf[s->in_cur++];
}

static Byte ppmd_byte(void *p) {
    ppmd_source *src = (ppmd_source *)p;
    int c = next_byte(src->stream);
    if (c < 0) {
        src->failed = 1;
        return 0;
    }
    return (Byte)c;
}

static const char *entry_name(const xf_zip_stream *s) {
    return xf_zip_name(s->z, s->e);
}

static void set_truncated(xf_zip_stream *s, char *err) {
    set_error(err, "%s is truncated", entry_name(s));
}

/* Decodes up to len bytes. Sets decoder_ended when the compressed stream says it is over. */
static ssize_t decode(xf_zip_stream *s, uint8_t *out, size_t len, char *err) {
    ssize_t avail;
    switch (s->method) {
    case METHOD_STORED: {
        size_t n = s->in_left < len ? (size_t)s->in_left : len;
        if (n == 0) {
            s->decoder_ended = 1;
            return 0;
        }
        if (read_at(s->z->fd, out, n, s->in_off) != 0) {
            set_read_error(err);
            return -1;
        }
        s->in_off += n;
        s->in_left -= n;
        return (ssize_t)n;
    }

    case METHOD_DEFLATE:
        for (;;) {
            int r;
            size_t produced;
            if ((avail = fill(s, err)) < 0) return -1;
            s->d.zlib.next_in = s->inbuf + s->in_cur;
            s->d.zlib.avail_in = (uInt)avail;
            s->d.zlib.next_out = out;
            s->d.zlib.avail_out = (uInt)len;
            r = inflate(&s->d.zlib, Z_NO_FLUSH);
            s->in_cur += (size_t)avail - s->d.zlib.avail_in;
            produced = len - s->d.zlib.avail_out;
            if (r == Z_STREAM_END) {
                s->decoder_ended = 1;
                return (ssize_t)produced;
            }
            if (r != Z_OK && r != Z_BUF_ERROR) {
                set_error(err, "%s: %s", entry_name(s),
                          s->d.zlib.msg != NULL ? s->d.zlib.msg : "invalid deflate data");
                return -1;
            }
            if (produced > 0) return (ssize_t)produced;
            if (avail == 0) {
                set_truncated(s, err);
                return -1;
            }
        }

    case METHOD_DEFLATE64:
        for (;;) {
            size_t used = 0, produced = 0;
            int r;
            if ((avail = fill(s, err)) < 0) return -1;
            r = xf_inflate64_run(s->d.deflate64, s->inbuf + s->in_cur, (size_t)avail, &used, out,
                                 len, &produced);
            s->in_cur += used;
            if (r == XF_INFLATE64_ERROR) {
                set_error(err, "%s: %s", entry_name(s), xf_inflate64_error(s->d.deflate64));
                return -1;
            }
            if (r == XF_INFLATE64_END) {
                s->decoder_ended = 1;
                return (ssize_t)produced;
            }
            if (produced > 0) return (ssize_t)produced;
            if (avail == 0) {
                set_truncated(s, err);
                return -1;
            }
        }

    case METHOD_BZIP2:
        for (;;) {
            int r;
            size_t produced;
            if ((avail = fill(s, err)) < 0) return -1;
            s->d.bzip2.next_in = (char *)(s->inbuf + s->in_cur);
            s->d.bzip2.avail_in = (unsigned)avail;
            s->d.bzip2.next_out = (char *)out;
            s->d.bzip2.avail_out = (unsigned)len;
            r = BZ2_bzDecompress(&s->d.bzip2);
            s->in_cur += (size_t)avail - s->d.bzip2.avail_in;
            produced = len - s->d.bzip2.avail_out;
            if (r == BZ_STREAM_END) {
                s->decoder_ended = 1;
                return (ssize_t)produced;
            }
            if (r != BZ_OK) {
                set_error(err, "%s: invalid bzip2 data", entry_name(s));
                return -1;
            }
            if (produced > 0) return (ssize_t)produced;
            if (avail == 0) {
                set_truncated(s, err);
                return -1;
            }
        }

    case METHOD_LZMA:
    case METHOD_XZ:
        for (;;) {
            lzma_ret r;
            size_t produced;
            int header = s->lzma_header_left > 0;
            if (header) {
                avail = (ssize_t)s->lzma_header_left;
                s->d.lzma.next_in = s->lzma_header + sizeof s->lzma_header - s->lzma_header_left;
            } else {
                if ((avail = fill(s, err)) < 0) return -1;
                s->d.lzma.next_in = s->inbuf + s->in_cur;
            }
            s->d.lzma.avail_in = (size_t)avail;
            s->d.lzma.next_out = out;
            s->d.lzma.avail_out = len;
            r = lzma_code(&s->d.lzma, LZMA_RUN);
            if (header) {
                s->lzma_header_left = s->d.lzma.avail_in;
            } else {
                s->in_cur += (size_t)avail - s->d.lzma.avail_in;
            }
            produced = len - s->d.lzma.avail_out;
            if (r == LZMA_STREAM_END) {
                s->decoder_ended = 1;
                return (ssize_t)produced;
            }
            if (r != LZMA_OK && r != LZMA_BUF_ERROR) {
                set_error(err, "%s: %s", entry_name(s),
                          r == LZMA_MEMLIMIT_ERROR || r == LZMA_MEM_ERROR
                              ? "not enough memory to decompress"
                              : "invalid LZMA data");
                return -1;
            }
            if (produced > 0) return (ssize_t)produced;
            if (avail == 0) {
                set_truncated(s, err);
                return -1;
            }
            if (header && s->lzma_header_left == (size_t)avail) {
                set_error(err, "%s: invalid LZMA header", entry_name(s));
                return -1;
            }
        }

    case METHOD_ZSTD:
        for (;;) {
            ZSTD_inBuffer in;
            ZSTD_outBuffer o;
            size_t r;
            if ((avail = fill(s, err)) < 0) return -1;
            in.src = s->inbuf + s->in_cur;
            in.size = (size_t)avail;
            in.pos = 0;
            o.dst = out;
            o.size = len;
            o.pos = 0;
            r = ZSTD_decompressStream(s->d.zstd, &o, &in);
            s->in_cur += in.pos;
            if (ZSTD_isError(r)) {
                set_error(err, "%s: %s", entry_name(s), ZSTD_getErrorName(r));
                return -1;
            }
            if (r == 0 && s->in_cur == s->in_end && s->in_left == 0) s->decoder_ended = 1;
            if (o.pos > 0 || s->decoder_ended) return (ssize_t)o.pos;
            if (avail == 0) {
                set_truncated(s, err);
                return -1;
            }
        }

    case METHOD_PPMD: {
        size_t n = 0;
        while (n < len) {
            int sym = __archive_ppmd8_functions.Ppmd8_DecodeSymbol(s->d.ppmd);
            if (sym < 0) {
                if (sym != -1) {
                    set_error(err, "%s: invalid PPMd data", entry_name(s));
                    return -1;
                }
                s->decoder_ended = 1;
                break;
            }
            if (s->ppmd_in.failed) {
                set_truncated(s, err);
                return -1;
            }
            out[n++] = (uint8_t)sym;
        }
        return (ssize_t)n;
    }

    case METHOD_IMPLODE:
    case METHOD_SHRINK: {
        const char *why = NULL;
        ssize_t n = s->method == METHOD_IMPLODE ? xf_explode_read(s->d.explode, out, len, &why)
                                                : xf_unshrink_read(s->d.unshrink, out, len, &why);
        if (n < 0) {
            set_error(err, "%s: %s", entry_name(s), why != NULL ? why : "corrupt data");
            return -1;
        }
        if (n == 0) s->decoder_ended = 1;
        return n;
    }
    }
    set_error(err, "%s: unsupported compression method %u", entry_name(s), s->method);
    return -1;
}

static int init_lzma_alone(xf_zip_stream *s, char *err) {
    /* Zip's LZMA header: version (2 bytes), properties size (2, always 5), properties (5).
     * liblzma's .lzma ("alone") decoder takes the same properties followed by the size. */
    uint8_t prefix[4];
    int i;
    for (i = 0; i < 4; i++) {
        int c = next_byte(s);
        if (c < 0) goto truncated;
        prefix[i] = (uint8_t)c;
    }
    if (le16(prefix + 2) != 5) {
        set_error(err, "%s: invalid LZMA header", entry_name(s));
        return -1;
    }
    for (i = 0; i < 5; i++) {
        int c = next_byte(s);
        if (c < 0) goto truncated;
        s->lzma_header[i] = (uint8_t)c;
    }
    memset(s->lzma_header + 5, 0xff, 8); /* unknown size; the central directory has the real one */
    s->lzma_header_left = sizeof s->lzma_header;
    if (lzma_alone_decoder(&s->d.lzma, LZMA_MEMLIMIT) != LZMA_OK) {
        set_error(err, "Out of memory");
        return -1;
    }
    s->decoder_ready = 1;
    return 0;

truncated:
    set_truncated(s, err);
    return -1;
}

static int init_ppmd(xf_zip_stream *s, char *err) {
    int lo = next_byte(s), hi = next_byte(s);
    unsigned v, order, mem, restore;
    if (lo < 0 || hi < 0) {
        set_truncated(s, err);
        return -1;
    }
    v = (unsigned)lo | ((unsigned)hi << 8);
    order = (v & 15u) + 1u;
    mem = ((v >> 4) & 0xffu) + 1u;
    restore = v >> 12;
    if (order < 2 || restore > 2) {
        set_error(err, "%s: invalid PPMd parameters", entry_name(s));
        return -1;
    }
    s->d.ppmd = calloc(1, sizeof(CPpmd8));
    if (s->d.ppmd == NULL) goto oom;
    __archive_ppmd8_functions.Ppmd8_Construct(s->d.ppmd);
    if (!__archive_ppmd8_functions.Ppmd8_Alloc(s->d.ppmd, mem << 20)) {
        free(s->d.ppmd);
        s->d.ppmd = NULL;
        goto oom;
    }
    s->decoder_ready = 1;
    s->ppmd_in.in.Read = ppmd_byte;
    s->ppmd_in.stream = s;
    s->d.ppmd->Stream.In = &s->ppmd_in.in;
    if (!__archive_ppmd8_functions.Ppmd8_RangeDec_Init(s->d.ppmd) || s->ppmd_in.failed) {
        set_error(err, "%s: invalid PPMd data", entry_name(s));
        return -1;
    }
    __archive_ppmd8_functions.Ppmd8_Init(s->d.ppmd, order, restore);
    return 0;

oom:
    set_error(err, "Out of memory");
    return -1;
}

static void end_decoder(xf_zip_stream *s) {
    if (!s->decoder_ready) return;
    switch (s->method) {
    case METHOD_DEFLATE: inflateEnd(&s->d.zlib); break;
    case METHOD_DEFLATE64: xf_inflate64_free(s->d.deflate64); break;
    case METHOD_BZIP2: BZ2_bzDecompressEnd(&s->d.bzip2); break;
    case METHOD_LZMA:
    case METHOD_XZ: lzma_end(&s->d.lzma); break;
    case METHOD_ZSTD: ZSTD_freeDStream(s->d.zstd); break;
    case METHOD_PPMD:
        __archive_ppmd8_functions.Ppmd8_Free(s->d.ppmd);
        free(s->d.ppmd);
        break;
    case METHOD_IMPLODE: xf_explode_free(s->d.explode); break;
    case METHOD_SHRINK: xf_unshrink_free(s->d.unshrink); break;
    default: break;
    }
    s->decoder_ready = 0;
}

static int init_decoder(xf_zip_stream *s, char *err) {
    const xf_zip_entry *e = s->e;
    switch (s->method) {
    case METHOD_STORED:
        return 0;
    case METHOD_DEFLATE:
        if (inflateInit2(&s->d.zlib, -MAX_WBITS) != Z_OK) break;
        s->decoder_ready = 1;
        return 0;
    case METHOD_DEFLATE64:
        if ((s->d.deflate64 = xf_inflate64_new(1)) == NULL) break;
        s->decoder_ready = 1;
        return 0;
    case METHOD_BZIP2:
        if (BZ2_bzDecompressInit(&s->d.bzip2, 0, 0) != BZ_OK) break;
        s->decoder_ready = 1;
        return 0;
    case METHOD_LZMA:
        return init_lzma_alone(s, err);
    case METHOD_XZ:
        if (lzma_stream_decoder(&s->d.lzma, LZMA_MEMLIMIT, 0) != LZMA_OK) break;
        s->decoder_ready = 1;
        return 0;
    case METHOD_ZSTD:
        if ((s->d.zstd = ZSTD_createDStream()) == NULL) break;
        s->decoder_ready = 1;
        if (ZSTD_isError(ZSTD_initDStream(s->d.zstd))) break;
        return 0;
    case METHOD_PPMD:
        return init_ppmd(s, err);
    case METHOD_IMPLODE:
        s->d.explode = xf_explode_new((e->flags & 2u) != 0, (e->flags & 4u) != 0, next_byte, s);
        if (s->d.explode == NULL) break;
        s->decoder_ready = 1;
        return 0;
    case METHOD_SHRINK:
        if ((s->d.unshrink = xf_unshrink_new(next_byte, s)) == NULL) break;
        s->decoder_ready = 1;
        return 0;
    default:
        set_error(err, "%s uses an unsupported compression method (%u)", entry_name(s), s->method);
        return -1;
    }
    set_error(err, "Out of memory");
    return -1;
}

xf_zip_stream *xf_zip_stream_open(xf_zip *z, uint32_t index, char *err) {
    const xf_zip_entry *e = xf_zip_entry_at(z, index);
    uint8_t lh[LOCAL_SIZE];
    uint64_t at, data;
    xf_zip_stream *s;

    if (e == NULL) {
        set_error(err, "No such zip entry");
        return NULL;
    }
    if ((e->flags & FLAG_ENCRYPTED) || e->method == METHOD_AES) {
        set_error(err, "%s is password-protected", xf_zip_name(z, e));
        return NULL;
    }
    /* The local header's name and extra field can differ in length from the central copy. */
    at = e->local_offset + (uint64_t)z->delta;
    if (at > z->size - LOCAL_SIZE || read_at(z->fd, lh, sizeof lh, at) != 0 ||
        le32(lh) != SIG_LOCAL) {
        set_error(err, "%s: invalid local header", xf_zip_name(z, e));
        return NULL;
    }
    data = at + LOCAL_SIZE + le16(lh + 26) + le16(lh + 28);
    if (data > z->size || e->csize > z->size - data) {
        set_error(err, "%s is truncated", xf_zip_name(z, e));
        return NULL;
    }
    s = calloc(1, sizeof *s);
    if (s == NULL) {
        set_error(err, "Out of memory");
        return NULL;
    }
    __atomic_add_fetch(&z->refs, 1, __ATOMIC_ACQ_REL);
    s->z = z;
    s->e = e;
    s->method = e->method;
    s->in_off = data;
    s->in_left = e->csize;
    if (s->method != METHOD_STORED) {
        s->inbuf = malloc(INPUT_BUFFER);
        if (s->inbuf == NULL) {
            set_error(err, "Out of memory");
            xf_zip_stream_close(s);
            return NULL;
        }
    }
    if (init_decoder(s, err) != 0) {
        xf_zip_stream_close(s);
        return NULL;
    }
    return s;
}

/*
 * Whether the entry's declared size is what ends it. Stored data ends with the compressed size and
 * the other methods mark their own end, so for those the declared size is not trusted: like
 * Commons Compress, the reader goes by the data and lets the CRC decide.
 */
static int ends_at_declared_size(const xf_zip_stream *s) {
    switch (s->method) {
    case METHOD_SHRINK:
    case METHOD_IMPLODE:
    case METHOD_PPMD:
        return 1;
    case METHOD_LZMA:
        return (s->e->flags & 2u) == 0; /* no end-of-stream marker */
    default:
        return 0;
    }
}

ssize_t xf_zip_stream_read(xf_zip_stream *s, uint8_t *out, size_t len, char *err) {
    int bounded = ends_at_declared_size(s);
    if (s->done) return 0;
    if (len == 0) return 0;
    for (;;) {
        size_t want = len;
        ssize_t n;
        if (bounded) {
            uint64_t left = s->e->usize - s->out_total;
            if (left == 0) break;
            if (left < want) want = (size_t)left;
        }
        if (s->decoder_ended) break;
        n = decode(s, out, want, err);
        if (n < 0) return -1;
        if (n > 0) {
            s->crc = (uint32_t)crc32(s->crc, out, (uInt)n);
            s->out_total += (uint64_t)n;
            return n;
        }
    }
    if (s->crc != s->e->crc) {
        if (s->out_total < s->e->usize) {
            set_truncated(s, err);
        } else {
            set_error(err, "%s is corrupt (CRC mismatch)", entry_name(s));
        }
        return -1;
    }
    s->done = 1;
    return 0;
}

void xf_zip_stream_close(xf_zip_stream *s) {
    if (s == NULL) return;
    end_decoder(s);
    free(s->inbuf);
    release(s->z);
    free(s);
}
