package app.local1st.files.core.fs

import app.local1st.files.core.util.FileTypes
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The Recycle Bin pane root (`trash://`). Its children are the real `file://` items
 * inside each volume's bin, so opening, copying, and thumbnails stay on [LocalFileSystem].
 */
class TrashFileSystem(
    private val trash: TrashStore,
    private val local: XFileSystem,
    /** Same string as the pane root, so `stat(trash://)` is not stuck on English. */
    private val rootName: String = "Recycle Bin",
    /** Volume label in, localized orphan badge out. */
    private val orphanBadge: (String) -> String = { volume -> "$volume · original location unknown" },
) : XFileSystem {

    override val scheme: String = XId.SCHEME_TRASH

    override fun list(dir: XEntry): List<XEntry> {
        if (dir.id != XId.TRASH_ROOT) throw IOException("Cannot read ${dir.name}")
        return trash.list().mapNotNull(::toEntry)
    }

    override fun stat(id: String): XEntry? = if (id == XId.TRASH_ROOT) rootEntry(rootName) else null

    override fun openIn(entry: XEntry): InputStream = unsupported()

    override fun openOut(parentDir: XEntry, name: String): OutputStream = unsupported()

    override fun createFile(parentDir: XEntry, name: String): XEntry = unsupported()

    override fun mkdir(parentDir: XEntry, name: String): XEntry = unsupported()

    override fun delete(entry: XEntry) = unsupported()

    override fun rename(entry: XEntry, newName: String): XEntry = unsupported()

    override fun canWrite(entry: XEntry): Boolean = false

    private fun toEntry(record: TrashRecord): XEntry? {
        val stat = local.stat(XId.file(record.storedPath))
        if (stat == null) {
            // File cannot see a SAF-only payload or a broken symlink. The record is
            // still the only copy, so Restore and Empty have to be able to find it.
            return XEntry(
                id = XId.file(record.storedPath),
                name = record.name,
                isDir = record.storedIsDir,
                mime = if (record.storedIsDir) null else runCatching { FileTypes.mimeOf(record.name) }.getOrNull(),
                hidden = false,
                badge = if (record.orphan) orphanBadge(record.volumeLabel) else record.badge,
                mtime = record.deletedAt,
                kind = when {
                    record.storedIsDir -> EntryKind.DIR
                    FileTypes.isBrowsableArchive(record.name) -> EntryKind.ARCHIVE
                    else -> EntryKind.FILE
                },
                localPath = null,
            )
        }
        return stat.copy(
            name = record.name,
            hidden = false,
            badge = if (record.orphan) orphanBadge(record.volumeLabel) else record.badge,
        )
    }

    private fun unsupported(): Nothing = throw IOException("Not supported")

    companion object {
        fun rootEntry(name: String = "Recycle Bin") = XEntry(
            id = XId.TRASH_ROOT,
            name = name,
            isDir = true,
            kind = EntryKind.RECYCLE_BIN,
            canWrite = false,
        )
    }
}
