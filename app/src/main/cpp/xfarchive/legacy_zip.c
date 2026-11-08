#include "legacy_zip.h"

#include <stdlib.h>
#include <string.h>

/* ---------------------------------------------------------------------------------------------
 * Implode (method 6)
 * ------------------------------------------------------------------------------------------- */

#define EXPLODE_WINDOW 8192u /* the largest dictionary; a 4 KiB one fits inside it */
#define SF_UNDEFINED 0xffffu

typedef struct {
    uint16_t *table; /* indexed by the next maxlen input bits: (length << 8) | value */
    unsigned maxlen;
} sf_tree;

struct xf_explode {
    xf_byte_source src;
    void *ctx;
    int dict8k;
    int three_trees;
    int started;
    uint32_t hold;
    unsigned bits;
    sf_tree literals, lengths, distances;
    uint32_t copy_dist, copy_left;
    uint32_t wpos;
    uint8_t window[EXPLODE_WINDOW];
};

/* Makes at least n bits available; 0 when the input ends first. */
static int explode_need(xf_explode *s, unsigned n) {
    while (s->bits < n) {
        int c = s->src(s->ctx);
        if (c < 0) return 0;
        s->hold |= (uint32_t)c << s->bits;
        s->bits += 8;
    }
    return 1;
}

static int explode_bits(xf_explode *s, unsigned n) {
    int v;
    if (!explode_need(s, n)) return -1;
    v = (int)(s->hold & ((1u << n) - 1u));
    s->hold >>= n;
    s->bits -= n;
    return v;
}

/*
 * Reads one Shannon-Fano tree description (APPNOTE 5.3.7) and builds its lookup table. The codes
 * are assigned from the longest bit length down, in the order Commons Compress's BinaryTree uses.
 */
static int read_tree(xf_explode *s, sf_tree *t, unsigned n) {
    uint8_t lens[256];
    uint16_t codes[256];
    unsigned perm[256];
    unsigned pos = 0, i, k, sorted = 0, last = 0, maxlen = 0;
    uint32_t code = 0, inc = 0;
    int c = s->src(s->ctx);
    unsigned nbytes;

    if (c < 0) return -1;
    nbytes = (unsigned)c + 1;
    memset(lens, 0, sizeof lens);
    for (i = 0; i < nbytes; i++) {
        unsigned count, bitlen;
        c = s->src(s->ctx);
        if (c < 0) return -1;
        count = ((unsigned)c >> 4) + 1;
        bitlen = ((unsigned)c & 15u) + 1;
        if (pos + count > n) return -1;
        while (count-- > 0) lens[pos++] = (uint8_t)bitlen;
        if (bitlen > maxlen) maxlen = bitlen;
    }
    if (maxlen == 0) return -1;

    for (k = 0; k <= 16; k++) {
        for (i = 0; i < n; i++) {
            if (lens[i] == k) perm[sorted++] = i;
        }
    }
    for (i = n; i-- > 0;) {
        unsigned bitlen = lens[perm[i]];
        code += inc;
        if (bitlen != last) {
            last = bitlen;
            inc = 1u << (16 - bitlen);
        }
        codes[perm[i]] = (uint16_t)code;
    }

    t->maxlen = maxlen;
    t->table = malloc(sizeof(uint16_t) << maxlen);
    if (t->table == NULL) return -1;
    for (i = 0; i < (1u << maxlen); i++) t->table[i] = SF_UNDEFINED;
    for (i = 0; i < n; i++) {
        unsigned bitlen = lens[i], rev = 0, b;
        if (bitlen == 0) continue;
        /* The stream sends a code's most significant bit first. */
        for (b = 0; b < bitlen; b++) rev |= ((codes[i] >> (15 - b)) & 1u) << b;
        for (k = rev; k < (1u << maxlen); k += 1u << bitlen) {
            t->table[k] = (uint16_t)((bitlen << 8) | i);
        }
    }
    return 0;
}

/* Returns the decoded value, -1 at the end of the input, or -2 for an undefined code. */
static int sf_decode(xf_explode *s, const sf_tree *t) {
    uint16_t e;
    unsigned bitlen;
    explode_need(s, t->maxlen); /* may come up short at the very end */
    e = t->table[s->hold & ((1u << t->maxlen) - 1u)];
    if (e == SF_UNDEFINED) return s->bits >= t->maxlen ? -2 : -1;
    bitlen = e >> 8;
    if (bitlen > s->bits) return -1;
    s->hold >>= bitlen;
    s->bits -= bitlen;
    return e & 0xff;
}

xf_explode *xf_explode_new(int dict8k, int three_trees, xf_byte_source src, void *ctx) {
    xf_explode *s = calloc(1, sizeof *s);
    if (s == NULL) return NULL;
    s->src = src;
    s->ctx = ctx;
    s->dict8k = dict8k;
    s->three_trees = three_trees;
    return s;
}

void xf_explode_free(xf_explode *s) {
    if (s == NULL) return;
    free(s->literals.table);
    free(s->lengths.table);
    free(s->distances.table);
    free(s);
}

ssize_t xf_explode_read(xf_explode *s, uint8_t *out, size_t len, const char **error) {
    size_t n = 0;
    if (!s->started) {
        if ((s->three_trees && read_tree(s, &s->literals, 256) != 0) ||
            read_tree(s, &s->lengths, 64) != 0 || read_tree(s, &s->distances, 64) != 0) {
            *error = "invalid Shannon-Fano trees in imploded data";
            return -1;
        }
        s->started = 1;
    }
    while (n < len) {
        int bit;
        if (s->copy_left > 0) {
            uint8_t b = s->window[(s->wpos - s->copy_dist) & (EXPLODE_WINDOW - 1u)];
            out[n++] = b;
            s->window[s->wpos] = b;
            s->wpos = (s->wpos + 1u) & (EXPLODE_WINDOW - 1u);
            s->copy_left--;
            continue;
        }
        bit = explode_bits(s, 1);
        if (bit < 0) break;
        if (bit == 1) {
            int literal = s->three_trees ? sf_decode(s, &s->literals) : explode_bits(s, 8);
            if (literal == -1) break;
            if (literal < 0) goto corrupt;
            out[n++] = (uint8_t)literal;
            s->window[s->wpos] = (uint8_t)literal;
            s->wpos = (s->wpos + 1u) & (EXPLODE_WINDOW - 1u);
        } else {
            unsigned low_bits = s->dict8k ? 7u : 6u;
            int low = explode_bits(s, low_bits);
            int high, length;
            if (low < 0) break;
            high = sf_decode(s, &s->distances);
            if (high == -1) break;
            if (high < 0) goto corrupt;
            length = sf_decode(s, &s->lengths);
            if (length == -1) break;
            if (length < 0) goto corrupt;
            if (length == 63) {
                int more = explode_bits(s, 8);
                if (more < 0) break;
                length += more;
            }
            /* Bytes before the start of the output read as zeros, like the empty window. */
            s->copy_dist = (((uint32_t)high << low_bits) | (uint32_t)low) + 1u;
            s->copy_left = (uint32_t)length + (s->three_trees ? 3u : 2u);
        }
    }
    return (ssize_t)n;

corrupt:
    *error = "invalid code in imploded data";
    return -1;
}

/* ---------------------------------------------------------------------------------------------
 * Shrink (method 1)
 * ------------------------------------------------------------------------------------------- */

#define SHRINK_MAX_BITS 13
#define SHRINK_TABLE (1 << SHRINK_MAX_BITS)
#define SHRINK_CONTROL 256
#define SHRINK_FIRST_FREE 257

struct xf_unshrink {
    xf_byte_source src;
    void *ctx;
    uint32_t hold;
    unsigned bits;
    unsigned code_size;
    int table_size;
    int prev_code;
    uint8_t prev_first;
    int sp; /* pending output is stack[sp..SHRINK_TABLE) */
    int16_t prefix[SHRINK_TABLE];
    uint8_t suffix[SHRINK_TABLE];
    uint8_t used[SHRINK_TABLE];
    uint8_t stack[SHRINK_TABLE];
};

static int unshrink_code(xf_unshrink *s) {
    int v;
    while (s->bits < s->code_size) {
        int c = s->src(s->ctx);
        if (c < 0) return -1;
        s->hold |= (uint32_t)c << s->bits;
        s->bits += 8;
    }
    v = (int)(s->hold & ((1u << s->code_size) - 1u));
    s->hold >>= s->code_size;
    s->bits -= s->code_size;
    return v;
}

/* Stores prev + ch in the lowest free slot, as the compressor did. -1 when the table is full. */
static int unshrink_add(xf_unshrink *s, int prev, uint8_t ch) {
    int ts = s->table_size;
    while (ts < SHRINK_TABLE && s->used[ts]) ts++;
    s->table_size = ts;
    if (ts >= SHRINK_TABLE) return -1;
    s->prefix[ts] = (int16_t)prev;
    s->suffix[ts] = ch;
    s->used[ts] = 1;
    s->table_size = ts + 1;
    return ts;
}

/* Frees every code that no other code uses as its prefix. */
static void unshrink_partial_clear(xf_unshrink *s) {
    uint8_t parent[SHRINK_TABLE];
    int i;
    memset(parent, 0, sizeof parent);
    for (i = 0; i < SHRINK_TABLE; i++) {
        if (s->used[i] && s->prefix[i] >= 0) parent[s->prefix[i]] = 1;
    }
    for (i = SHRINK_FIRST_FREE; i < SHRINK_TABLE; i++) {
        if (!parent[i]) {
            s->used[i] = 0;
            s->prefix[i] = -1;
        }
    }
}

xf_unshrink *xf_unshrink_new(xf_byte_source src, void *ctx) {
    int i;
    xf_unshrink *s = calloc(1, sizeof *s);
    if (s == NULL) return NULL;
    s->src = src;
    s->ctx = ctx;
    s->code_size = 9;
    s->table_size = SHRINK_FIRST_FREE;
    s->prev_code = -1;
    s->sp = SHRINK_TABLE;
    for (i = 0; i < SHRINK_TABLE; i++) s->prefix[i] = -1;
    for (i = 0; i < 256; i++) {
        s->suffix[i] = (uint8_t)i;
        s->used[i] = 1;
    }
    return s;
}

void xf_unshrink_free(xf_unshrink *s) {
    free(s);
}

ssize_t xf_unshrink_read(xf_unshrink *s, uint8_t *out, size_t len, const char **error) {
    size_t n = 0;
    while (n < len) {
        int code, entry, sp, unfinished = 0;
        if (s->sp < SHRINK_TABLE) {
            out[n++] = s->stack[s->sp++];
            continue;
        }
        code = unshrink_code(s);
        if (code < 0) break;
        if (code == SHRINK_CONTROL) {
            int sub = unshrink_code(s);
            if (sub == 1) {
                if (s->code_size >= SHRINK_MAX_BITS) goto corrupt;
                s->code_size++;
            } else if (sub == 2) {
                unshrink_partial_clear(s);
                s->table_size = SHRINK_FIRST_FREE;
            } else if (sub < 0) {
                break;
            } else {
                goto corrupt;
            }
            continue;
        }
        if (!s->used[code]) {
            /* The code the compressor was defining as it used it: previous string + its first byte. */
            if (s->prev_code < 0) goto corrupt;
            code = unshrink_add(s, s->prev_code, s->prev_first);
            if (code < 0) goto corrupt;
            unfinished = 1;
        }
        sp = SHRINK_TABLE;
        for (entry = code; entry >= 0; entry = s->prefix[entry]) {
            if (sp == 0) goto corrupt;
            s->stack[--sp] = s->suffix[entry];
        }
        if (s->prev_code >= 0 && !unfinished) unshrink_add(s, s->prev_code, s->stack[sp]);
        s->prev_code = code;
        s->prev_first = s->stack[sp];
        s->sp = sp;
    }
    return (ssize_t)n;

corrupt:
    *error = "invalid code in shrunk data";
    return -1;
}
