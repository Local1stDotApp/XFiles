package app.local1st.files.core.fs

import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.fs.priv.SuTransport
import app.local1st.files.core.fs.priv.TransportPref
import app.local1st.files.core.prefs.DEFAULT_ROOT_ENABLED
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RootFileSystemTest {

    @Before
    fun setUp() {
        PrivilegedAccess.enabled = true
        PrivilegedAccess.readOnly = true
        PrivilegedAccess.preference = TransportPref.AUTO
        SuTransport.reset()
    }

    @After
    fun tearDown() {
        PrivilegedAccess.enabled = false
        PrivilegedAccess.readOnly = true
        PrivilegedAccess.preference = TransportPref.AUTO
        SuTransport.reset()
    }

    @Test
    fun rootSwitchDefaultsOn() {
        assertTrue(DEFAULT_ROOT_ENABLED)
    }

    @Test
    fun filesystemRootPathShapes() {
        assertTrue(isFilesystemRoot("/"))
        assertTrue(isFilesystemRoot(""))
        assertTrue(isFilesystemRoot(" / "))
        assertFalse(isFilesystemRoot("/data"))
        assertFalse(isFilesystemRoot("/storage/emulated/0/Android/data"))
    }

    @Test
    fun rootTreeBadgeReflectsLastKnownSuAndShizuku() {
        assertEquals("Superuser · /", rootTreeBadge(suKnown = true, shizukuCoversData = false, readOnly = false))
        assertEquals(
            "Superuser · read-only",
            rootTreeBadge(suKnown = true, shizukuCoversData = true, readOnly = true),
        )
        assertEquals("Needs root (su)", rootTreeBadge(suKnown = false, shizukuCoversData = true, readOnly = true))
        assertEquals("Not available", rootTreeBadge(suKnown = false, shizukuCoversData = false, readOnly = true))
        assertEquals(
            "Superuser · read-only",
            rootTreeBadge(suKnown = null, shizukuCoversData = false, readOnly = true),
        )
        // Never-probed su stays on the optimistic label even with Shizuku around: only a
        // probe that actually failed may claim su is missing.
        assertEquals(
            "Superuser · read-only",
            rootTreeBadge(suKnown = null, shizukuCoversData = true, readOnly = true),
        )
    }

    @Test
    fun unavailableMessagesNameTheMissingCapability() {
        assertEquals(
            "Shizuku cannot browse /. Superuser (su) is required.",
            filesystemRootUnavailableMessage(shizukuActive = true),
        )
        assertTrue(filesystemRootUnavailableMessage(shizukuActive = false).contains("su"))
    }

    @Test
    fun rootEntryIsAlwaysThePrivilegedScheme() {
        val entry = RootFileSystem.rootEntry()
        assertEquals(RootFileSystem.ROOT_ID, entry.id)
        assertEquals("Root", entry.name)
        assertEquals(EntryKind.ROOT, entry.kind)
        assertTrue(entry.isDir)
        assertTrue(entry.canRead)
        assertFalse(entry.canWrite)
        assertEquals("Superuser · read-only", entry.badge)
    }

    @Test
    fun listingFilesystemRootForcedToShizukuDoesNotPretendToBeSu() {
        PrivilegedAccess.preference = TransportPref.SHIZUKU
        val error = assertThrows(IOException::class.java) {
            RootFileSystem().list(RootFileSystem.rootEntry())
        }
        assertEquals(filesystemRootUnavailableMessage(shizukuActive = true), error.message)
    }

    @Test
    fun listingFilesystemRootWithTransportOffPointsAtTheSetting() {
        PrivilegedAccess.preference = TransportPref.OFF
        val error = assertThrows(IOException::class.java) {
            RootFileSystem().list(RootFileSystem.rootEntry())
        }
        assertEquals(FILESYSTEM_ROOT_TRANSPORT_OFF_MESSAGE, error.message)
    }

    @Test
    fun listingIsBlockedWhenTheSettingsSwitchIsOff() {
        PrivilegedAccess.enabled = false
        val error = assertThrows(IOException::class.java) {
            RootFileSystem().list(RootFileSystem.rootEntry())
        }
        assertEquals("Root browsing is disabled in Settings", error.message)
    }
}
