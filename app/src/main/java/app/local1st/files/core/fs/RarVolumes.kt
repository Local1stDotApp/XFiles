package app.local1st.files.core.fs

import java.io.File

/** Multi-volume RAR sets and the RAR 4 archives libarchive can't read. Shared by both flavors. */
internal object RarVolumes {

    /**
     * The volumes to read when [first] starts a multi-volume set (`name.part1.rar`,
     * `name.part2.rar`, … or `name.rar`, `name.r00`, `name.r01`, …), else just [first]. The set
     * ends at the first number with no file. Without the later volumes a reader sees only what
     * the first one holds.
     */
    fun volumesOf(first: File): List<File> {
        val dir = first.parentFile ?: return listOf(first)
        val volumes = mutableListOf(first)
        val part = PART_VOLUME.matchEntire(first.name)
        if (part != null) {
            val (prefix, digits, suffix) = part.destructured
            if (digits.trimStart('0') != "1") return volumes
            var number = 2
            while (true) {
                val next = File(dir, prefix + number.toString().padStart(digits.length, '0') + suffix)
                if (!next.isFile) break
                volumes += next
                number++
            }
        } else if (first.name.endsWith(".rar", ignoreCase = true)) {
            val stem = first.name.dropLast(3)
            val r = if (first.name.last().isUpperCase()) 'R' else 'r'
            for (number in 0..99) {
                val next = File(dir, stem + r + number.toString().padStart(2, '0'))
                if (!next.isFile) break
                volumes += next
            }
        }
        return volumes
    }

    /**
     * True for the RAR archives libarchive can't read: RAR 1.4 (signature `RE~^`), and solid
     * RAR 1.5 to 4.x, which libarchive gives up on at the second member because its state
     * carries over from the first. Solid RAR 5 is fine.
     */
    fun isBeyondLibarchive(file: File): Boolean {
        val head = ByteArray(RAR4_MARKER.size + 5)
        val read = try {
            file.inputStream().use { input ->
                var n = 0
                while (n < head.size) {
                    val r = input.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                n
            }
        } catch (_: java.io.IOException) {
            return false
        }
        if (read >= RAR14_MARKER.size && RAR14_MARKER.indices.all { head[it] == RAR14_MARKER[it] }) return true
        if (read < head.size || !RAR4_MARKER.indices.all { head[it] == RAR4_MARKER[it] }) return false
        // The main archive header follows the marker: CRC (2), type 0x73 (1), flags (2).
        val at = RAR4_MARKER.size
        if (head[at + 2] != 0x73.toByte()) return false
        val flags = (head[at + 3].toInt() and 0xFF) or ((head[at + 4].toInt() and 0xFF) shl 8)
        return flags and MHD_SOLID != 0
    }

    private val PART_VOLUME = Regex("""(.*\.part)(\d+)(\.rar)""", RegexOption.IGNORE_CASE)
    private val RAR4_MARKER = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR14_MARKER = byteArrayOf(0x52, 0x45, 0x7E, 0x5E)
    private const val MHD_SOLID = 0x0008
}
