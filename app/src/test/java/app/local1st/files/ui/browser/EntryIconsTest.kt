package app.local1st.files.ui.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.FolderZip
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryIconsTest {

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
