package app.local1st.files.ui.dialogs

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.TrashFileSystem
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.encodeSafSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationPickerDirTest {

    private fun binDir(volume: String, id: String, relative: String): XEntry {
        val path = if (relative.isEmpty()) {
            "$volume/.xfiles-trash/files/$id"
        } else {
            "$volume/.xfiles-trash/files/$id/$relative"
        }
        return XEntry(
            id = "file://$path",
            name = relative.substringAfterLast('/').ifEmpty { id },
            isDir = true,
            localPath = path,
        )
    }

    private val internal = XEntry(
        id = "file:///storage/emulated/0",
        name = "Internal storage",
        isDir = true,
        kind = EntryKind.VOLUME_INTERNAL,
    )

    @Test
    fun androidDataStaysOnInternalWhenStatIsBlind() {
        val id = "file:///storage/emulated/0/Android/data"
        val entry = checkNotNull(pickerDirFromId(id, listOf(internal)))

        assertEquals(id, entry.id)
        assertEquals("data", entry.name)
        assertEquals(EntryKind.DIR, entry.kind)
        assertEquals("/storage/emulated/0/Android/data", entry.localPath)
    }

    @Test
    fun safLocationRootHasNoParentAndKeepsLocationKind() {
        val location = XEntry(
            id = "saf://loc-1",
            name = "NAS",
            isDir = true,
            kind = EntryKind.LOCATION,
        )
        val childId = "saf://loc-1/${encodeSafSegment("primary:Download")}"
        val child = checkNotNull(pickerDirFromId(childId, listOf(location)))
        assertEquals("primary:Download", child.name)
        assertEquals(EntryKind.DIR, child.kind)
        assertNull(child.localPath)
        assertEquals(location.id, pickerDirFromId(location.id, listOf(location))?.id)
    }

    @Test
    fun detailsKeepThePathUnlessTheRowIsInTheBin() {
        val volume = "/storage/emulated/0"
        val roots = listOf(volume)
        val binPath = "$volume/.xfiles-trash/files/abc-00000001/photo.jpg"
        val bin = XEntry(
            id = "file://$binPath",
            name = "photo.jpg",
            isDir = false,
            badge = "Internal · DCIM",
            localPath = binPath,
        )
        assertEquals("Internal · DCIM", detailLocationOf(bin, binPath, roots, "Recycle Bin"))

        val volumeRow = internal.copy(badge = "12 GB free")
        assertEquals(volumeRow.id, detailLocationOf(volumeRow, volume, roots, "Recycle Bin"))

        val unlabeled = bin.copy(badge = null)
        assertEquals("Recycle Bin/photo.jpg", detailLocationOf(unlabeled, binPath, roots, "Recycle Bin"))
    }

    @Test
    fun copyOrMoveDoesNotStartInsideTheBin() {
        val volume = "/storage/emulated/0"
        val roots = listOf(volume)
        assertNull(pickerStartDir(TrashFileSystem.rootEntry("回收站"), roots))
        assertNull(pickerStartDir(binDir(volume, "abc-00000001", "photos"), roots))
        val download = XEntry(
            id = "file://$volume/Download",
            name = "Download",
            isDir = true,
            localPath = "$volume/Download",
        )
        assertEquals(download.id, pickerStartDir(download, roots)?.id)
        assertNull(pickerStartDir(null, roots))
    }

    @Test
    fun recycleBinIsNotACopyOrMoveRoot() {
        assertFalse(isPickerRoot(TrashFileSystem.rootEntry()))
        assertTrue(
            isPickerRoot(
                XEntry(
                    id = "file:///storage/emulated/0",
                    name = "Internal storage",
                    isDir = true,
                    kind = EntryKind.VOLUME_INTERNAL,
                ),
            ),
        )
        assertFalse(
            isPickerRoot(
                XEntry(
                    id = "apps://",
                    name = "Apps",
                    isDir = true,
                    kind = EntryKind.APPS_ROOT,
                ),
            ),
        )
    }

    @Test
    fun trashedFolderParentIsTheBinNotTheHiddenBucket() {
        val volume = "/storage/emulated/0"
        val roots = listOf(volume)
        val folder = binDir(volume, "abc-00000001", "photos")
        assertEquals(XId.TRASH_ROOT, pickerParentId(folder, roots))

        val nested = binDir(volume, "abc-00000001", "photos/trip")
        assertEquals(folder.id, pickerParentId(nested, roots))

        val bucket = binDir(volume, "abc-00000001", "")
        assertEquals(XId.TRASH_ROOT, pickerParentId(bucket, roots))
        assertEquals(
            "file:///storage/emulated/0/Download",
            pickerParentId(
                XEntry(
                    id = "file:///storage/emulated/0/Download/Camera",
                    name = "Camera",
                    isDir = true,
                    localPath = "/storage/emulated/0/Download/Camera",
                ),
                roots,
            ),
        )
    }

    @Test
    fun searchHitLocationHidesTheBucketPath() {
        val volume = "/storage/emulated/0"
        val roots = listOf(volume)
        assertEquals(
            "回收站",
            searchHitLocation(XId.TRASH_ROOT, roots, "回收站"),
        )
        assertEquals(
            "回收站",
            searchHitLocation(
                "file://$volume/.xfiles-trash/files/abc-00000001",
                roots,
                "回收站",
            ),
        )
        assertEquals(
            "回收站/photos",
            searchHitLocation(
                "file://$volume/.xfiles-trash/files/abc-00000001/photos",
                roots,
                "回收站",
            ),
        )
        assertEquals(
            "$volume/Download",
            searchHitLocation("file://$volume/Download", roots, "回收站"),
        )
        assertEquals(
            "回收站/photos",
            searchHitLocation(
                "file:///storage/usb/.xfiles-trash/files/abc-00000001/photos",
                roots,
                "回收站",
            ),
        )
        assertEquals(
            "回收站/photos/trip/a.jpg",
            searchHitLocation(
                "file:///storage/usb/.xfiles-trash/files/abc-00000001/photos/trip/a.jpg",
                roots,
                "回收站",
            ),
        )
        assertEquals(
            "回收站",
            searchHitLocation("file:///storage/usb/.xfiles-trash", roots, "回收站"),
        )
        assertEquals(
            "回收站",
            searchHitLocation("file:///storage/usb/.xfiles-trash/files", roots, "回收站"),
        )
        assertEquals(
            "$volume/Download/.xfiles-trash/files/abc-00000001/photos",
            searchHitLocation(
                "file://$volume/Download/.xfiles-trash/files/abc-00000001/photos",
                roots,
                "回收站",
            ),
        )
    }

    @Test
    fun binDirectoryLabelHidesTheBucketPath() {
        val volume = "/storage/emulated/0"
        val roots = listOf(volume)
        assertEquals(
            "回收站/photos/trip",
            pickerPathLabel(binDir(volume, "abc-00000001", "photos/trip"), roots, "回收站"),
        )
        assertEquals(
            "回收站/photos",
            pickerPathLabel(binDir(volume, "abc-00000001", "photos"), roots, "回收站"),
        )
        assertEquals(
            "回收站",
            pickerPathLabel(TrashFileSystem.rootEntry("回收站"), roots, "Recycle Bin"),
        )
        assertEquals(
            "/storage/emulated/0/Download",
            pickerPathLabel(
                XEntry(
                    id = "file:///storage/emulated/0/Download",
                    name = "Download",
                    isDir = true,
                    localPath = "/storage/emulated/0/Download",
                ),
                roots,
                "回收站",
            ),
        )
    }

    @Test
    fun unmountedUsbPathIsGone() {
        assertNull(
            pickerDirFromId(
                "file:///storage/ABCD-1234/DCIM",
                listOf(internal),
            ),
        )
    }
}
