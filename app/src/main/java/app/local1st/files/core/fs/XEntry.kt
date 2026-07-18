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
}

object XId {
    const val SCHEME_FILE = "file"
    const val SCHEME_ZIP = "zip"
    const val SCHEME_APPS = "apps"
    const val SCHEME_ROOT = "root"
    const val ARCHIVE_SEP = "!/"

    fun file(absolutePath: String): String = "$SCHEME_FILE://$absolutePath"

    /** Superuser path id, e.g. root:///data/local. */
    fun root(absolutePath: String): String = "$SCHEME_ROOT://$absolutePath"

    fun zip(archiveAbsolutePath: String, innerPath: String = ""): String =
        "$SCHEME_ZIP://$archiveAbsolutePath$ARCHIVE_SEP$innerPath"

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
            else -> return null
        }
    }
}
