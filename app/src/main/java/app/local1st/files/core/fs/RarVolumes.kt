package app.local1st.files.core.fs

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Multi-volume RAR sets and the old RAR archives libarchive can't read. Shared by both flavors. */
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
     * True for the RAR archives libarchive can't read, both made by RAR 1.x:
     * - RAR 1.4 (signature `RE~^`), a format of its own;
     * - archives made by RAR 1.5x, whose members are packed with its algorithm.
     *
     * RAR 2.0 to 4.x archives, solid ones included, are read natively, through the patched
     * libarchive in `third_party/patches`.
     */
    fun isBeyondLibarchive(file: File): Boolean = try {
        RandomAccessFile(file, "r").use(::isBeyondLibarchive)
    } catch (_: IOException) {
        false
    }

    private fun isBeyondLibarchive(rar: RandomAccessFile): Boolean {
        val block = ByteArray(FILE_HEAD_FIXED)
        if (!rar.readAt(0, block, RAR4_MARKER.size)) {
            return rar.readAt(0, block, RAR14_MARKER.size) && block.startsWith(RAR14_MARKER)
        }
        if (block.startsWith(RAR14_MARKER)) return true
        if (!block.startsWith(RAR4_MARKER)) return false
        // Each block after the marker starts with CRC (2), type (1), flags (2) and size (2).
        var at = RAR4_MARKER.size.toLong()
        while (rar.readAt(at, block, BLOCK_HEAD)) {
            val type = block[2].toInt() and 0xFF
            val flags = block.u16(3)
            val size = block.u16(5)
            if (size < BLOCK_HEAD) return false
            when (type) {
                MAIN_HEAD -> if (flags and MHD_PASSWORD != 0) return false // the headers after it are encrypted
                FILE_HEAD -> {
                    if (size < FILE_HEAD_FIXED || !rar.readAt(at, block, FILE_HEAD_FIXED)) return false
                    // An archive's members come from one RAR version, so the first packed one tells.
                    if (block[25].toInt() and 0xFF != METHOD_STORE) return (block[24].toInt() and 0xFF) < UNP_VER_20
                    var packed = block.u32(7)
                    if (flags and LHD_LARGE != 0) {
                        if (!rar.readAt(at + FILE_HEAD_FIXED, block, 4)) return false
                        packed += block.u32(0) shl 32
                    }
                    at += packed
                }
                END_HEAD -> return false
                else -> if (flags and LONG_BLOCK != 0) {
                    if (!rar.readAt(at + BLOCK_HEAD, block, 4)) return false
                    at += block.u32(0)
                }
            }
            at += size
        }
        return false
    }

    private fun RandomAccessFile.readAt(position: Long, into: ByteArray, count: Int): Boolean = try {
        seek(position)
        readFully(into, 0, count)
        true
    } catch (_: EOFException) {
        false
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)

    private val PART_VOLUME = Regex("""(.*\.part)(\d+)(\.rar)""", RegexOption.IGNORE_CASE)
    private val RAR4_MARKER = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR14_MARKER = byteArrayOf(0x52, 0x45, 0x7E, 0x5E)
    private const val BLOCK_HEAD = 7

    /** A file header's fixed part: the block head, then sizes, host, CRC, time, version, method, name length, attributes. */
    private const val FILE_HEAD_FIXED = 32
    private const val MAIN_HEAD = 0x73
    private const val FILE_HEAD = 0x74
    private const val END_HEAD = 0x7B
    private const val MHD_PASSWORD = 0x0080
    private const val LHD_LARGE = 0x0100
    private const val LONG_BLOCK = 0x8000
    private const val METHOD_STORE = 0x30
    private const val UNP_VER_20 = 20
}
