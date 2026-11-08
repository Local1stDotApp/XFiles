# Vendored C sources

libxfarchive (`../CMakeLists.txt`) is built from these upstream releases. Each directory keeps
only the files the build compiles, the headers they include, and the project's licence;
`update.sh` re-creates them from the release tarballs. One file is modified: libarchive's RAR 4
reader, `libarchive/archive_read_support_format_rar.c`, by `patches/libarchive-rar4.patch`,
which `update.sh` applies. It reads solid RAR 4 archives and filters in PPMd blocks; the patch's
header lists the changes.

| Directory | Project | Version | Tarball SHA-256 | Licence |
|---|---|---|---|---|
| `libarchive/` | [libarchive](https://libarchive.org) | 3.8.11 | `a3dc0872b1b39da34dc18ef1b944f0f11ffd37425d70ae04be679279f1cf43d2` (`.tar.xz`) | BSD 2-clause (`COPYING`) |
| `zstd/` | [Zstandard](https://github.com/facebook/zstd), decoder | 1.5.7 | `eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3` | BSD 3-clause (`LICENSE`) |
| `xz/` | [XZ Utils](https://tukaani.org/xz/) liblzma, decoders | 5.8.4 | `0014c7886930454fe8bd4228665b51af55eeae560ea135c9c4cd33f55b2591d9` (`.tar.gz`) | 0BSD (`COPYING`, `COPYING.0BSD`) |
| `bzip2/` | [bzip2](https://sourceware.org/bzip2/) | 1.0.8 | `ab5a03176ee106d3f0fa90e381da478ddae405918153cca248e682cd0c4a2269` | BSD-style (`LICENSE`) |
| `lz4/` | [LZ4](https://github.com/lz4/lz4), block decoder | 1.10.0 | `537512904744b35e232912055ccf8ec66d768639ff3abe5788d90d792ec5f48b` | BSD 2-clause (`LICENSE`) |

libarchive is a read-only subset: the 7z, RAR, RAR5, tar, cpio, ar, ISO 9660 and raw readers,
and the gzip, bzip2, xz/lzma/lzip, zstd, lz4 and compress filters. Zip is read by our own
`../xfarchive/zip_reader.c`, which uses libarchive's PPMd8 decoder for zip method 98.

To update: download the releases, check them against the checksums their projects publish,
extract them, and run

    ./update.sh <libarchive-x.y.z> <zstd-x.y.z> <xz-x.y.z> <bzip2-x.y.z> <lz4-x.y.z>

then update this table, rebuild, and run the unit tests (`ArchiveFormatsTest`, `RarReadingTest`,
`SolidRar4Test`). If the RAR patch no longer applies, merge it by hand and regenerate it with
`diff -u` against the upstream file.
Security fixes in libarchive's format readers are the main reason to update; read its release
notes for the readers listed above.
