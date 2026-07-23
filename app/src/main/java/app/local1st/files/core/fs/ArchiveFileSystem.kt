package app.local1st.files.core.fs

import com.github.junrar.Archive
import app.local1st.files.core.util.FileTypes
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Read-only browsing of archive files as virtual folders.
 *
 * Serves every `zip://<archive>!/<inner>` id regardless of the actual archive format:
 * zip/jar/apk (java.util.zip), 7z (commons-compress), tar and compressed tar
 * (commons-compress), rar (junrar), plus single-file gz/bz2/xz pseudo-archives.
 *
 * Each archive is parsed once into an in-memory tree of [Node]s, cached in a small
 * LRU keyed by archive path + file mtime + length, so re-listing directories and
 * stat() are cheap. All methods are blocking IO (callers run them on Dispatchers.IO).
 */
class ArchiveFileSystem : XFileSystem {

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
        val archiveFile = File(archivePath)
        if (!archiveFile.isFile) return null
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
        return when (val format = formatOf(archiveFile.name)) {
            ArchiveFormat.ZIP -> openZipEntry(archiveFile, rawName)
            ArchiveFormat.SEVEN_Z -> openSevenZEntry(archiveFile, rawName, node.size)
            ArchiveFormat.RAR -> openRarEntry(archiveFile, rawName, node.size)
            ArchiveFormat.TAR,
            ArchiveFormat.TAR_GZ,
            ArchiveFormat.TAR_BZ2,
            ArchiveFormat.TAR_XZ,
            -> openTarEntry(archiveFile, format, rawName)
            ArchiveFormat.GZ,
            ArchiveFormat.BZ2,
            ArchiveFormat.XZ,
            -> openSingleCompressed(archiveFile, format)
        }
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream = throw readOnly()

    override fun mkdir(parentDir: XEntry, name: String): XEntry = throw readOnly()

    override fun delete(entry: XEntry) {
        throw readOnly()
    }

    override fun rename(entry: XEntry, newName: String): XEntry = throw readOnly()

    override fun canWrite(entry: XEntry): Boolean = false

    // ---------------------------------------------------------------------------------------
    // Formats
    // ---------------------------------------------------------------------------------------

    private enum class ArchiveFormat { ZIP, SEVEN_Z, RAR, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ }

    private fun formatOf(fileName: String): ArchiveFormat {
        val n = fileName.lowercase()
        return when {
            n.endsWith(".tar") -> ArchiveFormat.TAR
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> ArchiveFormat.TAR_BZ2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> ArchiveFormat.TAR_XZ
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") ||
                n.endsWith(".aab") ||
                n.substringAfterLast('.', "") in FileTypes.apkBundleExtensions ->
                ArchiveFormat.ZIP
            n.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
            n.endsWith(".rar") -> ArchiveFormat.RAR
            n.endsWith(".gz") -> ArchiveFormat.GZ
            n.endsWith(".bz2") -> ArchiveFormat.BZ2
            n.endsWith(".xz") -> ArchiveFormat.XZ
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
        /** Exact entry name as stored in the archive, used to reopen the entry later. */
        var rawName: String? = null,
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
            when (format) {
                ArchiveFormat.ZIP -> parseZip(archiveFile, builder)
                ArchiveFormat.SEVEN_Z -> parseSevenZ(archiveFile, builder)
                ArchiveFormat.RAR -> parseRar(archiveFile, builder)
                ArchiveFormat.TAR,
                ArchiveFormat.TAR_GZ,
                ArchiveFormat.TAR_BZ2,
                ArchiveFormat.TAR_XZ,
                -> parseTar(archiveFile, format, builder)
                ArchiveFormat.GZ,
                ArchiveFormat.BZ2,
                ArchiveFormat.XZ,
                -> parseSingleCompressed(archiveFile, format, builder)
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

        fun add(rawName: String, isDir: Boolean, size: Long, mtime: Long) {
            val normalized = normalizeInnerPath(rawName)
            if (normalized.isEmpty()) return
            val segments = normalized.split('/')
            var current = root
            val pathSoFar = StringBuilder()
            for ((index, segment) in segments.withIndex()) {
                val isLast = index == segments.lastIndex
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
        mime = if (node.isDir) null else FileTypes.mimeOf(node.name),
        hidden = node.name.startsWith("."),
        canRead = true,
        canWrite = false,
        kind = if (node.isDir) EntryKind.DIR else EntryKind.FILE,
        childCountHint = if (node.isDir) node.children.size else -1,
        localPath = null,
    )

    // ---------------------------------------------------------------------------------------
    // Per-format parsing
    // ---------------------------------------------------------------------------------------

    private fun parseZip(archiveFile: File, builder: TreeBuilder) {
        newZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                builder.add(
                    rawName = e.name,
                    isDir = e.isDirectory,
                    size = e.size,
                    mtime = e.time.takeIf { it > 0 } ?: 0L,
                )
            }
        }
    }

    private fun parseSevenZ(archiveFile: File, builder: TreeBuilder) {
        SevenZFile.builder().setFile(archiveFile).get().use { sevenZ ->
            var e = sevenZ.nextEntry
            while (e != null) {
                builder.add(
                    rawName = sevenZEntryName(e, archiveFile),
                    isDir = e.isDirectory,
                    size = if (e.isDirectory) -1L else e.size,
                    mtime = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L,
                )
                e = sevenZ.nextEntry
            }
        }
    }

    private fun parseRar(archiveFile: File, builder: TreeBuilder) {
        Archive(archiveFile).use { rar ->
            for (header in rar.fileHeaders) {
                builder.add(
                    rawName = header.fileName,
                    isDir = header.isDirectory,
                    size = if (header.isDirectory) -1L else header.fullUnpackSize,
                    mtime = header.mTime?.time ?: 0L,
                )
            }
        }
    }

    private fun parseTar(archiveFile: File, format: ArchiveFormat, builder: TreeBuilder) {
        openTarStream(archiveFile, format).use { tarIn ->
            var e = tarIn.nextEntry
            while (e != null) {
                // Skip special entries (links, devices); browsing shows files and dirs only.
                if (e.isDirectory || e.isFile) {
                    builder.add(
                        rawName = e.name,
                        isDir = e.isDirectory,
                        size = if (e.isDirectory) -1L else e.size,
                        mtime = e.lastModifiedDate?.time ?: 0L,
                    )
                }
                e = tarIn.nextEntry
            }
        }
    }

    private fun parseSingleCompressed(archiveFile: File, format: ArchiveFormat, builder: TreeBuilder) {
        // Opening the stream validates the magic bytes so corrupt files fail at list() time.
        openSingleCompressed(archiveFile, format).close()
        val name = archiveFile.name.substringBeforeLast('.').ifEmpty { archiveFile.name }
        val sizeHint = if (format == ArchiveFormat.GZ) gzipSizeHint(archiveFile) else -1L
        builder.add(rawName = name, isDir = false, size = sizeHint, mtime = archiveFile.lastModified())
    }

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

    private fun openZipEntry(archiveFile: File, rawName: String): InputStream {
        val zip = try {
            newZipFile(archiveFile)
        } catch (e: Exception) {
            throw e.asCannotOpen(archiveFile)
        }
        try {
            val zipEntry = zip.getEntry(rawName)
            if (zipEntry == null || zipEntry.isDirectory) {
                throw IOException("Entry not found in ${archiveFile.name}: $rawName")
            }
            val base = zip.getInputStream(zipEntry)
                ?: throw IOException("Entry not found in ${archiveFile.name}: $rawName")
            return ResourceClosingInputStream(base, zip)
        } catch (e: Exception) {
            closeQuietly(zip)
            throw e.asCannotOpen(archiveFile)
        }
    }

    private fun openSevenZEntry(archiveFile: File, rawName: String, knownSize: Long): InputStream {
        // Stream the entry (SevenZFile.read serves the current entry's bytes) instead of
        // buffering it whole, so large members can be copied/extracted out of the archive.
        // Viewers apply their own preview caps.
        val sevenZ = try {
            SevenZFile.builder().setFile(archiveFile).get()
        } catch (e: Exception) {
            throw e.asCannotOpen(archiveFile)
        }
        try {
            var e = sevenZ.nextEntry
            while (e != null) {
                if (!e.isDirectory && sevenZEntryName(e, archiveFile) == rawName) {
                    return ResourceClosingInputStream(SevenZEntryInputStream(sevenZ), sevenZ)
                }
                e = sevenZ.nextEntry
            }
            throw IOException("Entry not found in ${archiveFile.name}: $rawName")
        } catch (e: Exception) {
            closeQuietly(sevenZ)
            throw e.asCannotOpen(archiveFile)
        }
    }

    /** Adapts a positioned [SevenZFile] to a pull [InputStream] over the current entry. */
    private class SevenZEntryInputStream(private val sevenZ: SevenZFile) : InputStream() {
        override fun read(): Int = sevenZ.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = sevenZ.read(b, off, len)
        override fun close() = sevenZ.close()
    }

    private fun openRarEntry(archiveFile: File, rawName: String, knownSize: Long): InputStream {
        val rar = try {
            Archive(archiveFile)
        } catch (e: Exception) {
            throw e.asCannotOpen(archiveFile)
        }
        try {
            val header = rar.fileHeaders.firstOrNull { !it.isDirectory && it.fileName == rawName }
                ?: throw IOException("Entry not found in ${archiveFile.name}: $rawName")
            // getInputStream streams via a background thread; no full-file buffering.
            return ResourceClosingInputStream(rar.getInputStream(header), rar)
        } catch (e: Exception) {
            closeQuietly(rar)
            throw e.asCannotOpen(archiveFile)
        }
    }

    private fun openTarEntry(archiveFile: File, format: ArchiveFormat, rawName: String): InputStream {
        var tarIn: TarArchiveInputStream? = null
        try {
            tarIn = openTarStream(archiveFile, format)
            var e = tarIn.nextEntry
            while (e != null) {
                if (e.isFile && e.name == rawName) {
                    // Positioned at the entry: reads yield its bytes and EOF at the entry end.
                    return tarIn
                }
                e = tarIn.nextEntry
            }
            throw IOException("Entry not found in ${archiveFile.name}: $rawName")
        } catch (e: Exception) {
            tarIn?.let { closeQuietly(it) }
            throw e.asCannotOpen(archiveFile)
        }
    }

    private fun openTarStream(archiveFile: File, format: ArchiveFormat): TarArchiveInputStream {
        val raw = BufferedInputStream(FileInputStream(archiveFile), STREAM_BUFFER_SIZE)
        try {
            val plain = when (format) {
                ArchiveFormat.TAR -> raw
                ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(raw, true)
                ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw, true)
                ArchiveFormat.TAR_XZ -> XZCompressorInputStream(raw, true)
                else -> throw IllegalArgumentException("Not a tar format: $format")
            }
            return TarArchiveInputStream(plain)
        } catch (e: Exception) {
            closeQuietly(raw)
            throw e.asCannotOpen(archiveFile)
        }
    }

    private fun openSingleCompressed(archiveFile: File, format: ArchiveFormat): InputStream {
        val raw = BufferedInputStream(FileInputStream(archiveFile), STREAM_BUFFER_SIZE)
        try {
            return when (format) {
                ArchiveFormat.GZ -> GzipCompressorInputStream(raw, true)
                ArchiveFormat.BZ2 -> BZip2CompressorInputStream(raw, true)
                ArchiveFormat.XZ -> XZCompressorInputStream(raw, true)
                else -> throw IllegalArgumentException("Not a single-file format: $format")
            }
        } catch (e: Exception) {
            closeQuietly(raw)
            throw e.asCannotOpen(archiveFile)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun resolveTarget(dir: XEntry): Pair<String, String> = when (XId.schemeOf(dir.id)) {
        // An ARCHIVE entry on the file scheme, routed here via FsRegistry.resolveScheme.
        XId.SCHEME_FILE -> (dir.localPath ?: dir.path) to ""
        XId.SCHEME_ZIP -> XId.zipArchivePath(dir.id) to XId.zipInnerPath(dir.id)
        else -> throw IOException("Not an archive: ${dir.name}")
    }

    private fun requireArchiveFile(path: String): File {
        val file = File(path)
        if (!file.isFile) throw IOException("Archive not found: ${file.name}")
        return file
    }

    private fun newZipFile(archiveFile: File): ZipFile = try {
        ZipFile(archiveFile)
    } catch (_: IllegalArgumentException) {
        // Legacy zips with non-UTF-8 entry names; latin-1 accepts any byte sequence.
        ZipFile(archiveFile, Charsets.ISO_8859_1)
    }

    /** 7z can store a single unnamed entry; fall back to the archive's own base name. */
    private fun sevenZEntryName(entry: SevenZArchiveEntry, archiveFile: File): String =
        entry.name ?: archiveFile.name.substringBeforeLast('.').ifEmpty { archiveFile.name }

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

    /** Forwards reads to [base]; closing also closes [alsoClose] (e.g. the backing ZipFile). */
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
        const val STREAM_BUFFER_SIZE = 64 * 1024

        /** Normalizes separators and strips empty/`.`/`..` segments. */
        fun normalizeInnerPath(rawName: String): String =
            rawName.replace('\\', '/')
                .split('/')
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")
    }
}
