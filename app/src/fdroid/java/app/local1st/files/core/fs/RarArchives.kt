package app.local1st.files.core.fs

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The fdroid flavor's RAR hook for [ArchiveFileSystem]: nothing beyond libarchive, which reads
 * every RAR except solid RAR 4 and RAR 1.4. The full flavor reads those with junrar, whose UnRAR
 * licence F-Droid treats as non-free; here they fail to open like any other archive that can't
 * be read.
 */
internal object RarArchives {

    /** Never: libarchive reads every RAR this flavor can. */
    @Suppress("UNUSED_PARAMETER")
    fun readsItself(archiveFile: File): Boolean = false

    fun list(
        archiveFile: File,
        add: (rawName: String, isDir: Boolean, size: Long, mtime: Long, index: Int) -> Unit,
    ): Unit = throw unsupported(archiveFile)

    @Suppress("UNUSED_PARAMETER")
    fun openEntry(archiveFile: File, rawName: String, index: Int): InputStream = throw unsupported(archiveFile)

    fun forEachFile(archiveFile: File, visit: (rawName: String, index: Int, input: InputStream) -> Unit): Unit =
        throw unsupported(archiveFile)

    private fun unsupported(archiveFile: File) = IOException("Cannot open archive ${archiveFile.name}")
}
