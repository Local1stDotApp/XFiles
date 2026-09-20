package app.local1st.files.core.text

import app.local1st.files.core.fs.FileStamp
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Keeps finished [TextRowIndex] tables on disk, so a file large enough that walking it takes
 * seconds reopens as fast as a small one.
 *
 * A table is only right for the exact bytes it was scanned from, so an entry is keyed by the
 * file's path and stamped with its size and modification time as they were *before* the scan
 * started: a file that changed under the scan then simply never matches again. Anything else —
 * a table written by another version, for another row budget or encoding, or damaged — is
 * treated as absent and thrown away. The store is a cache: losing it costs one scan.
 */
class TextRowIndexStore(
    private val dir: File,
    private val minBytes: Long = DEFAULT_MIN_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /** The table saved for [file] as it is now, or null when there is none that still applies. */
    fun load(file: File, charset: Charset, maxRowBytes: Int = MAX_ROW_BYTES): TextRowIndexSnapshot? {
        val stamp = FileStamp.of(file)
        if (stamp.size < minBytes) return null
        val entry = entryFor(file)
        if (!entry.isFile) return null
        val found = try {
            DataInputStream(entry.inputStream().buffered()).use { read(it, file, stamp, charset, maxRowBytes) }
        } catch (_: IOException) {
            Found.Unusable
        } catch (_: RuntimeException) {
            Found.Unusable
        }
        when (found) {
            // Stale or damaged: never going to match again, so make room.
            Found.Unusable -> entry.delete()
            // Scanned with other settings; still right for those, so it stays.
            Found.OtherSettings -> Unit
            is Found.Table -> entry.setLastModified(System.currentTimeMillis())
        }
        return (found as? Found.Table)?.snapshot
    }

    private sealed class Found {
        object Unusable : Found()
        object OtherSettings : Found()
        class Table(val snapshot: TextRowIndexSnapshot) : Found()
    }

    /**
     * Saves [snapshot] for [file], stamped with [stamp] — the one taken before the scan that
     * produced it. Small files are not worth an entry; their scan is faster than the read here.
     */
    fun save(file: File, stamp: FileStamp, snapshot: TextRowIndexSnapshot) {
        if (stamp.size < minBytes || snapshot.size != stamp.size) return
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val tmp = File(dir, "${entryFor(file).name}.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { write(it, file, stamp, snapshot) }
            Files.move(tmp.toPath(), entryFor(file).toPath(), StandardCopyOption.REPLACE_EXISTING)
            trim()
        } catch (_: IOException) {
            // A cache that cannot be written is a cache that is empty next time.
        } catch (_: RuntimeException) {
        }
    }

    private fun read(
        input: DataInputStream,
        file: File,
        stamp: FileStamp,
        charset: Charset,
        maxRowBytes: Int,
    ): Found {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) return Found.Unusable
        if (input.readUTF() != file.absolutePath) return Found.Unusable
        if (input.readLong() != stamp.size || input.readLong() != stamp.mtime) return Found.Unusable
        val rowBytes = input.readInt()
        val charsetName = input.readUTF()
        if (rowBytes != maxRowBytes || charsetName != charset.name()) return Found.OtherSettings
        val stride = input.readInt()
        val rowCount = input.readInt()
        val lineCount = input.readInt()
        val count = input.readInt()
        if (stride < 1 || rowCount < 0 || lineCount < 0 || count < 0 || count > MAX_CHECKPOINTS) {
            return Found.Unusable
        }
        val starts = LongArray(count) { input.readLong() }
        if (input.read() != -1) return Found.Unusable
        return Found.Table(
            TextRowIndexSnapshot(rowBytes, charsetName, stamp.size, stride, rowCount, lineCount, starts),
        )
    }

    private fun write(out: DataOutputStream, file: File, stamp: FileStamp, snapshot: TextRowIndexSnapshot) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(file.absolutePath)
        out.writeLong(stamp.size)
        out.writeLong(stamp.mtime)
        out.writeInt(snapshot.maxRowBytes)
        out.writeUTF(snapshot.charsetName)
        out.writeInt(snapshot.stride)
        out.writeInt(snapshot.rowCount)
        out.writeInt(snapshot.lineCount)
        out.writeInt(snapshot.starts.size)
        for (start in snapshot.starts) out.writeLong(start)
    }

    /** Least recently used entries go first once the store holds more than [maxEntries]. */
    private fun trim() {
        val entries = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return
        if (entries.size <= maxEntries) return
        entries.sortedBy { it.lastModified() }
            .take(entries.size - maxEntries)
            .forEach { it.delete() }
    }

    private fun entryFor(file: File): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(file.absolutePath.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, name + SUFFIX)
    }

    companion object {
        /** Below this a scan is a few dozen milliseconds; reading an entry back would not beat it. */
        const val DEFAULT_MIN_BYTES = 64L * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 32
        private const val MAGIC = 0x58524958 // "XRIX"
        private const val VERSION = 1
        private const val SUFFIX = ".rowidx"
        private const val MAX_CHECKPOINTS = 1 shl 20
    }
}