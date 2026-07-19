package app.local1st.files.core.fs.priv

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Runs in Shizuku's user-service process, with the shell uid's file access. */
@Keep
class PrivFileService() : IPrivFileService.Stub() {

    // Shizuku may construct a user service either way. Its supplied Context is deliberately not
    // retained: it is not a complete app Context and is unsafe for framework component access.
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        try {
            // Unbinding only drops the connection; it does not terminate this process.
            System.exit(0)
        } catch (e: Exception) {
            throw binderFailure(ERROR_DESTROY, "Cannot stop privileged file service", e)
        }
    }

    override fun open(path: String, mode: Int): ParcelFileDescriptor = try {
        ParcelFileDescriptor.open(File(path), mode)
    } catch (e: Exception) {
        throw binderFailure(ERROR_OPEN, "Cannot open $path", e)
    }

    override fun exec(script: String): String {
        var process: Process? = null
        try {
            process = ProcessBuilder("sh", "-c", script).start()
            val stdout = StreamCollector(process.inputStream, "XFiles-privfs-stdout")
            val stderr = StreamCollector(process.errorStream, "XFiles-privfs-stderr")
            val code = process.waitFor()
            val output = stdout.finish()
            val error = stderr.finish()
            if (code != 0) {
                val detail = error.trim().ifEmpty {
                    output.trim().ifEmpty { "Command failed with exit code $code" }
                }
                throw binderFailure(ERROR_EXEC, detail)
            }
            return output
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith(ERROR_PREFIX) == true) throw e
            throw binderFailure(ERROR_EXEC, "Cannot run privileged command", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw binderFailure(ERROR_EXEC, "Privileged command was interrupted", e)
        } catch (e: Exception) {
            throw binderFailure(ERROR_EXEC, "Cannot run privileged command", e)
        } finally {
            process?.let {
                runCatching { it.inputStream.close() }
                runCatching { it.errorStream.close() }
                runCatching { it.outputStream.close() }
                runCatching { if (it.isAlive) it.destroy() }
            }
        }
    }

    private class StreamCollector(input: InputStream, name: String) {
        private val bytes = ByteArrayOutputStream()
        @Volatile private var failure: IOException? = null
        private val thread = Thread({
            try {
                input.use { it.copyTo(bytes) }
            } catch (e: IOException) {
                failure = e
            }
        }, name).apply {
            isDaemon = true
            start()
        }

        fun finish(): String {
            thread.join()
            failure?.let { throw it }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        private const val ERROR_OPEN = 1
        private const val ERROR_EXEC = 2
        private const val ERROR_DESTROY = 3
        private const val MAX_ERROR_CHARS = 16 * 1024
        private const val ERROR_PREFIX = "PRIVFS:"

        /**
         * Binder cannot marshal arbitrary exceptions. Every service failure is encoded as the
         * one supported [IllegalStateException] type with a stable error-code prefix; clients
         * convert it to IOException.
         * Capping its message well below IBinder.MAX_IPC_SIZE (64 KiB) also keeps error replies
         * from consuming the process-wide binder buffer under concurrent calls.
         */
        private fun binderFailure(
            code: Int,
            message: String,
            cause: Exception? = null,
        ): IllegalStateException {
            val causeText = cause?.message?.takeIf { it.isNotBlank() }
            val detail = if (causeText == null || causeText == message) {
                message
            } else {
                "$message: $causeText"
            }
            return IllegalStateException("$ERROR_PREFIX$code:${detail.take(MAX_ERROR_CHARS)}")
        }
    }
}
