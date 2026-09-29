package app.local1st.files.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MountPublishTest {

    private fun vol(path: String) = MountedVolume(path, path, writable = true)

    @Test
    fun pendingScanDoesNotSealTheEpoch() {
        val published = publishMountSnapshot(
            MountPublish(),
            epoch = 1,
            generation = 1,
            mounted = listOf(vol("/storage/emulated/0")),
            pending = true,
        )

        assertTrue(published.pending)
        assertTrue(published.needsRefresh(1))
        assertEquals(listOf("/storage/emulated/0"), published.cached.map { it.path })
    }

    @Test
    fun laterCompleteScanReplacesThePendingSnapshot() {
        val pending = publishMountSnapshot(
            MountPublish(),
            epoch = 1,
            generation = 1,
            mounted = listOf(vol("/storage/emulated/0")),
            pending = true,
        )
        val sealed = publishMountSnapshot(
            pending,
            epoch = 1,
            generation = 2,
            mounted = listOf(vol("/storage/emulated/0"), vol("/storage/usb")),
            pending = false,
        )

        assertFalse(sealed.needsRefresh(1))
        assertEquals(listOf("/storage/emulated/0", "/storage/usb"), sealed.cached.map { it.path })
    }

    @Test
    fun olderScanCannotOverwriteASealedSnapshot() {
        val sealed = publishMountSnapshot(
            MountPublish(),
            epoch = 1,
            generation = 2,
            mounted = listOf(vol("/storage/emulated/0"), vol("/storage/usb")),
            pending = false,
        )
        val late = publishMountSnapshot(
            sealed,
            epoch = 1,
            generation = 1,
            mounted = listOf(vol("/storage/emulated/0")),
            pending = true,
        )

        assertEquals(sealed, late)
    }

    @Test
    fun olderCompleteScanDoesNotSealOverAPendingObservation() {
        val previous = publishMountSnapshot(
            MountPublish(),
            epoch = 0,
            generation = 1,
            mounted = listOf(vol("/storage/emulated/0"), vol("/storage/usb")),
            pending = false,
        )
        val pending = publishMountSnapshot(
            previous,
            epoch = 1,
            generation = 5,
            mounted = listOf(vol("/storage/emulated/0")),
            pending = true,
        )

        assertTrue(pending.pending)
        assertTrue(pending.needsRefresh(1))
        assertEquals(
            listOf("/storage/emulated/0", "/storage/usb"),
            pending.cached.map { it.path },
        )

        val late = publishMountSnapshot(
            pending,
            epoch = 1,
            generation = 2,
            mounted = listOf(vol("/storage/emulated/0")),
            pending = false,
        )

        assertEquals(pending, late)
    }

    @Test
    fun pendingScanReplacesWritableForAVolumeItStillSees() {
        val sealed = publishMountSnapshot(
            MountPublish(),
            epoch = 1,
            generation = 1,
            mounted = listOf(
                MountedVolume("/storage/emulated/0", "Internal", writable = true),
                MountedVolume("/storage/usb", "USB", writable = false),
            ),
            pending = false,
        )
        val pending = publishMountSnapshot(
            sealed,
            epoch = 2,
            generation = 2,
            mounted = listOf(MountedVolume("/storage/usb", "USB", writable = true)),
            pending = true,
        )

        assertTrue(pending.pending)
        assertEquals(
            listOf("/storage/emulated/0", "/storage/usb"),
            pending.cached.map { it.path },
        )
        assertTrue(pending.cached.first { it.path == "/storage/usb" }.writable)
        assertTrue(pending.cached.first { it.path == "/storage/emulated/0" }.writable)
    }

    @Test
    fun newEpochKeepsASmallerMountedSet() {
        val sealed = publishMountSnapshot(
            MountPublish(),
            epoch = 1,
            generation = 1,
            mounted = listOf(vol("/storage/emulated/0"), vol("/storage/usb")),
            pending = false,
        )
        val unplugged = publishMountSnapshot(
            sealed,
            epoch = 2,
            generation = 2,
            mounted = listOf(vol("/storage/emulated/0")),
            pending = false,
        )

        assertFalse(unplugged.needsRefresh(2))
        assertEquals(listOf("/storage/emulated/0"), unplugged.cached.map { it.path })
    }
}
