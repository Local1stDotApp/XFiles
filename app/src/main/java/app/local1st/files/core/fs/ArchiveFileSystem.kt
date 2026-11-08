package app.local1st.files.core.fs

import app.local1st.files.core.archive.ArchivePaths
import app.local1st.files.core.archive.LibArchiveReader
import app.local1st.files.core.archive.NativeArchive
import app.local1st.files.core.archive.NativeZip
import app.local1st.files.core.util.FileTypes
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Read-only browsing of archive files as virtual folders.
 *
 * Serves every `zip://<archive>!/<inner>` id regardless of the actual archive format, all read
 * by libxfarchive (native code): zip and its relatives (jar, apk, zipx, split-APK bundles) by
 * its random-access zip reader; 7z, RAR, tar (plain or compressed with gzip, bzip2, xz, zstd,
 * lz4, lzip, lzma or compress), ISO 9660, cpio and ar/deb by its libarchive; and single-file
 * compressed streams as one-file pseudo-archives. The full flavor's [RarArchives] still reads
 * solid RAR 4 archives, which libarchive can't.
 *
 * Each archive is parsed once into an in-memory tree of [Node]s, cached in a small
 * LRU keyed by archive path + file mtime + length, so re-listing directories and
 * stat() are cheap. All methods are blocking IO (callers run them on Dispatchers.IO).
 */
class ArchiveFileSystem(
    /** Volume roots whose `.xfiles-trash` holds links that must not be followed. */
    private val volumeRoots: () -> List<String> = { emptyList() },
    /** Size and mtime of a grant-only archive. Null when the grant has no document. */
    private val grantStamp: (File) -> Pair<Long, Long>? = { null },
    /** Bytes of an archive [File] cannot see. Null when there is no grant stream. */
    private val openGrant: (File) -> InputStream? = { null },
    /** MIME type by file name; the JVM tests swap out Android's lookup. */
    private val mimeOf: (String) -> String? = FileTypes::mimeOf,
) : XFileSystem {

    override val scheme: String = XId.SCHEME_ZIP

    override fun list(dir: XEntry): List<XEntry> {
        val (archivePath, innerPath) = resolveTarget(dir)
        val archiveFile = requireArchiveFile(archivePath)
        val tree = treeFor(archiveFile)
        val node = tree.byPath[innerPath]
            ?: throw IOException("Folder not found in ${archiveFile.name}: /$innerPath")
        if (!node.isDir) {
            if (FileTypes.isBrowsableArchive(node.name)) {
                throw IOException("Cannot browse an archive inside another archive: ${node.name}")
            }
            throw IOException("Not a folder: ${node.name}")
        }
        return node.children.values
            .sortedWith(compareByDescending<Node> { it.isDir }.thenBy { it.name.lowercase() })
            .map { toEntry(archivePath, it) }
    }

    override fun stat(id: String): XEntry? {
        if (XId.schemeOf(id) != XId.SCHEME_ZIP) return null
        val archivePath = XId.zipArchivePath(id)
        if (followsIntoBin(archivePath)) return null
        val archiveFile = try {
            requireArchiveFile(archivePath)
        } catch (_: IOException) {
            return null
        }
        val tree = try {
            treeFor(archiveFile)
        } catch (_: Exception) {
            return null
        }
        val node = tree.byPath[XId.zipInnerPath(id)] ?: return null
        return toEntry(archivePath, node)
    }

    override fun openIn(entry: XEntry): InputStream {
        if (XId.schemeOf(entry.id) == XId.SCHEME_FILE) {
            // The raw archive file itself, routed here via FsRegistry.resolveScheme.
            return FileInputStream(requireArchiveFile(entry.localPath ?: entry.path))
        }
        if (XId.schemeOf(entry.id) != XId.SCHEME_ZIP) {
            throw IOException("Not an archive entry: ${entry.name}")
        }
        val archiveFile = requireArchiveFile(XId.zipArchivePath(entry.id))
        val innerPath = XId.zipInnerPath(entry.id)
        val node = treeFor(archiveFile).byPath[innerPath]
            ?: throw IOException("Entry not found in ${archiveFile.name}: /$innerPath")
        if (node.isDir) throw IOException("Cannot open a folder as a file: ${node.name}")
        val rawName = node.rawName ?: node.innerPath
        return try {
            when (val format = formatOf(archiveFile.name)) {
                ArchiveFormat.ZIP -> openZipEntry(archiveFile, node.index)
                ArchiveFormat.RAR -> if (RarArchives.readsItself(archiveFile)) {
                    RarArchives.openEntry(archiveFile, rawName, node.index)
                } else {
                    openLibArchiveEntry(archiveFile, format, node.index, rawName)
                }
                ArchiveFormat.SINGLE -> openSingleCompressed(archiveFile)
                else -> openLibArchiveEntry(archiveFile, format, node.index, rawName)
            }
        } catch (e: Exception) {
            throw e.asCannotOpen(archiveFile)
        }
    }

    /**
     * Streams every file in the archive behind [archive] (its own entry or any entry inside it)
     * once, in archive order. [visit] gets each file's inner path, as [list] shows it, and a
     * stream that is only valid during the call. One pass: extracting a tar.xz or a solid 7z
     * decodes it once, instead of once per file.
     */
    fun forEachFile(archive: XEntry, visit: (innerPath: String, input: InputStream) -> Unit) {
        val archivePath = if (XId.schemeOf(archive.id) == XId.SCHEME_ZIP) {
            XId.zipArchivePath(archive.id)
        } else {
            archive.localPath ?: archive.path
        }
        val archiveFile = requireArchiveFile(archivePath)
        val tree = treeFor(archiveFile)
        val format = formatOf(archiveFile.name)
        /** The node an archive entry fills, unless a later entry or a folder took its place. */
        fun target(rawName: String, index: Int): Node? {
            val node = tree.byPath[ArchivePaths.normalize(rawName)] ?: return null
            return node.takeIf { !it.isDir && it.index == index }
        }
        when {
            format == ArchiveFormat.ZIP -> NativeZip.open(archiveFile).use { zip ->
                for (zipEntry in zip.entries) {
                    if (zipEntry.isDirectory) continue
                    val node = target(zipEntry.name, zipEntry.index) ?: continue
                    zip.openEntry(zipEntry.index).use { visit(node.innerPath, it) }
                }
            }
            format == ArchiveFormat.SINGLE -> {
                val node = tree.root.children.values.firstOrNull() ?: return
                openSingleCompressed(archiveFile).use { visit(node.innerPath, it) }
            }
            format == ArchiveFormat.RAR && RarArchives.readsItself(archiveFile) ->
                RarArchives.forEachFile(archiveFile) { rawName, index, input ->
                    target(rawName, index)?.let { visit(it.innerPath, input) }
                }
            else -> openLibArchive(archiveFile, format).use { reader ->
                while (true) {
                    val e = reader.next() ?: break
                    if (!e.isFile) continue
                    val node = target(entryName(e, archiveFile), e.ordinal) ?: continue
                    visit(node.innerPath, reader.entryStream())
                }
            }
        }
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream = throw readOnly()

    override fun createFile(parentDir: XEntry, name: String): XEntry = throw readOnly()

    override fun mkdir(parentDir: XEntry, name: String): XEntry = throw readOnly()

    override fun delete(entry: XEntry) {
        throw readOnly()
    }

    override fun rename(entry: XEntry, newName: String): XEntry = throw readOnly()

    override fun canWrite(entry: XEntry): Boolean = false

    // ---------------------------------------------------------------------------------------
    // Formats
    // ---------------------------------------------------------------------------------------

    /** How an archive is read; compression layers are found by content, not by name. */
    private enum class ArchiveFormat(val formats: Int) {
        ZIP(0),
        SEVEN_Z(NativeArchive.FORMAT_7ZIP),
        RAR(NativeArchive.FORMAT_RAR),
        TAR(NativeArchive.FORMAT_TAR),
        ISO(NativeArchive.FORMAT_ISO9660),
        CPIO(NativeArchive.FORMAT_CPIO),
        AR(NativeArchive.FORMAT_AR),

        /** One compressed file (.gz, .xz, …): an archive holding just that file. */
        SINGLE(NativeArchive.FORMAT_RAW),
    }

    private fun formatOf(fileName: String): ArchiveFormat {
        val n = fileName.lowercase()
        val ext = n.substringAfterLast('.', "")
        return when {
            n.endsWith(".tar") || TAR_SUFFIXES.any { n.endsWith(it) } -> ArchiveFormat.TAR
            ext in TAR_EXTENSIONS -> ArchiveFormat.TAR
            ext in ZIP_EXTENSIONS || ext in FileTypes.apkBundleExtensions -> ArchiveFormat.ZIP
            ext == "7z" -> ArchiveFormat.SEVEN_Z
            ext == "rar" -> ArchiveFormat.RAR
            ext == "iso" -> ArchiveFormat.ISO
            ext == "cpio" -> ArchiveFormat.CPIO
            ext == "ar" || ext == "deb" -> ArchiveFormat.AR
            ext in SINGLE_EXTENSIONS -> ArchiveFormat.SINGLE
            else -> throw IOException("Unsupported archive type: $fileName")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Parsed tree + LRU cache
    // ---------------------------------------------------------------------------------------

    private class Node(
        val name: String,
        /** Normalized path inside the archive, no leading/trailing slash, "" for root. */
        val innerPath: String,
        var isDir: Boolean,
        var size: Long = -1L,
        var mtime: Long = 0L,
        /** Exact entry name as stored in the archive. */
        var rawName: String? = null,
        /** The entry's position in the archive, used to reopen it; -1 for implied folders. */
        var index: Int = -1,
    ) {
        val children = LinkedHashMap<String, Node>()
    }

    private class ArchiveTree(val root: Node, val byPath: Map<String, Node>)

    private data class CacheKey(val path: String, val mtime: Long, val length: Long)

    private val cache = object : LinkedHashMap<CacheKey, ArchiveTree>(MAX_CACHED_ARCHIVES + 1, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ArchiveTree>): Boolean =
            size > MAX_CACHED_ARCHIVES
    }

    private fun treeFor(archiveFile: File): ArchiveTree {
        val key = CacheKey(archiveFile.absolutePath, archiveFile.lastModified(), archiveFile.length())
        synchronized(cache) { cache[key]?.let { return it } }
        // Parse outside the lock so different archives can be parsed concurrently;
        // a rare duplicate parse of the same archive is harmless.
        val tree = parse(archiveFile)
        synchronized(cache) {
            cache.keys.removeAll { it.path == key.path && it != key }
            cache[key] = tree
        }
        return tree
    }

    private fun parse(archiveFile: File): ArchiveTree {
        val format = formatOf(archiveFile.name)
        val builder = TreeBuilder(archiveFile.name, archiveFile.lastModified())
        try {
            when {
                format == ArchiveFormat.ZIP -> parseZip(archiveFile, builder)
                format == ArchiveFormat.SINGLE -> parseSingleCompressed(archiveFile, builder)
                format == ArchiveFormat.RAR && RarArchives.readsItself(archiveFile) ->
                    RarArchives.list(archiveFile, builder::add)
                else -> parseLibArchive(archiveFile, format, builder)
            }
        } catch (e: Exception) {
            throw IOException("Cannot open archive ${archiveFile.name}", e)
        }
        return builder.build()
    }

    /** Builds the node tree, synthesizing parent dirs the archive never declared explicitly. */
    private class TreeBuilder(rootName: String, rootMtime: Long) {
        private val root = Node(name = rootName, innerPath = "", isDir = true, mtime = rootMtime)
        private val byPath = HashMap<String, Node>().apply { put("", root) }

        fun add(rawName: String, isDir: Boolean, size: Long, mtime: Long, index: Int) {
            val normalized = ArchivePaths.normalize(rawName)
            if (normalized.isEmpty()) return
            val segments = normalized.split('/')
            var current = root
            val pathSoFar = StringBuilder()
            for ((i, segment) in segments.withIndex()) {
                val isLast = i == segments.lastIndex
                if (pathSoFar.isNotEmpty()) pathSoFar.append('/')
                pathSoFar.append(segment)
                val segmentIsDir = !isLast || isDir
                var child = current.children[segment]
                if (child == null) {
                    child = Node(name = segment, innerPath = pathSoFar.toString(), isDir = segmentIsDir)
                    current.children[segment] = child
                    byPath[child.innerPath] = child
                } else if (segmentIsDir && !child.isDir) {
                    // A path used a file's name as a directory, or an explicit dir entry
                    // collides with a same-named file: the container wins.
                    child.isDir = true
                    child.size = -1L
                }
                if (isLast) {
                    child.rawName = rawName
                    child.mtime = mtime
                    child.index = index
                    if (!child.isDir) child.size = size
                }
                current = child
            }
        }

        fun build(): ArchiveTree = ArchiveTree(root, byPath)
    }

    private fun toEntry(archivePath: String, node: Node): XEntry = XEntry(
        id = XId.zip(archivePath, node.innerPath),
        name = node.name,
        isDir = node.isDir,
        size = if (node.isDir) -1L else node.size,
        mtime = node.mtime,
        mime = if (node.isDir) null else mimeOf(node.name),
        hidden = node.name.startsWith("."),
        canRead = true,
        canWrite = false,
        kind = if (node.isDir) EntryKind.DIR else EntryKind.FILE,
        childCountHint = if (node.isDir) node.children.size else -1,
        hiddenChildCountHint = if (node.isDir) {
            node.children.keys.count { it.startsWith(".") }
        } else {
            0
        },
        localPath = null,
    )

    // ---------------------------------------------------------------------------------------
    // Per-format parsing
    // ---------------------------------------------------------------------------------------

    private fun parseZip(archiveFile: File, builder: TreeBuilder) {
        NativeZip.open(archiveFile).use { zip ->
            for (e in zip.entries) {
                builder.add(
                    rawName = e.name,
                    isDir = e.isDirectory,
                    size = e.size,
                    mtime = e.time.takeIf { it > 0 } ?: 0L,
                    index = e.index,
                )
            }
        }
    }

    private fun parseLibArchive(archiveFile: File, format: ArchiveFormat, builder: TreeBuilder) {
        openLibArchive(archiveFile, format).use { reader ->
            while (true) {
                val e = reader.next() ?: break
                // Files and folders only; links and devices have no bytes to browse.
                if (!e.isDirectory && !e.isFile) continue
                builder.add(
                    rawName = entryName(e, archiveFile),
                    isDir = e.isDirectory,
                    size = if (e.isDirectory) -1L else e.size,
                    mtime = e.mtime.takeIf { it > 0 } ?: 0L,
                    index = e.ordinal,
                )
            }
        }
    }

    private fun parseSingleCompressed(archiveFile: File, builder: TreeBuilder) {
        // Opening the stream checks that the file really is compressed, so a corrupt or
        // mislabeled file fails at list() time.
        openSingleCompressed(archiveFile).close()
        val name = archiveFile.name.substringBeforeLast('.').ifEmpty { archiveFile.name }
        val sizeHint = if (isGzip(archiveFile)) gzipSizeHint(archiveFile) else -1L
        builder.add(rawName = name, isDir = false, size = sizeHint, mtime = archiveFile.lastModified(), index = 0)
    }

    private fun isGzip(archiveFile: File): Boolean =
        archiveFile.name.lowercase().endsWith(".gz")

    /** Uncompressed size from the gzip ISIZE trailer (mod 2^32, so only a hint). */
    private fun gzipSizeHint(archiveFile: File): Long {
        if (archiveFile.length() < 18L) return -1L
        return try {
            RandomAccessFile(archiveFile, "r").use { raf ->
                raf.seek(raf.length() - 4)
                val b = ByteArray(4)
                raf.readFully(b)
                (b[0].toLong() and 0xFF) or
                    ((b[1].toLong() and 0xFF) shl 8) or
                    ((b[2].toLong() and 0xFF) shl 16) or
                    ((b[3].toLong() and 0xFF) shl 24)
            }
        } catch (_: IOException) {
            -1L
        }
    }

    // ---------------------------------------------------------------------------------------
    // Per-format entry streaming
    // ---------------------------------------------------------------------------------------

    private fun openZipEntry(archiveFile: File, index: Int): InputStream {
        val zip = NativeZip.open(archiveFile)
        try {
            return ResourceClosingInputStream(zip.openEntry(index), zip)
        } catch (e: Exception) {
            closeQuietly(zip)
            throw e
        }
    }

    /**
     * Walks to entry [index] and streams it. A name check guards against an archive that
     * changed since it was listed.
     */
    private fun openLibArchiveEntry(
        archiveFile: File,
        format: ArchiveFormat,
        index: Int,
        rawName: String,
    ): InputStream {
        val reader = openLibArchive(archiveFile, format)
        try {
            while (true) {
                val e = reader.next() ?: break
                if (e.ordinal < index) continue
                if (e.ordinal == index && e.isFile && entryName(e, archiveFile) == rawName) {
                    if (e.isEncrypted) throw IOException("$rawName is password-protected")
                    return reader.detachEntryStream()
                }
                break
            }
            throw IOException("Entry not found in ${archiveFile.name}: $rawName")
        } catch (e: Exception) {
            closeQuietly(reader)
            throw e
        }
    }

    private fun openLibArchive(archiveFile: File, format: ArchiveFormat): LibArchiveReader {
        val volumes = if (format == ArchiveFormat.RAR) RarVolumes.volumesOf(archiveFile) else listOf(archiveFile)
        return LibArchiveReader.open(volumes, format.formats)
    }

    private fun openSingleCompressed(archiveFile: File): InputStream {
        val reader = LibArchiveReader.open(listOf(archiveFile), ArchiveFormat.SINGLE.formats)
        try {
            // The raw reader takes any bytes; only a decompression filter makes it an archive.
            if (reader.next() == null || reader.filter == 0) {
                throw IOException("${archiveFile.name} is not compressed data")
            }
            return reader.detachEntryStream()
        } catch (e: Exception) {
            closeQuietly(reader)
            throw e
        }
    }

    /** 7z can store a single unnamed entry; fall back to the archive's own base name. */
    private fun entryName(entry: LibArchiveReader.Entry, archiveFile: File): String =
        entry.path.ifEmpty { archiveFile.name.substringBeforeLast('.').ifEmpty { archiveFile.name } }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun resolveTarget(dir: XEntry): Pair<String, String> = when (XId.schemeOf(dir.id)) {
        // An ARCHIVE entry on the file scheme, routed here via FsRegistry.resolveScheme.
        XId.SCHEME_FILE -> (dir.localPath ?: dir.path) to ""
        XId.SCHEME_ZIP -> XId.zipArchivePath(dir.id) to XId.zipInnerPath(dir.id)
        else -> throw IOException("Not an archive: ${dir.name}")
    }

    private fun followsIntoBin(path: String): Boolean {
        val roots = volumeRoots()
        return TrashPaths.isVolumeBinSymlink(path, roots) || TrashPaths.crossesVolumeBin(path, roots)
    }

    private data class StagedGrant(val file: File, val size: Long, val mtime: Long)

    private val stagedGrants = HashMap<String, StagedGrant>()
    private val stagedGrantLock = Any()

    private fun requireArchiveFile(path: String): File {
        val file = File(path)
        // File.isFile and the native readers follow links. A bin symlink must not open the
        // live target.
        if (followsIntoBin(path)) {
            throw IOException("Cannot open ${file.name}")
        }
        if (file.isFile) return file
        return stageGrantArchive(file) ?: throw IOException("Archive not found: ${file.name}")
    }

    /** The native readers need a real file. Reuse a staged copy only while size and mtime match. */
    private fun stageGrantArchive(file: File): File? {
        val path = file.absolutePath
        val stamp = grantStamp(file)
        if (stamp != null) {
            synchronized(stagedGrantLock) {
                val cached = stagedGrants[path]
                if (cached != null && cached.file.isFile &&
                    cached.size == stamp.first && cached.mtime == stamp.second
                ) {
                    return cached.file
                }
            }
        }
        val input = openGrant(file) ?: return null
        val staged = File.createTempFile("xfiles-arc-", stageSuffix(file.name))
        staged.deleteOnExit()
        try {
            input.use { source -> staged.outputStream().use { dest -> source.copyTo(dest) } }
        } catch (error: IOException) {
            staged.delete()
            throw error
        }
        if (stamp == null) return staged
        synchronized(stagedGrantLock) {
            val current = stagedGrants[path]
            if (current != null && current.file.isFile &&
                current.size == stamp.first && current.mtime == stamp.second
            ) {
                staged.delete()
                return current.file
            }
            stagedGrants[path] = StagedGrant(staged, stamp.first, stamp.second)
            if (current != null) current.file.delete()
        }
        return staged
    }

    /** Compound tar suffixes must survive; format detection reads the staged name. */
    private fun stageSuffix(name: String): String {
        val lower = name.lowercase()
        val compound = TAR_SUFFIXES.firstOrNull { lower.endsWith(it) }
        if (compound != null) return compound
        val suffix = name.substringAfterLast('.', "")
        return if (suffix.isNotEmpty() && suffix.length <= 8 && '.' !in suffix) ".$suffix" else ".bin"
    }

    private fun readOnly(): IOException = IOException("Archives are read-only")

    private fun Exception.asCannotOpen(archiveFile: File): IOException =
        this as? IOException ?: IOException("Cannot open archive ${archiveFile.name}", this)

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Best-effort cleanup on a failure path.
        }
    }

    /** Forwards reads to [base]; closing also closes [alsoClose] (e.g. the backing archive). */
    private class ResourceClosingInputStream(
        base: InputStream,
        private val alsoClose: Closeable,
    ) : FilterInputStream(base) {
        override fun close() {
            try {
                super.close()
            } finally {
                alsoClose.close()
            }
        }
    }

    private companion object {
        const val MAX_CACHED_ARCHIVES = 8

        val ZIP_EXTENSIONS = setOf("zip", "zipx", "jar", "apk", "aab")

        /** Compressed tars named with one extension. */
        val TAR_EXTENSIONS = setOf("tgz", "taz", "tbz", "tbz2", "txz", "tzst", "tlz")

        /** Compressed tars named with two. */
        val TAR_SUFFIXES = listOf(
            ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tar.lz", ".tar.lzma", ".tar.z",
        )

        /** One compressed file each. */
        val SINGLE_EXTENSIONS = setOf("gz", "bz2", "xz", "zst", "lz4", "lz", "lzma", "z")
    }
}
