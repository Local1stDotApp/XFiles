package app.local1st.files.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTypesTest {
    @Test
    fun supportedArchives_matchArchiveFileSystemFormats() {
        val supported = listOf(
            "sample.zip",
            "sample.jar",
            "sample.apk",
            "sample.aab",
            "sample.apks",
            "sample.apkm",
            "sample.xapk",
            "sample.7z",
            "sample.rar",
            "sample.tar",
            "sample.tar.gz",
            "sample.tgz",
            "sample.tar.bz2",
            "sample.tbz2",
            "sample.tar.xz",
            "sample.txz",
            "sample.gz",
            "sample.bz2",
            "sample.xz",
        )

        supported.forEach { assertTrue("Expected support for $it", FileTypes.isSupportedArchive(it)) }
    }

    @Test
    fun unsupportedArchives_areNotAdvertised() {
        listOf("sample.zst", "sample.iso", "sample.cab", "sample.lzh").forEach {
            assertFalse("Did not expect support for $it", FileTypes.isSupportedArchive(it))
        }
    }
}
