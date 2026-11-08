# Archive test files

One small archive per format and compression method that libxfarchive reads, for
`ArchiveFormatsTest`. Names are unchanged from their sources. `expected.tsv` lists every file
inside, with its size and MD5, as macOS `bsdtar` (libarchive 3.7.4) extracts it, or Apache
Commons Compress 1.28 for the zip methods bsdtar can't read (Deflate64, Implode, Shrink). One
row is set by hand: `test_read_format_zip_zstd.zipx` holds the same `vimrc` as the other zipx
files, and this macOS `bsdtar` has no zstd.

## From libarchive's test suite

`test_*` files: the `libarchive/test/*.uu` files of https://github.com/libarchive/libarchive
3.8.11, decoded with `uudecode`. `test_read_format_iso_joliet_rockridge.iso` is
`test_read_format_iso_joliet_rockridge.iso.Z` renamed; it stays compressed, which the ISO reader
undoes by itself. They are under libarchive's licence:

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

## From Apache Commons Compress's test suite

`bzip2-zip.zip`, `COMPRESS-380-dd.zip` (Deflate64 with a data descriptor),
`imploding-4Kdict-2trees.zip`, `imploding-8Kdict-3trees.zip`, `lorem-ipsum-deflate64.zip`,
`shrunk.zip` (`SHRUNK.ZIP` there) and `test-method-xz.zip`: `src/test/resources` of
https://github.com/apache/commons-compress. They are under the Apache License 2.0
(https://www.apache.org/licenses/LICENSE-2.0); Commons Compress's NOTICE reads:

```
Apache Commons Compress
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```
