package app.local1st.files.core.fs

import com.github.junrar.Archive
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * A multi-volume RAR set handed to junrar as one single-volume archive.
 *
 * junrar reads a set one volume at a time. It lists only the members whose headers are in the
 * volume it has open. When a member's data runs on into the next volume, its RAR 4 code frees
 * the PPMd model and restarts the member's CRC at the switch, so the member fails with a CRC
 * error. Once the set is joined, junrar never switches volume. Each member's parts become one
 * header and one run of data, as a single-volume archive stores them. Only those headers are
 * rewritten; the data is read in place from the volume files.
 *
 * [join] leaves anything else to junrar's own reading: archives that aren't RAR 4 or RAR 5
 * multi-volume sets starting at their signature, sets with encrypted headers, and sets whose
 * headers fail their checksum.
 */
internal class JoinedRarVolumes private constructor(
    private val volumes: List<File>,
    private val pieces: List<Piece>,
    private val incompleteHeaders: Set<Long>,
) : VolumeManager {

    /** The joined archive's size. (Not `length`: inside [JoinedVolume] that name is getLength() itself.) */
    private val joinedLength: Long = pieces.last().let { it.start + it.length }

    /**
     * Whether the member whose header junrar read at [headerPosition] runs on into volumes
     * that are missing. junrar would read such a member as far as the volumes go and stop.
     */
    fun isIncomplete(headerPosition: Long): Boolean = headerPosition in incompleteHeaders

    override fun nextVolume(archive: Archive, lastVolume: Volume?): Volume? =
        if (lastVolume == null) JoinedVolume(archive) else null

    private inner class JoinedVolume(private val archive: Archive) : Volume {
        override fun getChannel(): SeekableReadOnlyByteChannel = JoinedChannel()
        override fun getLength(): Long = joinedLength
        override fun getArchive(): Archive = archive
    }

    /**
     * Bytes `[start, start + length)` of the joined archive: [bytes] where a header was
     * rewritten, else the run at [offset] in volume [volume].
     */
    private class Piece(
        val start: Long,
        val length: Long,
        val bytes: ByteArray?,
        val volume: Int,
        val offset: Long,
    )

    private inner class JoinedChannel : SeekableReadOnlyByteChannel {
        private val files = arrayOfNulls<RandomAccessFile>(volumes.size)
        private var position = 0L

        override fun getPosition(): Long = position

        override fun setPosition(pos: Long) {
            position = pos
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, count: Int): Int {
            if (count <= 0) return 0
            if (position < 0 || position >= joinedLength) return -1
            val piece = pieceAt(position)
            val skip = position - piece.start
            val n = minOf(count.toLong(), piece.length - skip).toInt()
            val bytes = piece.bytes
            if (bytes != null) {
                System.arraycopy(bytes, skip.toInt(), buffer, off, n)
            } else {
                val file = files[piece.volume]
                    ?: RandomAccessFile(volumes[piece.volume], "r").also { files[piece.volume] = it }
                file.seek(piece.offset + skip)
                file.readFully(buffer, off, n)
            }
            position += n
            return n
        }

        override fun readFully(buffer: ByteArray, count: Int): Int {
            var done = 0
            while (done < count) {
                val n = read(buffer, done, count - done)
                if (n < 0) throw EOFException()
                done += n
            }
            return count
        }

        override fun close() {
            var failure: IOException? = null
            for (i in files.indices) {
                try {
                    files[i]?.close()
                } catch (e: IOException) {
                    failure = failure ?: e
                }
                files[i] = null
            }
            failure?.let { throw it }
        }

        private fun pieceAt(pos: Long): Piece {
            var low = 0
            var high = pieces.lastIndex
            while (low < high) {
                val mid = (low + high + 1) ushr 1
                if (pieces[mid].start <= pos) low = mid else high = mid - 1
            }
            return pieces[low]
        }
    }

    companion object {
        /**
         * [volumes] joined into one archive, or null when there is nothing to join or this
         * can't join it. [volumes] is a whole set, first volume first, as
         * [RarVolumes.volumesOf] finds it.
         */
        fun join(volumes: List<File>): JoinedRarVolumes? {
            if (volumes.size < 2) return null
            val format = RandomAccessFile(volumes[0], "r").use { first ->
                when {
                    first.startsWith(Rar5.signature) -> Rar5
                    first.startsWith(Rar4.signature) -> Rar4
                    else -> null
                }
            } ?: return null
            return try {
                Joiner(volumes, format).join()
            } catch (_: NotJoinable) {
                null
            }
        }
    }

    /** Thrown while reading headers that this can't join. */
    private class NotJoinable : Exception()

    private enum class Kind { MAIN, FILE, END, ENCRYPTED, OTHER }

    /**
     * One header block. [isVolume] and [isSolid] are about the archive, from its main header;
     * [name] identifies a file block's member across its parts.
     */
    private class Block(
        val kind: Kind,
        val header: ByteArray,
        val dataSize: Long,
        val splitBefore: Boolean = false,
        val splitAfter: Boolean = false,
        val isVolume: Boolean = false,
        val isSolid: Boolean = false,
        val name: ByteArray? = null,
        /** RAR 5: the header's type, flags and extra-area size, and where the fields after them start. */
        val type: Long = 0,
        val flags: Long = 0,
        val extraSize: Long = 0,
        val restOffset: Int = 0,
    )

    private interface Format {
        val signature: ByteArray
        val endHeader: ByteArray

        /** The block at [pos], or null where [file] holds no whole block. */
        fun readBlock(file: RandomAccessFile, pos: Long): Block?

        fun mainHeader(solid: Boolean): ByteArray

        /** [last], a member's last part, rewritten to carry all [dataSize] bytes of its parts. */
        fun joinedHeader(last: Block, dataSize: Long): ByteArray
    }

    /** Where one part of a member keeps its data. */
    private class Part(val volume: Int, val offset: Long, val size: Long)

    private class Joiner(private val volumes: List<File>, private val format: Format) {
        private val pieces = ArrayList<Piece>()
        private var length = 0L
        private val incomplete = HashSet<Long>()

        /** The member still waiting for parts: the last part read so far, and every part's data. */
        private var pending: Block? = null
        private val pendingParts = ArrayList<Part>()

        fun join(): JoinedRarVolumes {
            emit(format.signature)
            for (index in volumes.indices) {
                if (!readVolume(index)) break
            }
            // Its later parts would have been in volumes that aren't there.
            finishPending(complete = false)
            emit(format.endHeader)
            return JoinedRarVolumes(volumes, pieces, incomplete)
        }

        /** Takes in volume [index]; false when the set ends there instead. */
        private fun readVolume(index: Int): Boolean = RandomAccessFile(volumes[index], "r").use { file ->
            if (!file.startsWith(format.signature)) {
                if (index == 0) throw NotJoinable()
                return@use false
            }
            val size = file.length()
            var pos = format.signature.size.toLong()
            var mainSeen = false
            while (true) {
                val block = format.readBlock(file, pos) ?: break
                val dataStart = pos + block.header.size
                when (block.kind) {
                    Kind.ENCRYPTED -> throw NotJoinable()
                    Kind.MAIN -> {
                        if (index == 0) {
                            if (!block.isVolume || mainSeen) throw NotJoinable()
                            emit(format.mainHeader(block.isSolid))
                        }
                        mainSeen = true
                    }
                    Kind.FILE -> {
                        if (!mainSeen) throw NotJoinable()
                        if (dataStart + block.dataSize > size) {
                            // A cut-off volume: what it lacks can't be read, and the set ends here.
                            take(block, Part(index, dataStart, size - dataStart), cutOff = true)
                            finishPending(complete = false)
                            return@use false
                        }
                        take(block, Part(index, dataStart, block.dataSize), cutOff = false)
                    }
                    Kind.END -> break
                    Kind.OTHER -> Unit
                }
                pos = dataStart + block.dataSize
            }
            true
        }

        private fun take(block: Block, part: Part, cutOff: Boolean) {
            val waiting = pending
            if (block.splitBefore) {
                // A continuation of anything but the member waiting for one can't be read.
                if (waiting == null || !waiting.name.contentEquals(block.name)) {
                    finishPending(complete = false)
                    return
                }
            } else {
                finishPending(complete = false)
            }
            pending = block
            pendingParts += part
            if (!block.splitAfter && !cutOff) finishPending(complete = true)
        }

        private fun finishPending(complete: Boolean) {
            val last = pending ?: return
            if (!complete) incomplete += length
            emit(
                if (complete && pendingParts.size == 1) {
                    last.header
                } else {
                    format.joinedHeader(last, pendingParts.sumOf { it.size })
                },
            )
            for (part in pendingParts) {
                if (part.size > 0) {
                    pieces += Piece(length, part.size, null, part.volume, part.offset)
                    length += part.size
                }
            }
            pending = null
            pendingParts.clear()
        }

        private fun emit(bytes: ByteArray) {
            pieces += Piece(length, bytes.size.toLong(), bytes, -1, 0L)
            length += bytes.size
        }
    }

    /** RAR 1.5 to 4.x. */
    private object Rar4 : Format {
        override val signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)

        private const val MAIN = 0x73
        private const val FILE = 0x74
        private const val SERVICE = 0x7A
        private const val END = 0x7B
        private const val MHD_VOLUME = 0x0001
        private const val MHD_SOLID = 0x0008
        private const val MHD_PASSWORD = 0x0080
        private const val LHD_SPLIT_BEFORE = 0x0001
        private const val LHD_SPLIT_AFTER = 0x0002
        private const val LHD_LARGE = 0x0100
        private const val LONG_BLOCK = 0x8000

        override val endHeader: ByteArray = withCrc(byteArrayOf(0, 0, END.toByte(), 0x00, 0x40, 7, 0))

        override fun mainHeader(solid: Boolean): ByteArray = withCrc(
            ByteArray(13).also {
                it[2] = MAIN.toByte()
                it.putU16(3, if (solid) MHD_SOLID else 0)
                it.putU16(5, 13)
            },
        )

        override fun readBlock(file: RandomAccessFile, pos: Long): Block? {
            val base = file.readAt(pos, 7) ?: return null
            val type = base[2].toInt() and 0xFF
            val flags = base.u16(3)
            val headSize = base.u16(5)
            if (headSize < 7) throw NotJoinable()
            val header = file.readAt(pos, headSize) ?: return null
            return when (type) {
                MAIN -> when {
                    flags and MHD_PASSWORD != 0 -> Block(Kind.ENCRYPTED, header, 0)
                    else -> Block(
                        Kind.MAIN,
                        header,
                        0,
                        isVolume = flags and MHD_VOLUME != 0,
                        isSolid = flags and MHD_SOLID != 0,
                    )
                }
                FILE -> {
                    val size = dataSize(header, flags)
                    val nameOffset = if (flags and LHD_LARGE != 0) 40 else 32
                    val nameSize = header.u16(26)
                    if (nameOffset + nameSize > headSize || crcOf(header) != header.u16(0)) {
                        throw NotJoinable()
                    }
                    Block(
                        Kind.FILE,
                        header,
                        size,
                        splitBefore = flags and LHD_SPLIT_BEFORE != 0,
                        splitAfter = flags and LHD_SPLIT_AFTER != 0,
                        name = header.copyOfRange(nameOffset, nameOffset + nameSize),
                    )
                }
                SERVICE -> Block(Kind.OTHER, header, dataSize(header, flags))
                END -> Block(Kind.END, header, 0)
                else -> {
                    if (flags and LONG_BLOCK != 0 && headSize < 11) throw NotJoinable()
                    Block(Kind.OTHER, header, if (flags and LONG_BLOCK != 0) header.u32(7) else 0L)
                }
            }
        }

        override fun joinedHeader(last: Block, dataSize: Long): ByteArray {
            var header = last.header.copyOf()
            var flags = header.u16(3) and (LHD_SPLIT_BEFORE or LHD_SPLIT_AFTER).inv()
            if (flags and LHD_LARGE == 0 && dataSize > 0xFFFFFFFFL) {
                // The joined data outgrows the 32-bit size field, so the high halves go in.
                header = header.copyOfRange(0, 32) + ByteArray(8) + header.copyOfRange(32, header.size)
                if (header.size > 0xFFFF) throw NotJoinable()
                flags = flags or LHD_LARGE
                header.putU16(5, header.size)
            }
            header.putU16(3, flags)
            header.putU32(7, dataSize and 0xFFFFFFFFL)
            if (flags and LHD_LARGE != 0) header.putU32(32, dataSize ushr 32)
            return withCrc(header)
        }

        /** PACK_SIZE of a file or service block: how much data follows its header. */
        private fun dataSize(header: ByteArray, flags: Int): Long {
            val large = flags and LHD_LARGE != 0
            if (header.size < if (large) 40 else 32) throw NotJoinable()
            return header.u32(7) or (if (large) header.u32(32) shl 32 else 0L)
        }

        /** The block CRC: the low half of a CRC-32 over everything after the CRC field. */
        private fun crcOf(header: ByteArray): Int =
            (CRC32().apply { update(header, 2, header.size - 2) }.value and 0xFFFF).toInt()

        private fun withCrc(header: ByteArray): ByteArray = header.also { it.putU16(0, crcOf(it)) }
    }

    /** RAR 5 and later. */
    private object Rar5 : Format {
        override val signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

        private const val MAIN = 1L
        private const val FILE = 2L
        private const val ENCRYPTION = 4L
        private const val END = 5L
        private const val HAS_EXTRA = 0x0001L
        private const val HAS_DATA = 0x0002L
        private const val SPLIT_BEFORE = 0x0008L
        private const val SPLIT_AFTER = 0x0010L
        private const val ARCHIVE_VOLUME = 0x0001L
        private const val ARCHIVE_SOLID = 0x0004L
        private const val FILE_HAS_MTIME = 0x0002L
        private const val FILE_HAS_CRC = 0x0004L

        /** unrar's own cap on a header's size. */
        private const val MAX_HEADER_SIZE = 2 * 1024 * 1024

        override val endHeader: ByteArray = header { vint(END); vint(0); vint(0) }

        override fun mainHeader(solid: Boolean): ByteArray =
            header { vint(MAIN); vint(0); vint(if (solid) ARCHIVE_SOLID else 0) }

        override fun readBlock(file: RandomAccessFile, pos: Long): Block? {
            // A CRC-32, then the header's size as a vint of up to three bytes.
            val start = file.readAt(pos, minOf(8L, file.length() - pos).toInt()) ?: return null
            if (start.size < 5) return null
            val sizeField = Reader(start, 4)
            val headerSize = sizeField.vint()
            if (headerSize <= 0 || headerSize > MAX_HEADER_SIZE) throw NotJoinable()
            val header = file.readAt(pos, sizeField.at + headerSize.toInt()) ?: return null
            val crc = CRC32().apply { update(header, 4, header.size - 4) }.value
            if (crc != header.u32(0)) throw NotJoinable()

            val fields = Reader(header, sizeField.at)
            val type = fields.vint()
            val flags = fields.vint()
            val extraSize = if (flags and HAS_EXTRA != 0L) fields.vint() else 0L
            val dataSize = if (flags and HAS_DATA != 0L) fields.vint() else 0L
            if (dataSize < 0) throw NotJoinable()
            val restOffset = fields.at
            return when (type) {
                MAIN -> {
                    val archiveFlags = fields.vint()
                    Block(
                        Kind.MAIN,
                        header,
                        dataSize,
                        isVolume = archiveFlags and ARCHIVE_VOLUME != 0L,
                        isSolid = archiveFlags and ARCHIVE_SOLID != 0L,
                    )
                }
                FILE -> {
                    val fileFlags = fields.vint()
                    fields.vint() // unpacked size
                    fields.vint() // attributes
                    if (fileFlags and FILE_HAS_MTIME != 0L) fields.skip(4)
                    if (fileFlags and FILE_HAS_CRC != 0L) fields.skip(4)
                    fields.vint() // compression
                    fields.vint() // host OS
                    val nameLength = fields.vint()
                    Block(
                        Kind.FILE,
                        header,
                        dataSize,
                        splitBefore = flags and SPLIT_BEFORE != 0L,
                        splitAfter = flags and SPLIT_AFTER != 0L,
                        name = fields.bytes(nameLength),
                        type = type,
                        flags = flags,
                        extraSize = extraSize,
                        restOffset = restOffset,
                    )
                }
                ENCRYPTION -> Block(Kind.ENCRYPTED, header, 0)
                END -> Block(Kind.END, header, 0)
                else -> Block(Kind.OTHER, header, dataSize)
            }
        }

        override fun joinedHeader(last: Block, dataSize: Long): ByteArray = header {
            vint(last.type)
            vint(last.flags and (SPLIT_BEFORE or SPLIT_AFTER).inv() or HAS_DATA)
            if (last.flags and HAS_EXTRA != 0L) vint(last.extraSize)
            vint(dataSize)
            write(last.header, last.restOffset, last.header.size - last.restOffset)
        }

        /** A header around the fields [body] writes: CRC-32, size, then the fields. */
        private fun header(body: ByteArrayOutputStream.() -> Unit): ByteArray {
            val fields = ByteArrayOutputStream().apply(body).toByteArray()
            val size = ByteArrayOutputStream().apply { vint(fields.size.toLong()) }.toByteArray()
            val crc = CRC32().apply {
                update(size)
                update(fields)
            }.value
            return ByteArray(4).also { it.putU32(0, crc) } + size + fields
        }

        private fun ByteArrayOutputStream.vint(value: Long) {
            var rest = value
            while (rest >= 0x80) {
                write(((rest and 0x7F) or 0x80).toInt())
                rest = rest ushr 7
            }
            write(rest.toInt())
        }

        /** Reads a header's fields in order; a field running past the header can't be joined. */
        private class Reader(private val bytes: ByteArray, var at: Int) {
            fun vint(): Long {
                var value = 0L
                var shift = 0
                while (true) {
                    if (at >= bytes.size || shift > 63) throw NotJoinable()
                    val b = bytes[at++].toInt() and 0xFF
                    value = value or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return value
                    shift += 7
                }
            }

            fun skip(count: Int) {
                if (count > bytes.size - at) throw NotJoinable()
                at += count
            }

            fun bytes(count: Long): ByteArray {
                if (count < 0 || count > bytes.size - at) throw NotJoinable()
                return bytes.copyOfRange(at, at + count.toInt()).also { at += count.toInt() }
            }
        }
    }
}

private fun RandomAccessFile.startsWith(prefix: ByteArray): Boolean =
    readAt(0, prefix.size)?.contentEquals(prefix) == true

/** [count] bytes at [pos], or null when the file ends before them. */
private fun RandomAccessFile.readAt(pos: Long, count: Int): ByteArray? {
    if (pos < 0 || count < 0 || pos > length() - count) return null
    return ByteArray(count).also {
        seek(pos)
        readFully(it)
    }
}

private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(at: Int): Long =
    (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)

private fun ByteArray.putU16(at: Int, value: Int) {
    this[at] = value.toByte()
    this[at + 1] = (value ushr 8).toByte()
}

private fun ByteArray.putU32(at: Int, value: Long) {
    putU16(at, (value and 0xFFFF).toInt())
    putU16(at + 2, ((value ushr 16) and 0xFFFF).toInt())
}
