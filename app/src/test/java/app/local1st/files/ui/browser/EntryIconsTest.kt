package app.local1st.files.ui.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.FolderZip
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.thumb.ApkIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EntryIconsTest {

    @Test
    fun localInstallableFiles_readTheirIcon() {
        assertEquals(
            ApkIcon("/storage/emulated/0/Download/app.apk", mtime = 7L, size = 1024L),
            EntryIcons.apkIconFor(localFile("app.apk")),
        )
        listOf("app.APK", "app.apks", "app.apkm", "app.xapk").forEach {
            assertNotNull("Expected an icon for $it", EntryIcons.apkIconFor(localFile(it)))
        }
    }

    @Test
    fun filesWithNoIconToRead_keepTheGlyph() {
        // An .aab's manifest is protobuf, which PackageManager can't parse.
        assertNull(EntryIcons.apkIconFor(localFile("app.aab")))
        assertNull(EntryIcons.apkIconFor(localFile("app.zip")))
        assertNull(EntryIcons.apkIconFor(localFile("app.apk").copy(size = 0L)))
        assertNull(EntryIcons.apkIconFor(localFile("app.apk").copy(size = -1L)))
        assertNull(EntryIcons.apkIconFor(localFile("app.apk").copy(isDir = true, kind = EntryKind.DIR)))
    }

    @Test
    fun installableFilesWithoutALocalPath_keepTheGlyph() {
        val inArchive = XEntry(
            id = "${XId.SCHEME_ZIP}:///storage/emulated/0/Download/apps.zip!/app.apk",
            name = "app.apk",
            isDir = false,
            size = 1024L,
        )
        assertNull(EntryIcons.apkIconFor(inArchive))
        assertNull(EntryIcons.apkIconFor(localFile("app.apk").copy(localPath = null)))
    }

    @Test
    fun installableArchives_showThePackageGlyph() {
        assertEquals(Icons.Outlined.Android, EntryIcons.forEntry(localFile("app.apk")))
        assertEquals(Icons.Outlined.Android, EntryIcons.forEntry(localFile("app.aab")))
        assertEquals(Icons.Outlined.FolderZip, EntryIcons.forEntry(localFile("photos.zip")))
    }

    private fun localFile(name: String): XEntry {
        val path = "/storage/emulated/0/Download/$name"
        return XEntry(
            id = XId.file(path),
            name = name,
            isDir = false,
            size = 1024L,
            mtime = 7L,
            kind = EntryKind.ARCHIVE,
            localPath = path,
        )
    }
}
