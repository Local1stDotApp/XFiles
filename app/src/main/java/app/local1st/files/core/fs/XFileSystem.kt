package app.local1st.files.core.fs

import java.io.InputStream
import java.io.OutputStream

/**
 * A mounted filesystem implementation, keyed by id scheme.
 * All methods are blocking-IO and must be called on Dispatchers.IO
 * (implementations may assume that; callers use [kotlinx.coroutines.withContext]).
 */
interface XFileSystem {
    val scheme: String

    /** List children of a container entry (dir, archive, archive-dir, apps root). */
    @Throws(java.io.IOException::class)
    fun list(dir: XEntry): List<XEntry>

    /** Resolve an id back to an entry, or null if it doesn't exist. */
    fun stat(id: String): XEntry?

    @Throws(java.io.IOException::class)
    fun openIn(entry: XEntry): InputStream

    /** Create (or truncate) a file named [name] inside [parentDir]. */
    @Throws(java.io.IOException::class)
    fun openOut(parentDir: XEntry, name: String): OutputStream

    @Throws(java.io.IOException::class)
    fun mkdir(parentDir: XEntry, name: String): XEntry

    /** Delete a file, or a directory recursively. */
    @Throws(java.io.IOException::class)
    fun delete(entry: XEntry)

    @Throws(java.io.IOException::class)
    fun rename(entry: XEntry, newName: String): XEntry

    /** Whether write ops (openOut/mkdir/delete/rename) can work for this entry. */
    fun canWrite(entry: XEntry): Boolean
}

/** Registry mapping id schemes to filesystems. Populated at app start (see Graph). */
class FsRegistry {
    private val systems = LinkedHashMap<String, XFileSystem>()

    fun register(fs: XFileSystem) {
        systems[fs.scheme] = fs
    }

    fun forScheme(scheme: String): XFileSystem =
        systems[scheme] ?: error("No filesystem registered for scheme '$scheme'")

    fun forId(id: String): XFileSystem = forScheme(XId.schemeOf(id))

    fun forEntry(entry: XEntry): XFileSystem = forScheme(resolveScheme(entry))

    /**
     * Scheme used to *browse into* an entry: an ARCHIVE file entry on the file scheme
     * is listed by the zip filesystem.
     */
    fun resolveScheme(entry: XEntry): String =
        if (entry.kind == EntryKind.ARCHIVE && entry.scheme == XId.SCHEME_FILE) XId.SCHEME_ZIP
        else entry.scheme
}
