package app.local1st.files.core.util

import org.junit.Assert.assertEquals
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

    @Test
    fun pdf_isCategorizedFromExtensionEvenWhenMimeIsGeneric() {
        assertEquals(FileCategory.PDF, FileTypes.categoryOf("report.pdf", "application/pdf"))
        assertEquals(FileCategory.PDF, FileTypes.categoryOf("report.pdf", "application/octet-stream"))
        assertEquals(FileCategory.PDF, FileTypes.categoryOf("report.pdf", null))
    }

    @Test
    fun text_isCategorizedFromExtensionEvenWhenMimeIsGeneric() {
        assertEquals(FileCategory.TEXT, FileTypes.categoryOf("notes.txt", "text/plain"))
        assertEquals(FileCategory.TEXT, FileTypes.categoryOf("data.json", "application/json"))
        assertEquals(FileCategory.TEXT, FileTypes.categoryOf("readme.md", "application/octet-stream"))
        assertEquals(FileCategory.TEXT, FileTypes.categoryOf("notes.txt", null))
    }

    @Test
    fun mpegTsVideo_isNotCategorizedAsText() {
        assertEquals(FileCategory.VIDEO, FileTypes.categoryOf("clip.ts", "video/mp2t"))
        assertEquals(FileCategory.TEXT, FileTypes.categoryOf("app.ts", "text/plain"))
        assertEquals(FileCategory.TEXT, FileTypes.categoryOf("app.ts", null))
        assertEquals(FileCategory.GENERIC, FileTypes.categoryOf("clip.ts", "application/octet-stream"))
    }
}
