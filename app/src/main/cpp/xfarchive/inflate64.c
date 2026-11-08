/*
 * Deflate64 decoder. The structure follows zlib's inflate.c (a resumable state machine fed from a
 * 64-bit bit buffer); the Huffman decoding follows Mark Adler's puff.c, with a lookup table for
 * codes of up to FASTBITS bits.
 */
#include "inflate64.h"

#include <stdlib.h>
#include <string.h>

#define MAXBITS 15
#define FASTBITS 9
#define MAXLCODES 288
#define MAXDCODES 32
#define WSIZE 65536u
#define WMASK (WSIZE - 1u)

typedef struct {
    int16_t count[MAXBITS + 1];
    int16_t symbol[MAXLCODES];
    uint16_t fast[1u << FASTBITS]; /* (length << 9) | symbol, or 0 for a longer code */
} huff;

enum mode {
    TYPE, STORED_LEN, STORED_COPY, TABLE, LENLENS, CODELENS, LEN, LENEXT, DIST, DISTEXT, MATCH,
    DONE, BAD
};

struct xf_inflate64 {
    int deflate64;
    enum mode mode;
    int last;
    uint64_t hold;
    unsigned bits;
    uint32_t stored_left;
    unsigned nlen, ndist, ncode, have;
    unsigned extra;
    uint32_t length, dist;
    uint64_t total;
    uint32_t wnext;
    const char *error;
    huff lencode, distcode, clcode;
    uint8_t lens[MAXLCODES + MAXDCODES];
    uint8_t window[WSIZE];
};

static const uint16_t lbase[29] = {
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
    35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258};
static const uint8_t lext[29] = {
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
    3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0};
static const uint32_t dbase[32] = {
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
    257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    32769, 49153};
static const uint8_t dext[32] = {
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
    7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14};
static const uint8_t order[19] = {
    16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};

/*
 * Builds the decoding tables for n code lengths. Returns 0 for a complete code, a positive
 * number for an incomplete one and a negative number for an over-subscribed one.
 */
static int construct(huff *h, const uint8_t *length, unsigned n) {
    int16_t offs[MAXBITS + 1];
    unsigned next[MAXBITS + 1];
    unsigned len, sym, code;
    int left;

    memset(h->count, 0, sizeof h->count);
    memset(h->fast, 0, sizeof h->fast);
    for (sym = 0; sym < n; sym++) h->count[length[sym]]++;
    if ((unsigned)h->count[0] == n) return 0;

    left = 1;
    for (len = 1; len <= MAXBITS; len++) {
        left <<= 1;
        left -= h->count[len];
        if (left < 0) return left;
    }

    offs[1] = 0;
    for (len = 1; len < MAXBITS; len++) offs[len + 1] = (int16_t)(offs[len] + h->count[len]);
    for (sym = 0; sym < n; sym++) {
        if (length[sym] != 0) h->symbol[offs[length[sym]]++] = (int16_t)sym;
    }

    /* The canonical codes of the short symbols, bit-reversed, fill the lookup table. */
    code = 0;
    for (len = 1; len <= MAXBITS; len++) {
        code = (code + (len > 1 ? (unsigned)h->count[len - 1] : 0u)) << 1;
        next[len] = code;
    }
    for (sym = 0; sym < n; sym++) {
        unsigned rev = 0, i;
        len = length[sym];
        if (len == 0) continue;
        code = next[len]++;
        if (len > FASTBITS) continue;
        for (i = 0; i < len; i++) rev |= ((code >> i) & 1u) << (len - 1 - i);
        for (i = rev; i < (1u << FASTBITS); i += 1u << len) {
            h->fast[i] = (uint16_t)((len << 9) | sym);
        }
    }
    return left;
}

/* Returns the next symbol without consuming it, -1 when more bits are needed, -2 if invalid. */
static int decode(const huff *h, uint64_t hold, unsigned bits, unsigned *used) {
    int code = 0, first = 0, index = 0;
    unsigned len;

    if (bits >= FASTBITS) {
        unsigned e = h->fast[hold & ((1u << FASTBITS) - 1u)];
        if (e != 0) {
            *used = e >> 9;
            return (int)(e & 0x1ffu);
        }
    }
    for (len = 1; len <= MAXBITS; len++) {
        int count;
        if (len > bits) return -1;
        code |= (int)((hold >> (len - 1)) & 1u);
        count = h->count[len];
        if (code - count < first) {
            *used = len;
            return h->symbol[index + (code - first)];
        }
        index += count;
        first += count;
        first <<= 1;
        code <<= 1;
    }
    return -2;
}

static void fixed_tables(xf_inflate64 *s) {
    unsigned sym;
    for (sym = 0; sym < 144; sym++) s->lens[sym] = 8;
    for (; sym < 256; sym++) s->lens[sym] = 9;
    for (; sym < 280; sym++) s->lens[sym] = 7;
    for (; sym < MAXLCODES; sym++) s->lens[sym] = 8;
    construct(&s->lencode, s->lens, MAXLCODES);
    for (sym = 0; sym < MAXDCODES; sym++) s->lens[sym] = 5;
    construct(&s->distcode, s->lens, s->deflate64 ? 32u : 30u);
}

xf_inflate64 *xf_inflate64_new(int deflate64) {
    xf_inflate64 *s = calloc(1, sizeof *s);
    if (s != NULL) {
        s->deflate64 = deflate64;
        s->mode = TYPE;
    }
    return s;
}

void xf_inflate64_free(xf_inflate64 *s) {
    free(s);
}

const char *xf_inflate64_error(const xf_inflate64 *s) {
    return s->error != NULL ? s->error : "invalid deflate data";
}

#define PULL()                                                        \
    do {                                                              \
        while (s->bits <= 56 && in < in_end) {                        \
            s->hold |= (uint64_t)(*in++) << s->bits;                  \
            s->bits += 8;                                             \
        }                                                             \
    } while (0)
#define DROP(n)                  \
    do {                         \
        s->hold >>= (n);         \
        s->bits -= (unsigned)(n); \
    } while (0)
#define FAIL(msg)        \
    do {                 \
        s->error = (msg); \
        s->mode = BAD;   \
        goto leave;      \
    } while (0)
#define EMIT(b)                                   \
    do {                                          \
        uint8_t emit_byte_ = (b);                 \
        *out++ = emit_byte_;                      \
        s->window[s->wnext] = emit_byte_;         \
        s->wnext = (s->wnext + 1u) & WMASK;       \
        s->total++;                               \
    } while (0)

int xf_inflate64_run(xf_inflate64 *s, const uint8_t *in_start, size_t in_len, size_t *in_used,
                     uint8_t *out_start, size_t out_len, size_t *out_used) {
    const uint8_t *in = in_start;
    const uint8_t *const in_end = in_start + in_len;
    uint8_t *out = out_start;
    uint8_t *const out_end = out_start + out_len;
    unsigned used;
    int sym, err;

    for (;;) {
        switch (s->mode) {
        case TYPE:
            if (s->last) {
                s->mode = DONE;
                goto leave;
            }
            PULL();
            if (s->bits < 3) goto leave;
            s->last = (int)(s->hold & 1u);
            switch ((unsigned)(s->hold >> 1) & 3u) {
            case 0:
                DROP(3);
                DROP(s->bits & 7u); /* stored blocks start on a byte boundary */
                s->mode = STORED_LEN;
                break;
            case 1:
                DROP(3);
                fixed_tables(s);
                s->mode = LEN;
                break;
            case 2:
                DROP(3);
                s->mode = TABLE;
                break;
            default:
                FAIL("invalid block type");
            }
            break;

        case STORED_LEN:
            PULL();
            if (s->bits < 32) goto leave;
            if ((s->hold & 0xffffu) != (~(s->hold >> 16) & 0xffffu)) {
                FAIL("invalid stored block lengths");
            }
            s->stored_left = (uint32_t)(s->hold & 0xffffu);
            DROP(32);
            s->mode = STORED_COPY;
            break;

        case STORED_COPY:
            while (s->stored_left > 0) {
                if (out == out_end) goto leave;
                if (s->bits >= 8) {
                    /* Whole bytes already pulled into the bit buffer come first. */
                    uint8_t b = (uint8_t)s->hold;
                    DROP(8);
                    EMIT(b);
                    s->stored_left--;
                } else if (in < in_end) {
                    size_t n = (size_t)s->stored_left;
                    size_t i;
                    if (n > (size_t)(out_end - out)) n = (size_t)(out_end - out);
                    if (n > (size_t)(in_end - in)) n = (size_t)(in_end - in);
                    for (i = 0; i < n; i++) EMIT(in[i]);
                    in += n;
                    s->stored_left -= (uint32_t)n;
                } else {
                    goto leave;
                }
            }
            s->mode = TYPE;
            break;

        case TABLE:
            PULL();
            if (s->bits < 14) goto leave;
            s->nlen = (unsigned)(s->hold & 31u) + 257u;
            DROP(5);
            s->ndist = (unsigned)(s->hold & 31u) + 1u;
            DROP(5);
            s->ncode = (unsigned)(s->hold & 15u) + 4u;
            DROP(4);
            if (s->nlen > 286 || s->ndist > (s->deflate64 ? 32u : 30u)) {
                FAIL("too many length or distance symbols");
            }
            s->have = 0;
            s->mode = LENLENS;
            break;

        case LENLENS:
            while (s->have < s->ncode) {
                PULL();
                if (s->bits < 3) goto leave;
                s->lens[order[s->have++]] = (uint8_t)(s->hold & 7u);
                DROP(3);
            }
            while (s->have < 19) s->lens[order[s->have++]] = 0;
            if (construct(&s->clcode, s->lens, 19) != 0) FAIL("invalid code lengths set");
            s->have = 0;
            s->mode = CODELENS;
            break;

        case CODELENS:
            while (s->have < s->nlen + s->ndist) {
                unsigned extra, rep, x;
                uint8_t val = 0;
                PULL();
                sym = decode(&s->clcode, s->hold, s->bits, &used);
                if (sym == -1) goto leave;
                if (sym < 0) FAIL("invalid code lengths set");
                if (sym < 16) {
                    DROP(used);
                    s->lens[s->have++] = (uint8_t)sym;
                    continue;
                }
                extra = sym == 16 ? 2u : sym == 17 ? 3u : 7u;
                if (s->bits < used + extra) goto leave;
                x = (unsigned)(s->hold >> used) & ((1u << extra) - 1u);
                if (sym == 16) {
                    if (s->have == 0) FAIL("invalid bit length repeat");
                    val = s->lens[s->have - 1];
                    rep = 3 + x;
                } else {
                    rep = (sym == 17 ? 3u : 11u) + x;
                }
                if (s->have + rep > s->nlen + s->ndist) FAIL("invalid bit length repeat");
                DROP(used + extra);
                while (rep-- > 0) s->lens[s->have++] = val;
            }
            if (s->lens[256] == 0) FAIL("invalid code -- missing end-of-block");
            err = construct(&s->lencode, s->lens, s->nlen);
            if (err < 0 ||
                (err > 0 && s->nlen != (unsigned)(s->lencode.count[0] + s->lencode.count[1]))) {
                FAIL("invalid literal/lengths set");
            }
            err = construct(&s->distcode, s->lens + s->nlen, s->ndist);
            if (err < 0 ||
                (err > 0 && s->ndist != (unsigned)(s->distcode.count[0] + s->distcode.count[1]))) {
                FAIL("invalid distances set");
            }
            s->mode = LEN;
            break;

        case LEN:
            for (;;) {
                PULL();
                sym = decode(&s->lencode, s->hold, s->bits, &used);
                if (sym == -1) goto leave;
                if (sym < 0) FAIL("invalid literal/length code");
                if (sym < 256) {
                    if (out == out_end) goto leave;
                    DROP(used);
                    EMIT((uint8_t)sym);
                    continue;
                }
                DROP(used);
                if (sym == 256) {
                    s->mode = TYPE;
                    break;
                }
                sym -= 257;
                if (sym >= 29) FAIL("invalid literal/length code");
                if (s->deflate64 && sym == 28) {
                    s->length = 3;
                    s->extra = 16;
                } else {
                    s->length = lbase[sym];
                    s->extra = lext[sym];
                }
                s->mode = LENEXT;
                break;
            }
            break;

        case LENEXT:
            if (s->extra != 0) {
                PULL();
                if (s->bits < s->extra) goto leave;
                s->length += (uint32_t)(s->hold & ((1u << s->extra) - 1u));
                DROP(s->extra);
            }
            s->mode = DIST;
            /* fall through */
        case DIST:
            PULL();
            sym = decode(&s->distcode, s->hold, s->bits, &used);
            if (sym == -1) goto leave;
            if (sym < 0 || sym >= (s->deflate64 ? 32 : 30)) FAIL("invalid distance code");
            DROP(used);
            s->dist = dbase[sym];
            s->extra = dext[sym];
            s->mode = DISTEXT;
            /* fall through */
        case DISTEXT:
            if (s->extra != 0) {
                PULL();
                if (s->bits < s->extra) goto leave;
                s->dist += (uint32_t)(s->hold & ((1u << s->extra) - 1u));
                DROP(s->extra);
                s->extra = 0;
            }
            if ((uint64_t)s->dist > s->total || s->dist > (s->deflate64 ? WSIZE : 32768u)) {
                FAIL("invalid distance too far back");
            }
            s->mode = MATCH;
            /* fall through */
        case MATCH:
            while (s->length > 0) {
                if (out == out_end) goto leave;
                EMIT(s->window[(s->wnext - s->dist) & WMASK]);
                s->length--;
            }
            s->mode = LEN;
            break;

        case DONE:
        case BAD:
            goto leave;
        }
    }

leave:
    *in_used = (size_t)(in - in_start);
    *out_used = (size_t)(out - out_start);
    if (s->mode == BAD) return XF_INFLATE64_ERROR;
    if (s->mode == DONE) return XF_INFLATE64_END;
    return XF_INFLATE64_OK;
}
