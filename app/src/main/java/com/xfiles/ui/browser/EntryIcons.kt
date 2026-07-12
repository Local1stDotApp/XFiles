package com.xfiles.ui.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.ui.graphics.vector.ImageVector
import com.xfiles.core.fs.EntryKind
import com.xfiles.core.fs.XEntry
import com.xfiles.core.util.FileCategory
import com.xfiles.core.util.FileTypes

object EntryIcons {

    fun forEntry(entry: XEntry, expanded: Boolean = false): ImageVector = when (entry.kind) {
        EntryKind.VOLUME_INTERNAL -> Icons.Outlined.Smartphone
        EntryKind.VOLUME_SD -> Icons.Outlined.SdCard
        EntryKind.VOLUME_USB -> Icons.Outlined.Usb
        EntryKind.APPS_ROOT -> Icons.Outlined.Apps
        EntryKind.APP -> Icons.Outlined.Android
        EntryKind.ARCHIVE -> Icons.Outlined.FolderZip
        EntryKind.DIR -> if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder
        EntryKind.FILE -> forCategory(FileTypes.categoryOf(entry.name, entry.mime))
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
        if (entry.localPath == null || entry.isDir) return false
        return when (FileTypes.categoryOf(entry.name, entry.mime)) {
            FileCategory.IMAGE, FileCategory.VIDEO -> true
            else -> false
        }
    }
}
