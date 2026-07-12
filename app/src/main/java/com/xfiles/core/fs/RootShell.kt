package com.xfiles.core.fs

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Thin wrapper around the device `su` binary. Every call spawns a fresh `su -c` process,
 * which keeps semantics simple and robust (no long-lived shell state to corrupt).
 * All calls block and must run on Dispatchers.IO.
 */
object RootShell {

    @Volatile
    private var availableCache: Boolean? = null

    /** Whether `su` exists and grants uid 0. Result is cached after the first probe. */
    fun isAvailable(): Boolean {
        availableCache?.let { return it }
        val ok = runCatching {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        }.getOrDefault(false)
        availableCache = ok
        return ok
    }

    /** Forget the cached availability (e.g. after the user grants/revokes superuser). */
    fun resetCache() {
        availableCache = null
    }

    /** Runs [script] via `su -c`, returning stdout. Throws [IOException] on non-zero exit. */
    @Throws(IOException::class)
    fun exec(script: String): String {
        val process = ProcessBuilder("su", "-c", script).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            throw IOException(stderr.trim().ifEmpty { "Root command failed (exit $code)" })
        }
        return stdout
    }

    /** Streams the bytes of [path] out of `cat`. Caller must close the stream. */
    @Throws(IOException::class)
    fun openRead(path: String): InputStream {
        val process = ProcessBuilder("su", "-c", "cat ${quote(path)}").start()
        return RootInputStream(process)
    }

    /** Streams bytes into `cat > path` (creates/truncates). Caller must close to commit. */
    @Throws(IOException::class)
    fun openWrite(path: String): OutputStream {
        val process = ProcessBuilder("su", "-c", "cat > ${quote(path)}").start()
        return RootOutputStream(process)
    }

    /** Single-quotes a path for safe embedding in a shell command. */
    fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private class RootInputStream(private val process: Process) : InputStream() {
        private val delegate = process.inputStream
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            runCatching { delegate.close() }
            process.destroy()
        }
    }

    private class RootOutputStream(private val process: Process) : OutputStream() {
        private val delegate = process.outputStream
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()
        override fun close() {
            // Closing cat's stdin lets it finish writing and exit.
            runCatching { delegate.close() }
            val code = process.waitFor()
            if (code != 0) throw IOException("Root write failed (exit $code)")
        }
    }
}
