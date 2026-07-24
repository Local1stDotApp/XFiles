package app.local1st.files.core.util

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** The stage an install has reached; the caller maps it to a localized message. */
enum class InstallPhase { BUILDING_APKS, EXTRACTING_OBB, WRITING_APKS }

/**
 * Progress sink for the blocking install helpers. Keeping it string-free leaves the wording
 * (and the localization) with the caller, and lets the helpers stay plain JVM code.
 */
class InstallProgress(
    val onPhase: (InstallPhase) -> Unit = {},
    /** Cumulative bytes written in the current phase; [total] is 0 when unknown. */
    val onBytes: (done: Long, total: Long) -> Unit = { _, _ -> },
    val isCancelled: () -> Boolean = { false },
)

/**
 * Copies [input] into [output], reporting cumulative bytes (starting from [done], counting
 * towards [total]) and aborting between chunks once the caller cancels. Returns the new
 * cumulative count so a multi-file copy can carry it across files.
 */
internal fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    progress: InstallProgress,
    done: Long = 0,
    total: Long = 0,
): Long {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var written = done
    while (true) {
        if (progress.isCancelled()) throw CancellationException("Install cancelled")
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        written += read
        progress.onBytes(written, total)
    }
    return written
}

private const val COPY_BUFFER_BYTES = 128 * 1024
