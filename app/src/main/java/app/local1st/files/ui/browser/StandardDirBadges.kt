package app.local1st.files.ui.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import app.local1st.files.core.util.StandardDir

/**
 * The emblem [EntryIcon] stamps into the corner of a standard directory's folder.
 *
 * Filled variants, unlike the outlined icons everywhere else: a badge is drawn at half icon size,
 * and an outlined glyph's strokes collapse into a smudge down there. Its own file because the
 * filled names would collide with the outlined ones [EntryIcons] imports.
 */
internal object StandardDirBadges {

    fun forDir(dir: StandardDir): ImageVector = when (dir) {
        StandardDir.DCIM -> Icons.Filled.PhotoCamera
        StandardDir.CAMERA -> Icons.Filled.CameraAlt
        StandardDir.SCREENSHOTS -> Icons.Filled.Screenshot
        StandardDir.PICTURES -> Icons.Filled.PhotoLibrary
        StandardDir.MOVIES -> Icons.Filled.VideoLibrary
        StandardDir.MUSIC -> Icons.Filled.LibraryMusic
        StandardDir.PODCASTS -> Icons.Filled.Podcasts
        StandardDir.AUDIOBOOKS -> Icons.Filled.Headphones
        StandardDir.RECORDINGS -> Icons.Filled.Mic
        StandardDir.RINGTONES -> Icons.Filled.RingVolume
        StandardDir.ALARMS -> Icons.Filled.Alarm
        StandardDir.NOTIFICATIONS -> Icons.Filled.Notifications
        StandardDir.DOWNLOAD -> Icons.Filled.Download
        StandardDir.DOCUMENTS -> Icons.Filled.Article
        StandardDir.BLUETOOTH -> Icons.Filled.Bluetooth
        StandardDir.ANDROID -> Icons.Filled.Android
        StandardDir.ANDROID_DATA -> Icons.Filled.DataObject
        StandardDir.ANDROID_OBB -> Icons.Filled.Inventory2
        StandardDir.ANDROID_MEDIA -> Icons.Filled.PermMedia
    }
}
