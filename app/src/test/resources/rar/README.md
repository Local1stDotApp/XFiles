# RAR test archives

These archives come from libarchive's test suite: the `libarchive/test/*.rar.uu` files in
https://github.com/libarchive/libarchive, decoded with `uudecode`, names unchanged. They were
made by WinRAR, which nothing free can write, and they cover what the RAR reader has to get
right: volume sets whose members cross volumes (PPMd in RAR 4, solid in RAR 5), encrypted
members, and a solid RAR 4 archive that the full flavor hands to junrar. `RarReadingTest` and
the flavors' `RarFlavorTest` read them; the expected MD5s come from macOS `bsdtar`.
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
