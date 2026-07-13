package app.local1st.files.core.fs

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

    // Prefer the global mount namespace: a su requested by an app inherits the app's restricted
    // namespace, so even as uid 0 it cannot reach other apps' /data/data. `--mount-master` runs
    // in the init/global namespace where the whole filesystem is visible. Detected once, since
    // not every su build supports the flag (fall back to plain su then).
    @Volatile
    private var mountMaster: Boolean = true

    /** Whether `su` exists and grants uid 0. Result is cached after the first probe. */
    fun isAvailable(): Boolean {
        availableCache?.let { return it }
        if (probe(useMountMaster = true)) {
            mountMaster = true
            availableCache = true
            return true
        }
        if (probe(useMountMaster = false)) {
            mountMaster = false
            availableCache = true
            return true
        }
        availableCache = false
        return false
    }

    private fun probe(useMountMaster: Boolean): Boolean = runCatching {
        val process = ProcessBuilder(suArgv(useMountMaster, "id"))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.contains("uid=0")
    }.getOrDefault(false)

    /** Argv for `su` running [command], with `--mount-master` when it is supported. */
    private fun suArgv(useMountMaster: Boolean, command: String): List<String> =
        if (useMountMaster) listOf("su", "--mount-master", "-c", command)
        else listOf("su", "-c", command)

    private fun suArgv(command: String): List<String> = suArgv(mountMaster, command)

    /** Forget the cached availability (e.g. after the user grants/revokes superuser). */
    fun resetCache() {
        availableCache = null
    }

    /** Runs [script] via `su -c`, returning stdout. Throws [IOException] on non-zero exit. */
    @Throws(IOException::class)
    fun exec(script: String): String {
        val process = ProcessBuilder(suArgv(script)).start()
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
        val process = ProcessBuilder(suArgv("cat ${quote(path)}")).start()
        return RootInputStream(process)
    }

    /** Streams bytes into `cat > path` (creates/truncates). Caller must close to commit. */
    @Throws(IOException::class)
    fun openWrite(path: String): OutputStream {
        val process = ProcessBuilder(suArgv("cat > ${quote(path)}")).start()
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
