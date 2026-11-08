/*
 * Decoders for the two PKZIP 1.x methods that zip readers still honour: Shrink (method 1, LZW)
 * and Implode (method 6, Shannon-Fano coded LZ77). Archives that use them are rare and old, but
 * Apache Commons Compress reads them, so libxfarchive does too. Both follow the descriptions in
 * PKWARE's APPNOTE.TXT and behave like Commons Compress's UnshrinkingInputStream and
 * ExplodingInputStream.
 *
 * Input is pulled one byte at a time through a callback; output stops whenever the caller's
 * buffer is full and resumes on the next call.
 */
#ifndef XF_LEGACY_ZIP_H
#define XF_LEGACY_ZIP_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

/* Returns the next input byte, or -1 once the input is exhausted. */
typedef int (*xf_byte_source)(void *ctx);

typedef struct xf_explode xf_explode;
typedef struct xf_unshrink xf_unshrink;

/* dict8k and three_trees come from bits 1 and 2 of the entry's general purpose flags. */
xf_explode *xf_explode_new(int dict8k, int three_trees, xf_byte_source src, void *ctx);
/* Returns bytes produced, 0 at the end of the input, or -1 on corrupt data. */
ssize_t xf_explode_read(xf_explode *s, uint8_t *out, size_t len, const char **error);
void xf_explode_free(xf_explode *s);

xf_unshrink *xf_unshrink_new(xf_byte_source src, void *ctx);
ssize_t xf_unshrink_read(xf_unshrink *s, uint8_t *out, size_t len, const char **error);
void xf_unshrink_free(xf_unshrink *s);

#endif /* XF_LEGACY_ZIP_H */
