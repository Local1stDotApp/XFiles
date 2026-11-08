package app.local1st.files.core.archive

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A zip archive opened by libxfarchive's random-access reader.
 *
 * [entries] comes from the central directory, read once at [open]. Any number of
 * [openEntry] streams can be open at a time, from any threads: each reads its own entry at
 * its own offset, which is what lets extraction run one stream per core. Closing the archive
 * leaves streams that are already open readable until they close too.
 */
internal class NativeZip private constructor(private var handle: Long) : Closeable {

    class Entry(
        /** Position in the central directory; what [openEntry] takes. */
        val index: Int,
        val name: String,
        /** Uncompressed size from the central directory. */
        val size: Long,
        val compressedSize: Long,
        /** Epoch millis: an extra field's UTC time, else the DOS time read as local time. */
        val time: Long,
        val method: Int,
        val isEncrypted: Boolean,
    ) {
        val isDirectory: Boolean get() = name.endsWith("/")
    }

    val entries: List<Entry>

    /**
     * Native error messages start with the entry's stored name read as UTF-8. For the entries
     * where that differs from [Entry.name], legacy code pages above all, this holds that form,
     * so [named] can put the real name in its place.
     */
    private val quotedNames: Map<Int, String>

    /** Entries open in parallel under the read lock; [close] takes the write lock. */
    private val lock = ReentrantReadWriteLock()

    init {
        val quoted = HashMap<Int, String>()
        entries = parseListing(NativeArchive.zipListing(handle), quoted)
        quotedNames = quoted
    }

    /** Streams entry [index]. Throws for encrypted entries and unsupported methods. */
    fun openEntry(index: Int): InputStream {
        val entry = entries.getOrNull(index) ?: throw IOException("No such zip entry: $index")
        if (entry.isEncrypted) throw IOException("${entry.name} is password-protected")
        val stream = lock.read {
            if (handle == 0L) throw IOException("Zip archive is closed")
            try {
                NativeArchive.zipOpenEntry(handle, index)
            } catch (e: IOException) {
                throw named(index, e)
            }
        }
        return EntryStream(stream, index)
    }

    override fun close() {
        lock.write {
            val toClose = handle
            if (toClose == 0L) return
            handle = 0L
            NativeArchive.zipClose(toClose)
        }
    }

    /** [e] with entry [index]'s name in place of the form the native side quoted. */
    private fun named(index: Int, e: IOException): IOException {
        val quoted = quotedNames[index] ?: return e
        val message = e.message ?: return e
        if (!message.startsWith(quoted)) return e
        return IOException(entries[index].name + message.substring(quoted.length)).also { it.stackTrace = e.stackTrace }
    }

    /** Reads and close are serialized, so a close from another thread can't free a live read. */
    private inner class EntryStream(private var stream: Long, private val index: Int) : InputStream() {
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1

        @Synchronized
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            if (stream == 0L) throw IOException("Stream closed")
            if (len == 0) return 0
            return try {
                NativeArchive.zipRead(stream, b, off, len)
            } catch (e: IOException) {
                throw named(index, e)
            }
        }

        @Synchronized
        override fun close() {
            val toClose = stream
            if (toClose == 0L) return
            stream = 0L
            NativeArchive.zipCloseEntry(toClose)
        }
    }

    companion object {
        private const val FLAG_ENCRYPTED = 0x0001
        private const val METHOD_AES = 99
        private const val PLATFORM_FAT = 0

        /**
         * A listing record's fixed part: u64 size, u64 compressed size, i64 extra-field time,
         * u32 DOS time, u32 CRC, u32 external attributes, u16 method, u16 flags, u16 made by,
         * u8 name-is-UTF-8 and u16 name length, at offsets 0 to 43. The name follows.
         */
        private const val RECORD = 45

        fun open(file: File): NativeZip {
            NativeArchive.ensureLoaded()
            return NativeZip(NativeArchive.zipOpen(file.path.toByteArray()))
        }

        /**
         * See zipListing in jni_zip.c for the layout. Fills [quoted] with the entries whose
         * stored name, read as UTF-8, isn't their name.
         */
        private fun parseListing(bytes: ByteArray, quoted: MutableMap<Int, String>): List<Entry> {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = buf.getInt(0)
            val records = IntArray(count)
            val stored = ArrayList<ByteArray>(count)
            val markedUtf8 = BooleanArray(count)
            var at = 4
            for (index in 0 until count) {
                records[index] = at
                markedUtf8[index] = bytes[at + 42].toInt() != 0
                val nameLength = buf.getShort(at + 43).toInt() and 0xFFFF
                stored += bytes.copyOfRange(at + RECORD, at + RECORD + nameLength)
                at += RECORD + nameLength
            }
            // The names share one encoding, so they are decoded together.
            val names = ArchiveNames.decodeZip(stored) { markedUtf8[it] }
            val calendar = Calendar.getInstance()
            return List(count) { index ->
                val record = records[index]
                val extraTime = buf.getLong(record + 16)
                val dosTime = buf.getInt(record + 24).toLong() and 0xFFFFFFFFL
                val method = buf.getShort(record + 36).toInt() and 0xFFFF
                val flags = buf.getShort(record + 38).toInt() and 0xFFFF
                val madeBy = buf.getShort(record + 40).toInt() and 0xFFFF
                var name = names[index]
                // Commons Compress's rule: a DOS-made name without slashes uses backslashes.
                if ((madeBy shr 8) and 0x0F == PLATFORM_FAT && '/' !in name) {
                    name = name.replace('\\', '/')
                }
                val asUtf8 = String(stored[index], Charsets.UTF_8)
                if (asUtf8 != name) quoted[index] = asUtf8
                Entry(
                    index = index,
                    name = name,
                    size = buf.getLong(record),
                    compressedSize = buf.getLong(record + 8),
                    time = if (extraTime != Long.MIN_VALUE) extraTime else dosToJava(dosTime, calendar),
                    method = method,
                    isEncrypted = flags and FLAG_ENCRYPTED != 0 || method == METHOD_AES,
                )
            }
        }

        /** A DOS date and time in the device's time zone, read leniently like java.util.zip. */
        private fun dosToJava(dos: Long, calendar: Calendar): Long {
            calendar.clear()
            calendar.set(
                ((dos shr 25) and 0x7F).toInt() + 1980,
                ((dos shr 21) and 0x0F).toInt() - 1,
                ((dos shr 16) and 0x1F).toInt(),
                ((dos shr 11) and 0x1F).toInt(),
                ((dos shr 5) and 0x3F).toInt(),
                ((dos shl 1) and 0x3E).toInt(),
            )
            return calendar.timeInMillis
        }
    }
}
