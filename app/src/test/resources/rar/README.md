# RAR test archives

## Solid RAR 4 (`solid/`)

Made for this project, for `SolidRar4Test`. `solid/gen_sources.py` writes deterministic source
trees (text, binary, x86-like code, audio, RGB and table data, empty files) and
`rar4_solid.md5`, the MD5 of every source file. `solid/make.sh` builds the archives from them
with RAR 6.24 for Linux, the last RAR that writes RAR 4, forcing each case: LZ only with a 64 KB
window that wraps, PPMd only, both mixed, solid groups, a volume set, empty members between the
others, stored members among compressed ones, and each filter (x86, also inside PPMd blocks;
audio, RGB, delta). RAR is proprietary, so it runs in a throwaway container:

```
python3 gen_sources.py /tmp/src
docker run --rm --platform linux/amd64 -v <rar-6.24>:/rar:ro -v /tmp/src:/src:ro \
  -v /tmp/out:/out -v "$PWD":/tools:ro debian:trixie-slim sh /tools/make.sh
```

RAR 6.24 for Linux: https://www.rarlab.com/rar/rarlinux-x64-624.tar.gz (SHA-256
`88e22a8e84125c947637bbf28c746e338a0a63279d80f9f9d7373603875db1eb`). The archives hold only
the generated data and are under this repository's licence.

## RAR 2.x (`rar2/`)

Made for this project, for `Rar2Test`, the same way: `rar2/gen_sources.py` writes the source
trees and `rar2.md5`, and `rar2/make.sh` packs them with RAR 2.80 for Linux (2001), forcing
each case: LZ alone, solid or not, with a 64 KB window that wraps and with matches from more
than 256 KB back; audio blocks with one to four channels; audio and LZ blocks in turn within
and across solid members; empty members, among them a solid archive's first, which holds its
tables; a set of three volumes (`.rar`, `.r00`, `.r01`); a Unix owner subblock after each
member and a recovery record at the end; an archive comment and a file comment; and encrypted
members. RAR 2.80 is a 32-bit x86 program, so it runs in a `linux/386` container:

```
python3 gen_sources.py /tmp/src
docker run --rm --platform linux/386 -v <rar-2.80>:/rar:ro -v /tmp/src:/src:ro \
  -v /tmp/out:/out -v "$PWD":/tools:ro debian:bookworm-slim sh /tools/make.sh
```

RAR 2.80 for Linux, `rarlnx28.sfx`, from RARLAB's old site through the Wayback Machine:
https://web.archive.org/web/20010719204404id_/http://www.rarsoft.com/rar/rarlnx28.sfx (SHA-256
`b61a1fed86b80dbf9bd91ce86c77d2679e972671141ef75fbf27dc91978f0bec`). It unpacks itself when run
in the same container. The archives hold only the generated data and are under this
repository's licence.

## From rarfile

These come from the test suite of rarfile, https://github.com/markokr/rarfile (`test/files`).
`rar2-unix-owner.rar` holds a member packed with the RAR 2.0 algorithm, then a Unix owner
subblock and a recovery record, and no end-of-archive block. `rar15-comment.rar` was made by
RAR 1.5x: an archive comment, and two members with a file comment each, one packed with the
RAR 1.5 algorithm and one stored. `rar15.rar` is `rar15-comment.rar` without its file comments
(sizes, flags and header CRCs fixed to match), as junrar can't read a member whose header holds
one. Their licence:

```
Copyright (c) 2005-2026 Marko Kreen <markokr@gmail.com>

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## From libarchive

These archives come from libarchive's test suite: the `libarchive/test/*.rar.uu` files in
https://github.com/libarchive/libarchive, decoded with `uudecode`, names unchanged. They were
made by WinRAR, which nothing free can write, and they cover what the RAR reader has to get
right: volume sets whose members cross volumes (PPMd in RAR 4, solid in RAR 5), and encrypted
members, in RAR 5 and in a solid RAR 4 archive. `RarReadingTest` and the flavors'
`RarFlavorTest` read them; the expected MD5s come from macOS `bsdtar`.
`truncated_rar5_multiple_files_solid.rar` is the first 108 bytes of
`test_read_format_rar5_multiple_files_solid.rar`, on which libarchive returns the same failed
header forever to a caller that carries on (libarchive issue 3492).

They are under libarchive's licence:

```
Copyright (c) 2003-2018 <author(s)>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer
   in this position and unchanged.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
