package app.local1st.files.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafLocalPathTest {

    private val volumes = listOf(
        Volume(
            entry = XEntry(
                id = "file:///storage/emulated/0",
                name = "Internal storage",
                isDir = true,
                kind = EntryKind.VOLUME_INTERNAL,
                localPath = "/storage/emulated/0",
            ),
            label = "Internal",
            totalBytes = 1,
            freeBytes = 1,
        ),
        Volume(
            entry = XEntry(
                id = "file:///storage/ABCD-1234",
                name = "SD card",
                isDir = true,
                kind = EntryKind.VOLUME_SD,
                localPath = "/storage/ABCD-1234",
            ),
            label = "SD",
            totalBytes = 1,
            freeBytes = 1,
        ),
    )

    @Test
    fun mapsPrimaryAndUuidTreesOntoMountedVolumes() {
        assertEquals(
            "/storage/emulated/0",
            localPathForExternalStorageTree(EXTERNAL_STORAGE_AUTHORITY, "primary:", volumes),
        )
        assertEquals(
            "/storage/emulated/0/Download",
            localPathForExternalStorageTree(EXTERNAL_STORAGE_AUTHORITY, "primary:Download", volumes),
        )
        assertEquals(
            "/storage/ABCD-1234/DCIM",
            localPathForExternalStorageTree(EXTERNAL_STORAGE_AUTHORITY, "ABCD-1234:DCIM", volumes),
        )
    }

    @Test
    fun remoteAuthoritiesStayOnSaf() {
        assertNull(
            localPathForExternalStorageTree("io.github.x0b.rcx.documents", "sftp/home", volumes),
        )
    }
}
