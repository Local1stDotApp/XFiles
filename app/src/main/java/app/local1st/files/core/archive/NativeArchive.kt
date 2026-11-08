package app.local1st.files.core.archive

import java.io.IOException

/**
 * JNI bindings to libxfarchive (app/src/main/cpp). Use [NativeZip] and [LibArchiveReader]
 * rather than these directly: they own the handles and keep reads and closes apart.
 */
internal object NativeArchive {

    // Format mask for open(); must match jni_libarchive.c.
    const val FORMAT_7ZIP = 0x01
    const val FORMAT_RAR = 0x02
    const val FORMAT_TAR = 0x04
    const val FORMAT_CPIO = 0x08
    const val FORMAT_AR = 0x10
    const val FORMAT_ISO9660 = 0x20
    const val FORMAT_RAW = 0x40

    // libarchive's AE_IF* file types.
    const val TYPE_FILE = 0x8000
    const val TYPE_DIR = 0x4000

    private val loadError: Throwable? = try {
        System.loadLibrary("xfarchive")
        null
    } catch (e: UnsatisfiedLinkError) {
        e
    }

    /** A missing or broken library fails the archive being opened, not the app. */
    fun ensureLoaded() {
        loadError?.let { throw IOException("Archive support is unavailable", it) }
    }

    // ---- libarchive: every format but zip, front to back -----------------------------------

    @JvmStatic external fun open(paths: Array<ByteArray>, formats: Int): Long

    /** Path bytes of the next entry, or null at the end. Fills [info]: type, size, mtime, flags. */
    @JvmStatic external fun next(handle: Long, info: LongArray): ByteArray?

    @JvmStatic external fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** The outermost decompression filter; 0 when the data is not compressed. */
    @JvmStatic external fun filter(handle: Long): Int

    @JvmStatic external fun close(handle: Long)

    // ---- zip: random access -----------------------------------------------------------------

    @JvmStatic external fun zipOpen(path: ByteArray): Long

    @JvmStatic external fun zipListing(handle: Long): ByteArray

    @JvmStatic external fun zipOpenEntry(handle: Long, index: Int): Long

    @JvmStatic external fun zipRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int

    @JvmStatic external fun zipCloseEntry(handle: Long)

    @JvmStatic external fun zipClose(handle: Long)
}
