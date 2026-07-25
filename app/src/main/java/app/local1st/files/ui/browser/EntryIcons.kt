package app.local1st.files.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.thumb.AppIcon
import app.local1st.files.core.util.AppComponents
import app.local1st.files.core.util.ComponentType
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.StandardDirs
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

object EntryIcons {

    fun forEntry(entry: XEntry, expanded: Boolean = false): ImageVector = when {
        // A pinned favorite folder keeps its star even when expanded — the star marks
        // the shortcut root itself, not its open/closed state.
        entry.pinned && entry.isDir -> Icons.Outlined.FolderSpecial
        else -> forKind(entry, expanded)
    }

    /**
     * Emblem for a directory the platform owns (DCIM, Download, ...), stamped into the corner of
     * its folder by [EntryIcon]. The folder shape stays: these rows are still folders, still one
     * glance away from "container or file", and still show whether they are open. Null for
     * everything else, a pinned folder included — its star has already claimed the icon slot.
     */
    fun badgeFor(entry: XEntry): ImageVector? {
        if (!entry.isDir || entry.pinned) return null
        return StandardDirs.of(entry.id)?.let(StandardDirBadges::forDir)
    }

    private fun forKind(entry: XEntry, expanded: Boolean): ImageVector = when (entry.kind) {
        EntryKind.VOLUME_INTERNAL -> Icons.Outlined.Smartphone
        EntryKind.VOLUME_SD -> Icons.Outlined.SdCard
        EntryKind.VOLUME_USB -> Icons.Outlined.Usb
        EntryKind.APPS_ROOT -> Icons.Outlined.Apps
        EntryKind.APP -> Icons.Outlined.Android
        EntryKind.APP_COMPONENT_GROUP, EntryKind.APP_COMPONENT -> componentIcon(entry)
        EntryKind.ROOT -> Icons.Outlined.Security
        EntryKind.ARCHIVE -> Icons.Outlined.FolderZip
        EntryKind.DIR -> if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder
        EntryKind.FILE -> forCategory(FileTypes.categoryOf(entry.name, entry.mime))
    }

    /** Per-type icon for a component group/leaf; the "Components" wrapper gets the generic one. */
    private fun componentIcon(entry: XEntry): ImageVector {
        val slug = entry.id.substringAfter("/${AppComponents.COMPONENTS_SEGMENT}", "")
            .trimStart('/').substringBefore('/')
        return when (ComponentType.fromSlug(slug)) {
            ComponentType.ACTIVITY -> Icons.Outlined.Window
            ComponentType.SERVICE -> Icons.Outlined.Sync
            ComponentType.RECEIVER -> Icons.Outlined.Podcasts
            ComponentType.PROVIDER -> Icons.Outlined.Storage
            null -> Icons.Outlined.Widgets
        }
    }

    fun forCategory(category: FileCategory): ImageVector = when (category) {
        FileCategory.IMAGE -> Icons.Outlined.Image
        FileCategory.VIDEO -> Icons.Outlined.Movie
        FileCategory.AUDIO -> Icons.Outlined.MusicNote
        FileCategory.TEXT -> Icons.Outlined.Description
        FileCategory.PDF -> Icons.Outlined.PictureAsPdf
        FileCategory.ARCHIVE -> Icons.Outlined.FolderZip
        FileCategory.APK -> Icons.Outlined.Android
        FileCategory.DATABASE -> Icons.Outlined.Storage
        FileCategory.GENERIC -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }

    /** Badge diameter and its clearance ring, as fractions of the icon slot. */
    internal const val BADGE_FRACTION = 0.52f
    internal const val BADGE_CLEARANCE = 1.24f

    /** True when the row should try a Coil thumbnail instead of a vector icon. */
    fun wantsThumbnail(entry: XEntry): Boolean {
        // size < 0 = stat failed mid-listing: nothing decodable behind the entry, and a
        // video's (path, mtime, size) thumb-cache key would be degenerate.
        val readableModel = entry.localPath != null ||
            (entry.scheme == XId.SCHEME_ROOT && PrivilegedAccess.canOpenFd())
        if (!readableModel || entry.isDir || entry.size < 0) return false
        return when (FileTypes.categoryOf(entry.name, entry.mime)) {
            FileCategory.IMAGE, FileCategory.VIDEO -> true
            else -> false
        }
    }
}

/**
 * An entry's icon, with the standard-directory emblem stamped over it when it has one.
 *
 * The badge is punched out of the folder rather than laid on top of it: the glyph and the folder
 * share a color, so without the gap the two outlines would read as one shape. Clearing works on
 * any row background — highlighted, selected or plain — which drawing an opaque disc would not.
 *
 * [modifier] must carry the size; the icon fills it.
 */
@Composable
fun EntryIcon(
    entry: XEntry,
    tint: Color,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val icon = EntryIcons.forEntry(entry, expanded)
    val owner = StandardDirs.ownerPackageOf(entry.id)
    if (owner != null) {
        AppSandboxIcon(owner, icon, tint, modifier)
        return
    }
    val badge = EntryIcons.badgeFor(entry)
    if (badge == null) {
        Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
        return
    }
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.matchParentSize())
        Icon(
            badge,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxSize(EntryIcons.BADGE_FRACTION)
                .drawBehind {
                    drawCircle(
                        Color.Black,
                        radius = size.minDimension / 2f * EntryIcons.BADGE_CLEARANCE,
                        blendMode = BlendMode.Clear,
                    )
                },
        )
    }
}

/**
 * An app's private directory, wearing that app's launcher icon — inside `Android/data` every row
 * is a folder anyway, so the shape says nothing and the identity says everything.
 *
 * [folder] shows through until the icon arrives, and keeps showing if it never does: these
 * directories outlive the apps that leave them behind, and an uninstalled package has no icon.
 */
@Composable
private fun AppSandboxIcon(
    packageName: String,
    folder: ImageVector,
    tint: Color,
    modifier: Modifier,
) {
    var loaded by remember(packageName) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!loaded) {
            Icon(folder, contentDescription = null, tint = tint, modifier = Modifier.matchParentSize())
        }
        AsyncImage(
            model = AppIcon(packageName),
            contentDescription = null,
            onState = { loaded = it is AsyncImagePainter.State.Success },
            modifier = Modifier.matchParentSize(),
        )
    }
}
