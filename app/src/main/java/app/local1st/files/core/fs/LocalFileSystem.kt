package app.local1st.files.core.fs

import android.os.Build
import app.local1st.files.core.util.FileTypes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Local disk filesystem for `file://` ids, backed by [java.io.File].
 * All methods are blocking and expected to run on Dispatchers.IO.
 */
class LocalFileSystem(
    private val legacySaf: LegacySafAccess? = null,
) : XFileSystem {

    override val scheme: String = XId.SCHEME_FILE

    override fun list(dir: XEntry): List<XEntry> {
        val file = File(dir.path)
        val children = file.listFiles()
            ?: throw IOException(
                if (file.exists()) "Cannot read ${dir.name}"
                else "Folder not found: ${dir.name}"
            )
        return children.map { toEntry(it, readAttrs(it)) }
    }

    override fun stat(id: String): XEntry? {
        val file = File(id.substringAfter("://"))
        val attrs = readAttrs(file) ?: return null
        return toEntry(file, attrs)
    }

    override fun openIn(entry: XEntry): InputStream {
        val file = File(entry.path)
        if (!file.isFile) throw IOException("Cannot open ${entry.name}")
        return FileInputStream(file)
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream {
        requireSafeName(name)
        val parent = File(parentDir.path)
        if (!parent.isDirectory && !parent.mkdirs()) {
            val directError = IOException("Cannot create folder ${parent.absolutePath}")
            return withSafWrite(parent, directError) { saf, volume, tree ->
                val parentDocument = saf.resolve(volume, tree, parent) ?: throw directError
                saf.openOutput(
                    tree,
                    parentDocument,
                    name,
                    FileTypes.mimeOf(name) ?: "application/octet-stream",
                )
            }
        }
        try {
            return FileOutputStream(File(parent, name))
        } catch (e: IOException) {
            val directError = IOException("Cannot write $name in ${parentDir.name}", e)
            return withSafWrite(File(parent, name), directError) { saf, volume, tree ->
                val parentDocument = saf.resolve(volume, tree, parent) ?: throw directError
                saf.openOutput(
                    tree,
                    parentDocument,
                    name,
                    FileTypes.mimeOf(name) ?: "application/octet-stream",
                )
            }
        }
    }

    override fun mkdir(parentDir: XEntry, name: String): XEntry {
        requireSafeName(name)
        val dir = File(parentDir.path, name)
        // Idempotent: copy ops re-create destination subfolders that may already exist.
        if (dir.isDirectory) return toEntry(dir, readAttrs(dir))
        if (!dir.mkdirs()) {
            val directError = IOException("Cannot create folder $name in ${parentDir.name}")
            return withSafWrite(dir, directError) { saf, volume, tree ->
                val parent = saf.resolve(volume, tree, File(parentDir.path)) ?: throw directError
                val existing = saf.child(tree, parent, name)
                val document = when {
                    existing == null -> saf.createDirectory(tree, parent, name)
                    existing.isDirectory -> existing
                    else -> throw directError
                }
                toEntry(dir, document)
            }
        }
        return toEntry(dir, readAttrs(dir))
    }

    override fun delete(entry: XEntry) {
        val file = File(entry.path)
        try {
            deleteRecursively(file)
        } catch (e: IOException) {
            withSafWrite(file, e) { saf, volume, tree ->
                val document = saf.resolve(volume, tree, file)
                if (document != null) saf.delete(document)
                else if (file.exists()) throw e
            }
        }
    }

    override fun rename(entry: XEntry, newName: String): XEntry {
        requireSafeName(newName)
        val file = File(entry.path)
        val parent = file.parentFile
            ?: throw IOException("Cannot rename ${entry.name}")
        val target = File(parent, newName)
        // A case-only rename ("photo.jpg" -> "Photo.jpg") points at the same file on
        // case-insensitive storage; allow it instead of tripping the exists() guard.
        val caseOnly = target.absolutePath.equals(file.absolutePath, ignoreCase = true)
        if (!caseOnly && target.exists()) {
            throw IOException("$newName already exists")
        }
        if (!file.renameTo(target)) {
            val directError = IOException("Cannot rename ${entry.name} to $newName")
            return withSafWrite(file, directError) { saf, volume, tree ->
                val parentDocument = saf.resolve(volume, tree, parent) ?: throw directError
                val document = saf.resolve(volume, tree, file) ?: throw directError
                val renamed = saf.rename(tree, parentDocument, document, newName)
                toEntry(target, renamed)
            }
        }
        return toEntry(target, readAttrs(target))
    }

    override fun canWrite(entry: XEntry): Boolean = File(entry.path).canWrite()

    /**
     * Saves an edited local file with the existing atomic File path when that works.
     * Only its failed API 26-29 secondary-volume case falls through to SAF.
     */
    fun replaceContents(entry: XEntry, bytes: ByteArray) {
        val target = File(entry.localPath ?: entry.path)
        val tmp = File(target.parentFile, ".${entry.name}.xfiles-tmp")
        try {
            tmp.outputStream().use { it.write(bytes) }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return
        } catch (e: Throwable) {
            tmp.delete()
            // Preserve the old File-only behavior and exception on API 30+ verbatim.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) throw e
            val directError = e as? IOException ?: IOException("Cannot save ${entry.name}", e)
            withSafWrite(target, directError) { saf, volume, tree ->
                val parent = target.parentFile?.let { saf.resolve(volume, tree, it) }
                    ?: throw directError
                saf.openOutput(
                    tree,
                    parent,
                    entry.name,
                    entry.mime ?: FileTypes.mimeOf(entry.name) ?: "application/octet-stream",
                ).use { it.write(bytes) }
            }
        }
    }

    /** Parallel ZipTurbo writes directly to File; secondary volumes use XFileSystem streams. */
    fun supportsDirectBulkWrites(entry: XEntry): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
            legacySaf?.secondaryVolumeFor(File(entry.path)) == null

    /** Reject names that would escape the parent directory (path traversal / injection). */
    private fun requireSafeName(name: String) {
        if (name.isEmpty() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\')
        ) {
            throw IOException("Invalid name: $name")
        }
    }

    private fun deleteRecursively(file: File) {
        // Never descend through symlinks; delete only the link itself.
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        if (!file.delete() && file.exists()) {
            throw IOException("Cannot delete ${file.absolutePath}")
        }
    }

    /**
     * All of an entry's metadata from ONE stat. The old per-field java.io.File calls
     * (isDirectory/length/lastModified/canRead/canWrite) were five separate syscalls,
     * each a FUSE round trip on /sdcard — big directories paid it thousands of times.
     * Access checks are deferred to the actual operation, which reports its own error.
     */
    private fun readAttrs(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    } catch (e: IOException) {
        null // vanished mid-listing or broken symlink
    }

    private fun toEntry(file: File, attrs: BasicFileAttributes?): XEntry {
        val abs = file.absolutePath
        val name = file.name
        val isDir = attrs?.isDirectory == true
        return XEntry(
            id = XId.file(abs),
            name = name,
            isDir = isDir,
            size = if (isDir || attrs == null) -1L else attrs.size(),
            mtime = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
            mime = if (isDir) null else FileTypes.mimeOf(name),
            hidden = name.startsWith("."),
            kind = when {
                isDir -> EntryKind.DIR
                FileTypes.isBrowsableArchive(name) -> EntryKind.ARCHIVE
                else -> EntryKind.FILE
            },
            childCountHint = -1,
            localPath = abs,
        )
    }

    private fun toEntry(file: File, document: SafDocument): XEntry {
        val name = file.name
        val isDir = document.isDirectory
        return XEntry(
            id = XId.file(file.absolutePath),
            name = name,
            isDir = isDir,
            size = if (isDir) -1L else document.size,
            mtime = document.lastModified,
            mime = if (isDir) null else document.mimeType,
            hidden = name.startsWith("."),
            kind = when {
                isDir -> EntryKind.DIR
                FileTypes.isBrowsableArchive(name) -> EntryKind.ARCHIVE
                else -> EntryKind.FILE
            },
            childCountHint = -1,
            localPath = file.absolutePath,
        )
    }

    /**
     * API 30+ never enters this branch. On API 26-29, SAF is considered only after the
     * existing direct File write actually failed and only when the target is on a secondary
     * volume; primary storage and Android/data retain their direct behavior.
     */
    private fun <T> withSafWrite(
        target: File,
        directError: IOException,
        write: (LegacySafAccess, SafVolume, android.net.Uri) -> T,
    ): T {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) throw directError
        val saf = legacySaf ?: throw directError
        val volume = saf.secondaryVolumeFor(target) ?: throw directError
        val tree = saf.validatedTreeOrRequest(volume) ?: throw directError
        return try {
            write(saf, volume, tree)
        } catch (e: IOException) {
            throw IOException(directError.message, e)
        } catch (e: RuntimeException) {
            throw IOException(directError.message, e)
        }
    }
}
