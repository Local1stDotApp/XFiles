package app.local1st.files.core.thumb

import app.local1st.files.vendor.arsclib.arsc.chunk.xml.AndroidManifestBlock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkIconFetcherTest {

    @Test
    fun loneApk_isTheBase() {
        assertEquals("universal.apk", baseOf("universal.apk" to apk(split = null)))
    }

    @Test
    fun saiAndApkMirrorBundles_nameTheBase() {
        val base = baseOf(
            "split_config.arm64_v8a.apk" to apk(split = "config.arm64_v8a"),
            "base.apk" to apk(split = null),
            "split_config.xxhdpi.apk" to apk(split = "config.xxhdpi"),
        )
        assertEquals("base.apk", base)
    }

    @Test
    fun bundletoolApks_nameTheBaseMaster() {
        val base = baseOf(
            "toc.pb" to byteArrayOf(0),
            "splits/base-xxhdpi.apk" to apk(split = "config.xxhdpi"),
            "splits/base-master.apk" to apk(split = null),
        )
        assertEquals("splits/base-master.apk", base)
    }

    @Test
    fun apkPureBundle_findsTheBaseByItsManifest() {
        val base = baseOf(
            "icon.png" to byteArrayOf(0),
            "config.arm64_v8a.apk" to apk(split = "config.arm64_v8a"),
            "com.example.app.apk" to apk(split = null),
            "config.en.apk" to apk(split = "config.en"),
        )
        assertEquals("com.example.app.apk", base)
    }

    @Test
    fun bundleWithoutAReadableBase_hasNone() {
        assertNull(baseOf("manifest.json" to "{}".toByteArray()))
        assertNull(
            baseOf(
                "config.arm64_v8a.apk" to apk(split = "config.arm64_v8a"),
                "garbage.apk" to "not a zip".toByteArray(),
            ),
        )
    }

    private fun baseOf(vararg members: Pair<String, ByteArray>): String? {
        val bundle = Files.createTempFile("bundle", ".apks").toFile()
        try {
            bundle.writeBytes(zip(*members))
            return ZipFile(bundle).use { ApkIconFetcher.baseApkOf(it)?.name }
        } finally {
            bundle.delete()
        }
    }

    /** An APK as far as the base lookup reads one: a zip that opens on its binary manifest. */
    private fun apk(split: String?): ByteArray {
        val manifest = AndroidManifestBlock().apply {
            setPackageName("com.example.app")
            if (split != null) setSplit(split, true)
            refresh()
        }
        return zip("AndroidManifest.xml" to manifest.bytes, "classes.dex" to ByteArray(64))
    }

    private fun zip(vararg members: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            members.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
