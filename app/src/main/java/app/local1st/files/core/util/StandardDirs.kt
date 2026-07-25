package app.local1st.files.core.util

import app.local1st.files.core.fs.XId

/** A well-known directory of a storage volume, recognised so it can carry its own icon. */
enum class StandardDir {
    DCIM,
    CAMERA,
    SCREENSHOTS,
    PICTURES,
    MOVIES,
    MUSIC,
    PODCASTS,
    AUDIOBOOKS,
    RECORDINGS,
    RINGTONES,
    ALARMS,
    NOTIFICATIONS,
    DOWNLOAD,
    DOCUMENTS,
    BLUETOOTH,
    ANDROID,
    ANDROID_DATA,
    ANDROID_OBB,
    ANDROID_MEDIA,
}

/**
 * Recognises the directories the platform itself puts on every volume — the
 * `Environment.DIRECTORY_*` set plus the `Android/` sandbox — from an entry id.
 */
object StandardDirs {

    /**
     * Volume roots a path can sit under: emulated storage per user id (`/data/media/<user>` is
     * the same tree as `su` sees it), the legacy `/sdcard` symlinks, and removable volumes keyed
     * by uuid — `/storage/1A2B-3C4D` for the FUSE view, `/mnt/media_rw/...` for the raw mount.
     */
    private val volumeRoot = Regex(
        "^/(?:storage/emulated/\\d+" +
            "|data/media/\\d+" +
            "|storage/self/primary" +
            "|storage/(?!emulated/|self/)[^/]+" +
            "|mnt/media_rw/[^/]+" +
            "|mnt/sdcard" +
            "|sdcard)/",
    )

    /**
     * Path relative to the volume root → the directory it names. The names on disk are fixed
     * (never localized), but their casing varies across OEMs, so lookup is lowercased.
     */
    private val byRelativePath = mapOf(
        "dcim" to StandardDir.DCIM,
        "dcim/camera" to StandardDir.CAMERA,
        "dcim/screenshots" to StandardDir.SCREENSHOTS,
        "pictures" to StandardDir.PICTURES,
        "pictures/screenshots" to StandardDir.SCREENSHOTS,
        "screenshots" to StandardDir.SCREENSHOTS,
        "movies" to StandardDir.MOVIES,
        "music" to StandardDir.MUSIC,
        "podcasts" to StandardDir.PODCASTS,
        "audiobooks" to StandardDir.AUDIOBOOKS,
        "recordings" to StandardDir.RECORDINGS,
        "ringtones" to StandardDir.RINGTONES,
        "alarms" to StandardDir.ALARMS,
        "notifications" to StandardDir.NOTIFICATIONS,
        "download" to StandardDir.DOWNLOAD,
        "downloads" to StandardDir.DOWNLOAD,
        "documents" to StandardDir.DOCUMENTS,
        "bluetooth" to StandardDir.BLUETOOTH,
        "android" to StandardDir.ANDROID,
        "android/data" to StandardDir.ANDROID_DATA,
        "android/obb" to StandardDir.ANDROID_OBB,
        "android/media" to StandardDir.ANDROID_MEDIA,
    )

    /** Directories holding one subdirectory per package, relative to a volume root. */
    private val sandboxParents = setOf("android/data", "android/media", "android/obb")

    /**
     * The same per-package split on the data partition, reachable only through `su`. `/data/data`
     * is the classic path; `/data/user/<user>` is what it actually is on a multi-user device, and
     * `/data/user_de` is the device-encrypted half that exists before the first unlock.
     */
    private val dataPartitionSandbox = Regex("^/data/(?:data|user/\\d+|user_de/\\d+)/$")

    /** Conservative package-name shape: at least one dot, nothing a directory could hold but a package could not. */
    private val packageName = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")

    /**
     * The standard directory [id] points at, or null for anything else. Only ids naming a real
     * spot on disk qualify (`file://`, and `root://` for the same tree through `su`) — a folder
     * called "Music" inside a zip is not the system's Music folder, and neither is one nested
     * under some app's private data.
     */
    fun of(id: String): StandardDir? {
        val path = onDiskPath(id) ?: return null
        return byRelativePath[relativeToVolume(path)?.lowercase() ?: return null]
    }

    /**
     * Package whose private directory [id] *is* — `Android/{data,media,obb}/<pkg>` on a volume,
     * or `<pkg>` on the data partition. Null for anything else, the contents of those directories
     * included: `Android/data/com.foo/files` belongs to com.foo too, but com.foo's icon marks
     * where its sandbox starts, and repeating it downwards would stop meaning that.
     *
     * The package is only known to be well-formed, not installed — these directories routinely
     * outlive the app that made them, so the caller has to survive having no icon to show.
     */
    fun ownerPackageOf(id: String): String? {
        val path = onDiskPath(id) ?: return null
        val name = path.substringAfterLast('/')
        if (!packageName.matches(name)) return null
        val parent = path.substringBeforeLast('/', "")
        val isSandbox = relativeToVolume(parent)?.lowercase() in sandboxParents ||
            dataPartitionSandbox.matches("$parent/")
        return if (isSandbox) name else null
    }

    /** Absolute path behind [id], for the schemes that name a real one; null for the rest. */
    private fun onDiskPath(id: String): String? {
        val scheme = XId.schemeOf(id)
        if (scheme != XId.SCHEME_FILE && scheme != XId.SCHEME_ROOT) return null
        return id.substringAfter("://").trimEnd('/')
    }

    /** [path] relative to the volume root containing it, or null when it is not inside one. */
    private fun relativeToVolume(path: String): String? {
        val root = volumeRoot.find(path) ?: return null
        return path.substring(root.value.length)
    }
}
