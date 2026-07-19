package app.local1st.files.core.fs

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.util.AppComponent
import app.local1st.files.core.util.AppComponents
import app.local1st.files.core.util.ComponentType
import app.local1st.files.core.util.FileTypes
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Read-only pseudo-filesystem exposing installed apps (X-plore's "App manager").
 *
 * - `apps://` is the root container listing one [EntryKind.APP] entry per installed package.
 * - `apps://<packageName>` identifies a single app. Copying it out produces an installable
 *   package — a single `.apk`, or every split bundled into one `.apks` when the app is split
 *   (see the copy engine). [list] expands it into all of the app's files — APK splits, the
 *   private data dir (root), and its Android/{data,obb,media} dirs; copying an individual split
 *   from there is an ordinary file copy. [openIn] streams the base APK for direct opens.
 * - System apps are marked [XEntry.hidden] so the default hidden-entries filter shows
 *   user-installed apps only; enabling "show hidden" reveals system apps too.
 */
class AppsFileSystem(private val context: Context) : XFileSystem {

    override val scheme: String = XId.SCHEME_APPS

    override fun list(dir: XEntry): List<XEntry> {
        if (dir.scheme != scheme) {
            throw IOException("'${dir.name}' is not a listable app container")
        }
        // apps://                            -> the Installed / System categories
        // apps://@user                       -> user-installed apps
        // apps://@system                     -> system apps
        // apps://<pkg>                       -> that app's files (+ a Components node)
        // apps://<pkg>/@components           -> the Activities/Services/... buckets
        // apps://<pkg>/@components/<type>     -> that bucket's components
        return when (dir.path) {
            "" -> listCategories()
            PATH_USER -> listApps(system = false)
            PATH_SYSTEM -> listApps(system = true)
            else -> listAppPath(dir.path)
        }
    }

    /** Routes an `apps://<pkg>[/@components[/<type>]]` path to the right lister. */
    private fun listAppPath(path: String): List<XEntry> {
        val pkg = path.substringBefore('/')
        val sub = path.substringAfter('/', "")
        return when {
            sub.isEmpty() -> listAppFiles(pkg)
            sub == AppComponents.COMPONENTS_SEGMENT -> listComponentGroups(pkg)
            sub.startsWith(AppComponents.COMPONENTS_SEGMENT + "/") ->
                listComponents(pkg, sub.substringAfter('/'))
            else -> listAppFiles(pkg)
        }
    }

    /** The two top-level buckets shown under "App manager", each badged with its app count. */
    private fun listCategories(): List<XEntry> {
        val pm = context.packageManager
        var user = 0
        var system = 0
        installedPackages(pm).forEach { pkg ->
            val app = pkg.applicationInfo ?: return@forEach
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) system++ else user++
        }
        return listOf(
            categoryEntry(CAT_USER, "Installed", "$user apps"),
            categoryEntry(CAT_SYSTEM, "System", "$system apps"),
        )
    }

    private fun listApps(system: Boolean): List<XEntry> {
        val pm = context.packageManager
        // One binder pass: getInstalledPackages returns applicationInfo + versionName +
        // lastUpdateTime together, avoiding a per-app getPackageInfo round trip.
        return installedPackages(pm)
            .mapNotNull { pkg ->
                val app = pkg.applicationInfo ?: return@mapNotNull null
                val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                if (isSystem != system) return@mapNotNull null
                toEntry(pm, pkg)
            }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { e: XEntry -> e.name }
                    .thenBy { it.path },
            )
    }

    /**
     * All files belonging to [packageName], grouped as one convenient place:
     *  - the base APK + any split APKs (as browsable archives — tap to open as a zip),
     *  - the private data dir `/data/data/<pkg>` (only with root, since it is otherwise
     *    unreadable),
     *  - the external `Android/{data,obb,media}/<pkg>` dirs (direct when readable, else via
     *    root when available — Android 11+ hides other apps' data/obb from the File API).
     *
     * Each child carries a real `file://`/`root://` id, so expanding or copying it routes to
     * the right filesystem automatically.
     */
    private fun listAppFiles(packageName: String): List<XEntry> {
        val pm = context.packageManager
        val app = try {
            packageInfo(pm, packageName).applicationInfo
        } catch (_: PackageManager.NameNotFoundException) {
            throw IOException("App not installed: $packageName")
        } ?: throw IOException("No info for $packageName")

        val out = ArrayList<XEntry>()

        // APK(s): base + splits, de-duplicated. .apk is a browsable archive, so these open as zip.
        val apkPaths = LinkedHashSet<String>()
        app.sourceDir?.let(apkPaths::add)
        app.publicSourceDir?.let(apkPaths::add)
        app.splitSourceDirs?.forEach { it?.let(apkPaths::add) }
        app.splitPublicSourceDirs?.forEach { it?.let(apkPaths::add) }
        apkPaths.mapNotNull { File(it).takeIf(File::isFile) }
            .sortedBy { it.name }
            .forEach { out += apkEntry(it) }

        // Private internal data. Needs real root, not merely a privileged transport:
        // /data/data is SELinux-denied to the adb shell domain (media_userdir_file /
        // *_app_data_file are not in shell's allow set), so a Shizuku-backed transport
        // would list an entry that can never open.
        if (PrivilegedAccess.enabled && PrivilegedAccess.caps.appPrivateData) {
            out += rootDirEntry("/data/data/$packageName", "Data (internal)")
        }

        // External per-app dirs. Prefer a direct file:// entry when we can read it;
        // otherwise fall back to root (which sees the scoped-storage-hidden dirs).
        val external = Environment.getExternalStorageDirectory()
        if (external != null) {
            addExternalDir(out, File(external, "Android/data/$packageName"), "Android/data")
            addExternalDir(out, File(external, "Android/obb/$packageName"), "Android/obb")
            addExternalDir(out, File(external, "Android/media/$packageName"), "Android/media")
        }

        // Manifest components (activities/services/receivers/providers), gathered under one node
        // so the common APK/data rows above stay uncluttered. Omitted when the app declares none.
        val componentTotal = AppComponents.counts(context, packageName).values.sum()
        if (componentTotal > 0) out += componentsRootEntry(packageName, componentTotal)

        if (out.isEmpty()) throw IOException("No accessible files for $packageName")
        return out
    }

    /** The "Components" node under an app: expands into the four component buckets. */
    private fun componentsRootEntry(packageName: String, total: Int): XEntry = XEntry(
        id = "$scheme://$packageName/${AppComponents.COMPONENTS_SEGMENT}",
        name = "Components",
        isDir = true,
        canWrite = false,
        kind = EntryKind.APP_COMPONENT_GROUP,
        childCountHint = total,
        badge = "$total components",
    )

    /** The Activities/Services/Receivers/Providers buckets for an app (empty buckets omitted). */
    private fun listComponentGroups(packageName: String): List<XEntry> {
        val counts = AppComponents.counts(context, packageName)
        return ComponentType.entries.mapNotNull { type ->
            val n = counts[type] ?: 0
            if (n == 0) null else componentGroupEntry(packageName, type, n)
        }
    }

    private fun componentGroupEntry(packageName: String, type: ComponentType, count: Int): XEntry =
        XEntry(
            id = "$scheme://$packageName/${AppComponents.COMPONENTS_SEGMENT}/${type.slug}",
            name = type.label,
            isDir = true,
            canWrite = false,
            kind = EntryKind.APP_COMPONENT_GROUP,
            childCountHint = count,
            badge = "$count",
        )

    /** The individual components of one bucket. */
    private fun listComponents(packageName: String, slug: String): List<XEntry> {
        val type = ComponentType.fromSlug(slug)
            ?: throw IOException("Unknown component type: $slug")
        return AppComponents.list(context, packageName, type)
            .map { componentLeafEntry(packageName, it) }
    }

    private fun componentLeafEntry(packageName: String, c: AppComponent): XEntry {
        val state = buildList {
            add(if (c.exported) "exported" else "not exported")
            if (!c.enabled) add("disabled")
        }.joinToString(" · ")
        return XEntry(
            // The fully-qualified class name is the last id segment; the row shows the short name.
            id = "$scheme://$packageName/${AppComponents.COMPONENTS_SEGMENT}/${c.type.slug}/${c.className}",
            name = c.className.substringAfterLast('.'),
            isDir = false,
            canRead = true,
            canWrite = false,
            kind = EntryKind.APP_COMPONENT,
            badge = state,
        )
    }

    private fun addExternalDir(out: MutableList<XEntry>, dir: File, label: String) {
        when {
            dir.isDirectory && dir.canRead() -> out += XEntry(
                id = XId.file(dir.absolutePath),
                name = label,
                isDir = true,
                mtime = dir.lastModified(),
                kind = EntryKind.DIR,
                canRead = true,
                canWrite = dir.canWrite(),
                badge = dir.absolutePath,
                localPath = dir.absolutePath,
            )
            // Scoped storage hides other apps' data/obb from File I/O; root can still reach them.
            PrivilegedAccess.usable() -> out += rootDirEntry(dir.absolutePath, label)
            // else: not accessible without root — omit rather than show an un-openable stub.
        }
    }

    private fun apkEntry(apk: File): XEntry = XEntry(
        id = XId.file(apk.absolutePath),
        name = apk.name,
        isDir = false,
        size = apk.length(),
        mtime = apk.lastModified(),
        mime = APK_MIME,
        canRead = true,
        canWrite = false,
        // ARCHIVE so it expands as a zip in place (X-plore's "open as zip") and stays copyable.
        kind = if (FileTypes.isBrowsableArchive(apk.name)) EntryKind.ARCHIVE else EntryKind.FILE,
        localPath = apk.absolutePath,
    )

    private fun rootDirEntry(path: String, label: String): XEntry = XEntry(
        id = XId.root(path),
        name = label,
        isDir = true,
        kind = EntryKind.DIR,
        canRead = true,
        canWrite = !PrivilegedAccess.readOnly,
        badge = "root · $path",
        localPath = null,
    )

    override fun stat(id: String): XEntry? {
        if (XId.schemeOf(id) != scheme) return null
        val path = id.substringAfter("://")
        when (path) {
            "" -> return rootEntry()
            PATH_USER -> return categoryEntry(CAT_USER, "Installed", null)
            PATH_SYSTEM -> return categoryEntry(CAT_SYSTEM, "System", null)
        }
        val pm = context.packageManager
        val pkg = try {
            packageInfo(pm, path)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return toEntry(pm, pkg)
    }

    override fun openIn(entry: XEntry): InputStream {
        if (entry.kind != EntryKind.APP) {
            throw IOException("'${entry.name}' is not an app and cannot be opened")
        }
        val apkPath = entry.localPath
            ?: stat(entry.id)?.localPath
            ?: throw IOException("App '${entry.name}' is not installed")
        val apk = File(apkPath)
        if (!apk.exists()) {
            throw IOException("APK of '${entry.name}' not found at $apkPath")
        }
        return FileInputStream(apk)
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream =
        throw IOException("Not supported")

    override fun mkdir(parentDir: XEntry, name: String): XEntry =
        throw IOException("Not supported")

    override fun delete(entry: XEntry) {
        throw IOException("Not supported")
    }

    override fun rename(entry: XEntry, newName: String): XEntry =
        throw IOException("Not supported")

    override fun canWrite(entry: XEntry): Boolean = false

    /** Builds an entry from a full [PackageInfo] (list path — no extra binder call). */
    private fun toEntry(pm: PackageManager, pkg: PackageInfo): XEntry? {
        val app = pkg.applicationInfo ?: return null
        return toEntry(pm, app, pkg.versionName, pkg.lastUpdateTime)
    }

    /** Builds an entry from an [ApplicationInfo] plus its version/update metadata (stat path). */
    private fun toEntry(
        pm: PackageManager,
        app: ApplicationInfo,
        versionName: String?,
        lastUpdateTime: Long,
    ): XEntry? {
        val sourceDir = app.sourceDir ?: return null
        return XEntry(
            id = "$scheme://${app.packageName}",
            name = app.loadLabel(pm).toString(),
            isDir = false,
            size = File(sourceDir).length(),
            mtime = lastUpdateTime,
            mime = APK_MIME,
            // System vs user is now expressed by the category the app is listed under.
            hidden = false,
            canRead = true,
            canWrite = false,
            kind = EntryKind.APP,
            badge = "${versionName ?: "?"} · ${app.packageName}",
            localPath = sourceDir,
        )
    }

    private fun categoryEntry(id: String, name: String, badge: String?): XEntry = XEntry(
        id = id,
        name = name,
        isDir = true,
        canWrite = false,
        kind = EntryKind.APPS_ROOT,
        badge = badge,
    )

    private fun installedPackages(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

    @Throws(PackageManager.NameNotFoundException::class)
    private fun packageInfo(pm: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }

    companion object {
        const val ROOT_ID = "${XId.SCHEME_APPS}://"

        // Category container ids. '@' is not a legal package-name character, so these never
        // collide with a real `apps://<pkg>` id.
        private const val PATH_USER = "@user"
        private const val PATH_SYSTEM = "@system"
        const val CAT_USER = "${XId.SCHEME_APPS}://$PATH_USER"
        const val CAT_SYSTEM = "${XId.SCHEME_APPS}://$PATH_SYSTEM"

        private const val APK_MIME = "application/vnd.android.package-archive"

        /** Root entry for pane roots ("App manager" special root). */
        fun rootEntry(): XEntry = XEntry(
            id = ROOT_ID,
            name = "App manager",
            isDir = true,
            canWrite = false,
            kind = EntryKind.APPS_ROOT,
        )
    }
}
