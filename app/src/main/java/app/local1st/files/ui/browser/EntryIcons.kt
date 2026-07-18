package app.local1st.files.ui.browser

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
import androidx.compose.ui.graphics.vector.ImageVector
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.util.AppComponents
import app.local1st.files.core.util.ComponentType
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes

object EntryIcons {

    fun forEntry(entry: XEntry, expanded: Boolean = false): ImageVector = when {
        // A pinned favorite folder keeps its star even when expanded — the star marks
        // the shortcut root itself, not its open/closed state.
        entry.pinned && entry.isDir -> Icons.Outlined.FolderSpecial
        else -> forKind(entry, expanded)
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

    /** True when the row should try a Coil thumbnail instead of a vector icon. */
    fun wantsThumbnail(entry: XEntry): Boolean {
        // size < 0 = stat failed mid-listing: nothing decodable behind the entry, and a
        // video's (path, mtime, size) thumb-cache key would be degenerate.
        if (entry.localPath == null || entry.isDir || entry.size < 0) return false
        return when (FileTypes.categoryOf(entry.name, entry.mime)) {
            FileCategory.IMAGE, FileCategory.VIDEO -> true
            else -> false
        }
    }
}
