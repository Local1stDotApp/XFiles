package app.local1st.files.core.fs

import androidx.compose.runtime.Immutable

/**
 * Universal entry model. Every browsable thing (local file/dir, storage volume,
 * entry inside an archive, installed app, ...) is an [XEntry].
 *
 * Identity is [id]: a URI-like string `scheme://path`, e.g.
 *  - `file:///storage/emulated/0/DCIM`
 *  - `zip:///storage/emulated/0/a.zip!/inner/dir`   (archive file path + `!/` + inner path)
 *  - `apps://`                                       (app manager root)
 *  - `apps://com.example.app`                        (one installed app)
 *  - `saf://{locationId}`                            (a granted document tree)
 *  - `saf://{locationId}/{enc(docId)}/…`            (document ids, not names)
 */
@Immutable
data class XEntry(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = -1L,
    val mtime: Long = 0L,
    val mime: String? = null,
    val hidden: Boolean = false,
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    val kind: EntryKind = if (isDir) EntryKind.DIR else EntryKind.FILE,
    /** For dirs: number of children when cheaply known, else -1. */
    val childCountHint: Int = -1,
    /** Secondary label (volume free space, app version, ...). */
    val badge: String? = null,
    /** Absolute path of a real local file backing this entry (thumbnails, open-with), else null. */
    val localPath: String? = null,
    /** Used fraction 0..1 for volumes (usage bar), else -1. */
    val progress: Float = -1f,
    /** True for a favorite shown as a top-level shortcut root. */
    val pinned: Boolean = false,
) {
    val scheme: String get() = id.substringBefore("://")
    val path: String get() = id.substringAfter("://")
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** True when this entry can be entered like a folder (dirs, archives, app root, an app). */
    val isContainer: Boolean
        get() = isDir || kind == EntryKind.ARCHIVE || kind == EntryKind.APP
}

enum class EntryKind {
    VOLUME_INTERNAL,
    VOLUME_SD,
    VOLUME_USB,
    DIR,
    FILE,
    /** Archive file browsable as a folder (zip/7z/tar/rar/apk). */
    ARCHIVE,
    APPS_ROOT,
    APP,
    /** Container grouping an app's manifest components (the "Components" node, and each of the
     *  Activities/Services/Receivers/Providers buckets under it). */
    APP_COMPONENT_GROUP,
    /** A single manifest component (one activity/service/receiver/provider) of an app. */
    APP_COMPONENT,
    /** The superuser filesystem root ("/") browsed via `su`. */
    ROOT,
    /** A granted Storage Access Framework document tree, shown as a pane root. */
    LOCATION,
}

object XId {
    const val SCHEME_FILE = "file"
    const val SCHEME_ZIP = "zip"
    const val SCHEME_APPS = "apps"
    const val SCHEME_ROOT = "root"
    const val SCHEME_SAF = "saf"
    const val ARCHIVE_SEP = "!/"

    fun file(absolutePath: String): String = "$SCHEME_FILE://$absolutePath"

    /** Superuser path id, e.g. root:///data/local. */
    fun root(absolutePath: String): String = "$SCHEME_ROOT://$absolutePath"

    fun zip(archiveAbsolutePath: String, innerPath: String = ""): String =
        "$SCHEME_ZIP://$archiveAbsolutePath$ARCHIVE_SEP$innerPath"

    /**
     * A granted document tree. [documentIds] is the chain of provider document ids from the
     * tree root (empty) down; each id is percent-encoded because providers put `:` and `/`
     * in them.
     */
    fun saf(locationId: String, documentIds: List<String> = emptyList()): String {
        require(locationId.isNotEmpty() && '/' !in locationId) { "Invalid location id" }
        if (documentIds.isEmpty()) return "$SCHEME_SAF://$locationId"
        val path = documentIds.joinToString("/") { encodeSafSegment(it) }
        return "$SCHEME_SAF://$locationId/$path"
    }

    fun safLocationId(id: String): String {
        val rest = id.substringAfter("://")
        return rest.substringBefore('/', rest)
    }

    fun safDocumentIds(id: String): List<String> {
        val rest = id.substringAfter("://")
        val slash = rest.indexOf('/')
        if (slash < 0 || slash == rest.lastIndex) return emptyList()
        return rest.substring(slash + 1).trimEnd('/').split('/')
            .filter { it.isNotEmpty() }
            .map { decodeSafSegment(it) }
    }

    fun schemeOf(id: String): String = id.substringBefore("://")

    /** For zip ids: absolute path of the archive file on disk. */
    fun zipArchivePath(id: String): String =
        id.substringAfter("://").substringBefore(ARCHIVE_SEP)

    /** For zip ids: path inside the archive ("" for archive root), no trailing slash. */
    fun zipInnerPath(id: String): String =
        id.substringAfter("://").substringAfter(ARCHIVE_SEP, "").trimEnd('/')

    /** Child id under a parent container id (handles file/ root/ zip/ apps schemes). */
    fun child(parent: XEntry, childName: String): String = when (parent.scheme) {
        SCHEME_FILE ->
            if (parent.kind == EntryKind.ARCHIVE) zip(parent.path, childName)
            else file(joinPath(parent.path, childName))
        SCHEME_ROOT -> root(joinPath(parent.path, childName))
        SCHEME_ZIP -> {
            val inner = zipInnerPath(parent.id)
            zip(zipArchivePath(parent.id), if (inner.isEmpty()) childName else "$inner/$childName")
        }
        SCHEME_APPS -> "$SCHEME_APPS://$childName"
        SCHEME_SAF -> saf(safLocationId(parent.id), safDocumentIds(parent.id) + childName)
        else -> parent.id.trimEnd('/') + "/" + childName
    }

    /** Joins a POSIX directory path with a child name, handling the "/" root. */
    fun joinPath(dirPath: String, childName: String): String =
        if (dirPath == "/" || dirPath.isEmpty()) "/$childName"
        else dirPath.trimEnd('/') + "/" + childName

    /** Parent id, or null at a root. */
    fun parent(id: String): String? {
        when (schemeOf(id)) {
            SCHEME_FILE -> {
                val p = id.substringAfter("://")
                if (p == "/" || p.isEmpty()) return null
                val parentPath = p.trimEnd('/').substringBeforeLast('/', "")
                return if (parentPath.isEmpty()) file("/") else file(parentPath)
            }
            SCHEME_ROOT -> {
                val p = id.substringAfter("://")
                if (p == "/" || p.isEmpty()) return null
                val parentPath = p.trimEnd('/').substringBeforeLast('/', "")
                return if (parentPath.isEmpty()) root("/") else root(parentPath)
            }
            SCHEME_ZIP -> {
                val inner = zipInnerPath(id)
                return if (inner.isEmpty()) file(zipArchivePath(id))
                else zip(zipArchivePath(id), inner.substringBeforeLast('/', ""))
            }
            SCHEME_APPS -> {
                val p = id.substringAfter("://")
                if (p.isEmpty()) return null
                // Nested app sub-paths (e.g. <pkg>/@components/activity) climb one segment;
                // a bare <pkg> (or @user/@system category) sits directly under the apps root.
                return if (p.contains('/')) "$SCHEME_APPS://${p.substringBeforeLast('/')}"
                else "$SCHEME_APPS://"
            }
            SCHEME_SAF -> {
                val locationId = safLocationId(id)
                if (locationId.isEmpty()) return null
                val docs = safDocumentIds(id)
                return if (docs.isEmpty()) null else saf(locationId, docs.dropLast(1))
            }
            else -> return null
        }
    }
}

/** Percent-encode a SAF document id so it can sit in one path segment. */
internal fun encodeSafSegment(raw: String): String {
    val bytes = raw.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size)
    for (b in bytes) {
        val c = b.toInt() and 0xff
        val unreserved = c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 ||
            c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E
        if (unreserved) {
            out.append(c.toChar())
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[c shr 4])
            out.append("0123456789ABCDEF"[c and 0x0F])
        }
    }
    return out.toString()
}

internal fun decodeSafSegment(encoded: String): String {
    val bytes = ArrayList<Byte>(encoded.length)
    var i = 0
    while (i < encoded.length) {
        val ch = encoded[i]
        if (ch == '%' && i + 2 < encoded.length) {
            val hi = encoded[i + 1].digitToIntOrNull(16)
            val lo = encoded[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
                continue
            }
        }
        bytes.add(ch.code.toByte())
        i++
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}
