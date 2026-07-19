package app.local1st.files.core.fs.priv

import java.io.InputStream
import java.io.OutputStream

enum class TransportId { SU, SHIZUKU }

/**
 * Single-quotes a path for safe embedding in a shell command. A property of POSIX shell
 * quoting, not of any one transport — every transport ends up feeding the same `sh`.
 */
fun shQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

interface PrivilegedTransport {
    val id: TransportId
    val caps: Caps
    fun isAvailable(): Boolean
    fun exec(script: String): String
    fun execOneShot(script: String): String
    fun openRead(path: String): InputStream
    fun openWrite(path: String): OutputStream
    fun reset()
}

data class Caps(
    val appPrivateData: Boolean,
    val wholeFilesystem: Boolean,
    val remount: Boolean,
    val otherUsers: Boolean,
)
