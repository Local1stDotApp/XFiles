package app.local1st.files.core.fs

import app.local1st.files.core.util.FileTypes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Local disk filesystem for `file://` ids, backed by [java.io.File].
 * All methods are blocking and expected to run on Dispatchers.IO.
 */
class LocalFileSystem : XFileSystem {

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
            throw IOException("Cannot create folder ${parent.absolutePath}")
        }
        try {
            return FileOutputStream(File(parent, name))
        } catch (e: IOException) {
            throw IOException("Cannot write $name in ${parentDir.name}", e)
        }
    }

    override fun mkdir(parentDir: XEntry, name: String): XEntry {
        requireSafeName(name)
        val dir = File(parentDir.path, name)
        // Idempotent: copy ops re-create destination subfolders that may already exist.
        if (dir.isDirectory) return toEntry(dir, readAttrs(dir))
        if (!dir.mkdirs()) {
            throw IOException("Cannot create folder $name in ${parentDir.name}")
        }
        return toEntry(dir, readAttrs(dir))
    }

    override fun delete(entry: XEntry) {
        deleteRecursively(File(entry.path))
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
            throw IOException("Cannot rename ${entry.name} to $newName")
        }
        return toEntry(target, readAttrs(target))
    }

    override fun canWrite(entry: XEntry): Boolean = File(entry.path).canWrite()

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
}
