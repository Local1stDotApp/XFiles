package app.local1st.files.core.fs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RarFlavorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fdroidFlavor_leavesJunrarOut() {
        // F-Droid's scanner rejects junrar (UnRAR licence), so it must not reach this classpath.
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.github.junrar.Archive")
        }
    }

    @Test
    fun fdroidFlavor_readsEveryRarThroughLibarchive() {
        val solid = File(temporaryFolder.root, "solid.rar")
        javaClass.getResourceAsStream("/rar/test_read_format_rar4_solid_encrypted.rar")!!.use { input ->
            solid.outputStream().use { input.copyTo(it) }
        }

        assertFalse(RarArchives.readsItself(solid))
    }
}
