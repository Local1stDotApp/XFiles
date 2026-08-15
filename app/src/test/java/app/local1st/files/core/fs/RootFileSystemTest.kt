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
        assertEquals("Shizuku · read-only", rootTreeBadge(suKnown = false, shizukuCoversData = true, readOnly = true))
        assertEquals("Shizuku · /", rootTreeBadge(suKnown = false, shizukuCoversData = true, readOnly = false))
        assertEquals("Not available", rootTreeBadge(suKnown = false, shizukuCoversData = false, readOnly = true))
        assertEquals(
            "Superuser · read-only",
            rootTreeBadge(suKnown = null, shizukuCoversData = false, readOnly = true),
        )
        // Shizuku is enough to list `/`, even before su has been probed.
        assertEquals(
            "Shizuku · read-only",
            rootTreeBadge(suKnown = null, shizukuCoversData = true, readOnly = true),
        )
    }

    @Test
    fun unavailableMessagesNameTheMissingCapability() {
        assertTrue(rootTransportUnavailableMessage(TransportPref.SHIZUKU).contains("Shizuku"))
        assertTrue(rootTransportUnavailableMessage(TransportPref.AUTO).contains("superuser"))
        // Forced su never falls back to Shizuku, so "start Shizuku" would be a dead end;
        // the remedies are the grant or the transport switch.
        assertTrue(rootTransportUnavailableMessage(TransportPref.SU).contains("superuser"))
        assertFalse(rootTransportUnavailableMessage(TransportPref.SU).contains("start Shizuku"))
        assertEquals(FILESYSTEM_ROOT_TRANSPORT_OFF_MESSAGE, rootTransportUnavailableMessage(TransportPref.OFF))
    }

    @Test
    fun suRetryOnFilesystemRootIsOnlyForStaleMisses() {
        assertTrue(shouldRetrySuForFilesystemRoot("/", TransportPref.AUTO, suKnownBeforeActive = false))
        assertTrue(shouldRetrySuForFilesystemRoot("/", TransportPref.SU, suKnownBeforeActive = false))
        // null = the probe inside PrivilegedAccess.active just ran (and failed) for this
        // very call; retrying immediately would repeat the Magisk prompt that was denied.
        assertFalse(shouldRetrySuForFilesystemRoot("/", TransportPref.AUTO, suKnownBeforeActive = null))
        assertFalse(shouldRetrySuForFilesystemRoot("/", TransportPref.SU, suKnownBeforeActive = null))
        assertFalse(shouldRetrySuForFilesystemRoot("/", TransportPref.SHIZUKU, suKnownBeforeActive = false))
        assertFalse(shouldRetrySuForFilesystemRoot("/", TransportPref.OFF, suKnownBeforeActive = false))
        assertFalse(shouldRetrySuForFilesystemRoot("/data", TransportPref.AUTO, suKnownBeforeActive = false))
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
        assertEquals(rootTransportUnavailableMessage(TransportPref.SHIZUKU), error.message)
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
