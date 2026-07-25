package app.local1st.files.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandardDirsTest {
    @Test
    fun platformDirs_areRecognisedOnEveryVolumeShape() {
        val cases = mapOf(
            "file:///storage/emulated/0/DCIM" to StandardDir.DCIM,
            "file:///storage/emulated/10/Download" to StandardDir.DOWNLOAD,
            "file:///sdcard/Documents" to StandardDir.DOCUMENTS,
            "file:///storage/self/primary/Music" to StandardDir.MUSIC,
            "file:///storage/1A2B-3C4D/Movies" to StandardDir.MOVIES,
            "file:///mnt/media_rw/1A2B-3C4D/Pictures" to StandardDir.PICTURES,
            "root:///data/media/0/Android/obb" to StandardDir.ANDROID_OBB,
            // Trailing slash and OEM casing are both cosmetic.
            "file:///storage/emulated/0/downloads/" to StandardDir.DOWNLOAD,
            "file:///storage/emulated/0/dcim/Camera" to StandardDir.CAMERA,
        )

        cases.forEach { (id, expected) -> assertEquals(id, expected, StandardDirs.of(id)) }
    }

    @Test
    fun lookalikes_stayPlainFolders() {
        listOf(
            // The volume root itself, and a nesting level the platform never uses.
            "file:///storage/emulated/0",
            "file:///storage/emulated/0/Android/data/com.example/files/Download",
            // Same name, but not on a volume: inside an archive, or elsewhere on the disk.
            "zip:///storage/emulated/0/a.zip!/DCIM",
            "root:///data/local/tmp/Documents",
            // An ordinary folder some app made at the volume root.
            "file:///storage/emulated/0/Telegram",
        ).forEach { assertNull(it, StandardDirs.of(it)) }
    }

    @Test
    fun sandboxDirs_reportTheOwningPackage() {
        val cases = mapOf(
            "file:///storage/emulated/0/Android/data/com.example.app" to "com.example.app",
            "file:///storage/emulated/0/Android/media/com.example.app" to "com.example.app",
            "file:///storage/emulated/0/Android/obb/com.example.app" to "com.example.app",
            "file:///storage/1A2B-3C4D/Android/data/com.example.app" to "com.example.app",
            "root:///data/data/com.example.app" to "com.example.app",
            "root:///data/user/0/com.example.app" to "com.example.app",
            "root:///data/user_de/0/com.example.app" to "com.example.app",
            "root:///data/media/0/Android/data/com.example.app" to "com.example.app",
        )

        cases.forEach { (id, expected) -> assertEquals(id, expected, StandardDirs.ownerPackageOf(id)) }
    }

    @Test
    fun nonSandboxDirs_haveNoOwningPackage() {
        listOf(
            // The sandbox roots themselves, and everything below a package's own directory.
            "file:///storage/emulated/0/Android/data",
            "file:///storage/emulated/0/Android/data/com.example.app/files",
            "root:///data/data",
            // Package-shaped names that are not a sandbox: a folder at the volume root, and an
            // apk unpacked into a zip listing.
            "file:///storage/emulated/0/com.example.app",
            "zip:///storage/emulated/0/a.zip!/Android/data/com.example.app",
            // A directory that simply is not named like a package.
            "file:///storage/emulated/0/Android/data/.nomedia",
        ).forEach { assertNull(it, StandardDirs.ownerPackageOf(it)) }
    }
}
