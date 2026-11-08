/*
 * Streaming decoder for Deflate64 ("enhanced deflate", zip method 9), also able to decode plain
 * Deflate. zlib does the plain kind in production; the Deflate mode exists so the shared code can
 * be checked against zlib.
 *
 * Deflate64 differs from Deflate in three places: a 64 KiB window, length code 285 meaning
 * 3 + 16 extra bits instead of 258, and distance codes 30 and 31 (32769 and 49153 + 14 extra bits).
 *
 * Like zlib's inflate, the decoder can stop whenever input runs out or output fills up, and
 * carries on from there on the next call.
 */
#ifndef XF_INFLATE64_H
#define XF_INFLATE64_H

#include <stddef.h>
#include <stdint.h>

typedef struct xf_inflate64 xf_inflate64;

#define XF_INFLATE64_OK 0     /* progress made; call again with more input or output room */
#define XF_INFLATE64_END 1    /* the final block ended */
#define XF_INFLATE64_ERROR (-1)

/* deflate64: 1 for Deflate64, 0 for plain Deflate. Returns NULL when out of memory. */
xf_inflate64 *xf_inflate64_new(int deflate64);
void xf_inflate64_free(xf_inflate64 *s);

/*
 * Decodes from in[0..in_len) into out[0..out_len). *in_used and *out_used receive the bytes
 * consumed and produced. Input bytes are consumed into an internal bit buffer, so a caller must
 * not resend them.
 */
int xf_inflate64_run(xf_inflate64 *s, const uint8_t *in, size_t in_len, size_t *in_used,
                     uint8_t *out, size_t out_len, size_t *out_used);

/* What went wrong after XF_INFLATE64_ERROR. */
const char *xf_inflate64_error(const xf_inflate64 *s);

#endif /* XF_INFLATE64_H */
