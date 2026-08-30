package app.local1st.files.ui.dialogs

import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.encodeSafSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationPickerDirTest {

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
    fun unmountedUsbPathIsGone() {
        assertNull(
            pickerDirFromId(
                "file:///storage/ABCD-1234/DCIM",
                listOf(internal),
            ),
        )
    }
}
