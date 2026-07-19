package app.local1st.files.core.fs.priv

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Thin wrapper around the device `su` binary. [exec] runs commands through one
 * long-lived root shell: spawning `su` costs a full superuser-daemon round trip
 * (~1s measured), which used to be paid per directory listing. Streams
 * ([openRead]/[openWrite]) still use dedicated processes.
 * All calls block and must run on Dispatchers.IO.
 */
object SuTransport : PrivilegedTransport {

    override val id: TransportId = TransportId.SU

    override val caps: Caps = Caps(
        appPrivateData = true,
        wholeFilesystem = true,
        remount = true,
        otherUsers = true,
    )

    @Volatile
    private var availableCache: Boolean? = null

    // Prefer the global mount namespace: a su requested by an app inherits the app's restricted
    // namespace, so even as uid 0 it cannot reach other apps' /data/data. `--mount-master` runs
    // in the init/global namespace where the whole filesystem is visible. Detected once, since
    // not every su build supports the flag (fall back to plain su then).
    @Volatile
    private var mountMaster: Boolean = true

    /** Whether `su` exists and grants uid 0. Result is cached after the first probe. */
    override fun isAvailable(): Boolean {
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
    @Synchronized
    override fun reset() {
        availableCache = null
        closeShell()
    }

    // ---- persistent shell ----

    private var shell: Process? = null
    private var shellStdin: BufferedWriter? = null
    private var shellStdout: BufferedReader? = null

    /**
     * Runs [script] in the persistent root shell, returning stdout.
     * Throws [IOException] on non-zero exit. Serialized: commands from both panes
     * share the one shell, and a queued command waits ~ms, not an `su` spawn.
     * Anything unbounded (a recursive delete) must use [execOneShot] instead —
     * it would hold this lock and freeze all root browsing until it finishes.
     */
    @Throws(IOException::class)
    @Synchronized
    override fun exec(script: String): String {
        val (code, output) = try {
            runCommand(script)
        } catch (e: IOException) {
            // Transport failure (shell killed, su revoked mid-command): drop the shell
            // so the next call respawns. The command may or may not have run — same
            // uncertainty as a killed one-shot `su -c`.
            closeShell()
            throw e
        }
        if (code != 0) {
            throw IOException(output.trim().ifEmpty { "Root command failed (exit $code)" })
        }
        return output
    }

    /**
     * Runs [script] in its own `su -c` process. Slower (a fresh superuser round trip)
     * but independent: long mutations must not queue root listings behind them on the
     * persistent shell's lock.
     */
    @Throws(IOException::class)
    override fun execOneShot(script: String): String {
        val process = ProcessBuilder(suArgv(script)).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            throw IOException(stderr.trim().ifEmpty { "Root command failed (exit $code)" })
        }
        return stdout
    }

    private fun runCommand(script: String): Pair<Int, String> {
        val (stdin, stdout) = ensureShell()
        // Per-command unpredictable terminator: a fixed marker could be forged by
        // listing output (stat %n prints raw file-name bytes, including newlines),
        // which would desynchronize every later command on the shared shell.
        val mark = "__XF_DONE__" + java.util.UUID.randomUUID()
        // Subshell so an `exit` inside the script ends the command, not the shell;
        // `2>&1` keeps the command's error text readable for the non-zero-exit message.
        stdin.write("( ")
        stdin.write(script)
        stdin.write("\n) 2>&1\necho $mark \$?\n")
        stdin.flush()
        val output = StringBuilder()
        while (true) {
            val line = stdout.readLine() ?: throw IOException("Root shell terminated")
            if (line.startsWith(mark)) {
                val code = line.substring(mark.length).trim().toIntOrNull() ?: -1
                return code to output.toString()
            }
            output.append(line).append('\n')
        }
    }

    private fun ensureShell(): Pair<BufferedWriter, BufferedReader> {
        shell?.takeIf { it.isAlive }?.let { return shellStdin!! to shellStdout!! }
        closeShell()
        val argv = if (mountMaster) listOf("su", "--mount-master") else listOf("su")
        val process = ProcessBuilder(argv)
            // Command stderr is folded into stdout per command; anything the shell itself
            // prints on stderr must not fill an unread pipe and deadlock it.
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            .start()
        shell = process
        shellStdin = process.outputStream.bufferedWriter()
        shellStdout = process.inputStream.bufferedReader()
        return shellStdin!! to shellStdout!!
    }

    private fun closeShell() {
        runCatching { shellStdin?.close() }
        runCatching { shellStdout?.close() }
        shell?.destroy()
        shell = null
        shellStdin = null
        shellStdout = null
    }

    /** Streams the bytes of [path] out of `cat`. Caller must close the stream. */
    @Throws(IOException::class)
    override fun openRead(path: String): InputStream {
        val process = ProcessBuilder(suArgv("cat ${shQuote(path)}")).start()
        return RootInputStream(process)
    }

    /** Streams bytes into `cat > path` (creates/truncates). Caller must close to commit. */
    @Throws(IOException::class)
    override fun openWrite(path: String): OutputStream {
        val process = ProcessBuilder(suArgv("cat > ${shQuote(path)}")).start()
        return RootOutputStream(process)
    }

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
