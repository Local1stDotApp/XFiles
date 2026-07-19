package app.local1st.files.core.fs.priv

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Privileged transport backed by Shizuku's adb-shell uid. Calls block and must run on
 * Dispatchers.IO. Directory listings share one shell; streams and long commands do not.
 */
object ShizukuTransport : PrivilegedTransport {

    override val id: TransportId = TransportId.SHIZUKU

    // uid 2000 has the supplementary GIDs needed for Android/data and Android/obb, but the
    // shell SELinux domain is denied on /data/data and /data/media. It also cannot remount
    // filesystems or read another Android user's storage. These false values are safety gates.
    override val caps: Caps = Caps(
        appPrivateData = false,
        wholeFilesystem = false,
        remount = false,
        otherUsers = false,
    )

    /** True only while a supported binder is alive and this app has Shizuku permission. */
    override fun isAvailable(): Boolean = runCatching {
        // Every Shizuku call is inside this guard: access before binder delivery throws
        // IllegalStateException rather than returning an ordinary unavailable result.
        val binder = Shizuku.getBinder()
        binder != null &&
            binder.isBinderAlive &&
            Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private var shell: RemoteShell? = null

    @Synchronized
    override fun reset() {
        closeShell()
    }

    @Throws(IOException::class)
    @Synchronized
    override fun exec(script: String): String {
        val (code, output) = try {
            runCommand(script)
        } catch (e: IOException) {
            // The binder or remote shell may have died after availability was checked. Forget
            // every handle so the next command creates a shell against the current binder.
            closeShell()
            throw e
        }
        if (code != 0) {
            throw IOException(output.trim().ifEmpty { "Shizuku command failed (exit $code)" })
        }
        return output
    }

    @Throws(IOException::class)
    override fun execOneShot(script: String): String {
        val process = spawn(arrayOf("sh", "-c", script))
        return try {
            closeQuietly(remoteOutput(process))
            val stdout = remoteInput(process)
            val stderrDrain = ErrorDrain(remoteError(process))
            val output = stdout.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            val error = stderrDrain.finish()
            if (code != 0) {
                throw IOException(error.trim().ifEmpty {
                    "Shizuku command failed (exit $code)"
                })
            }
            output
        } catch (e: IOException) {
            destroyQuietly(process)
            throw e
        } catch (e: Exception) {
            destroyQuietly(process)
            throw IOException("Shizuku command failed", e)
        }
    }

    private fun runCommand(script: String): Pair<Int, String> {
        val current = ensureShell()
        // stat %n preserves raw filename bytes, including newlines. A fixed delimiter could be
        // forged by a filename and desynchronize the shared shell, so every command gets a UUID.
        val mark = "__XF_DONE__" + UUID.randomUUID()
        current.stdin.write("( ")
        current.stdin.write(script)
        current.stdin.write("\n) 2>&1\necho $mark \$?\n")
        current.stdin.flush()

        val output = StringBuilder()
        while (true) {
            val line = current.stdout.readLine()
                ?: throw IOException("Shizuku shell terminated")
            if (line.startsWith(mark)) {
                val code = line.substring(mark.length).trim().toIntOrNull() ?: -1
                return code to output.toString()
            }
            output.append(line).append('\n')
        }
    }

    private fun ensureShell(): RemoteShell {
        // Liveness is checked up front, as SuTransport does with Process.isAlive: the remote
        // shell can be killed (low memory, service restart) while the binder stays alive, and
        // writing into its dead pipe would surface one spurious error before the retry heals it.
        shell?.takeIf { runCatching { it.process.alive() }.getOrDefault(false) }?.let { return it }
        closeShell()
        val process = spawn(arrayOf("sh"))
        return try {
            RemoteShell(
                process = process,
                stdin = remoteOutput(process).bufferedWriter(),
                stdout = remoteInput(process).bufferedReader(),
                stderrDrain = ErrorDrain(remoteError(process)),
            ).also { shell = it }
        } catch (e: Exception) {
            destroyQuietly(process)
            throw if (e is IOException) e else IOException("Cannot open Shizuku shell", e)
        }
    }

    private fun closeShell() {
        val current = shell
        shell = null
        if (current == null) return
        closeQuietly(current.stdin)
        closeQuietly(current.stdout)
        destroyQuietly(current.process)
        current.stderrDrain.close()
    }

    /** Streams bytes from a dedicated remote `cat`; closing also releases its binder process. */
    @Throws(IOException::class)
    override fun openRead(path: String): InputStream {
        val process = spawn(arrayOf("sh", "-c", "exec cat ${shQuote(path)}"))
        return try {
            closeQuietly(remoteOutput(process))
            ShizukuInputStream(
                delegate = remoteInput(process),
                process = process,
                stderrDrain = ErrorDrain(remoteError(process)),
            )
        } catch (e: Exception) {
            destroyQuietly(process)
            throw if (e is IOException) e else IOException("Cannot read through Shizuku", e)
        }
    }

    /** Streams bytes into a dedicated remote `cat`; close waits so write failures are visible. */
    @Throws(IOException::class)
    override fun openWrite(path: String): OutputStream {
        val process = spawn(arrayOf("sh", "-c", "exec cat > ${shQuote(path)}"))
        return try {
            closeQuietly(remoteInput(process))
            ShizukuOutputStream(
                delegate = remoteOutput(process),
                process = process,
                stderrDrain = ErrorDrain(remoteError(process)),
            )
        } catch (e: Exception) {
            destroyQuietly(process)
            throw if (e is IOException) e else IOException("Cannot write through Shizuku", e)
        }
    }

    private fun spawn(argv: Array<String>): IRemoteProcess {
        if (!isAvailable()) throw IOException("Shizuku is unavailable or permission is missing")
        return try {
            // Shizuku.newProcess is private in 13.1.5; :api exposes this compiled AIDL proxy
            // through its transitive :aidl dependency, whose transaction remains supported.
            val binder = Shizuku.getBinder()
                ?: throw IOException("Shizuku binder is unavailable")
            if (!binder.isBinderAlive) throw IOException("Shizuku binder is unavailable")
            val service = IShizukuService.Stub.asInterface(binder)
                ?: throw IOException("Shizuku service is unavailable")
            service.newProcess(argv, null, null)
                ?: throw IOException("Shizuku did not create a process")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Cannot create Shizuku process", e)
        }
    }

    private fun remoteInput(process: IRemoteProcess): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(
            process.inputStream ?: throw IOException("Shizuku process has no stdout"),
        )

    private fun remoteOutput(process: IRemoteProcess): OutputStream =
        ParcelFileDescriptor.AutoCloseOutputStream(
            process.outputStream ?: throw IOException("Shizuku process has no stdin"),
        )

    private fun remoteError(process: IRemoteProcess): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(
            process.errorStream ?: throw IOException("Shizuku process has no stderr"),
        )

    private data class RemoteShell(
        val process: IRemoteProcess,
        val stdin: BufferedWriter,
        val stdout: BufferedReader,
        val stderrDrain: ErrorDrain,
    )

    private class ErrorDrain(private val input: InputStream) {
        private val bytes = ByteArrayOutputStream()
        private val thread = Thread({
            runCatching { input.use { it.copyTo(bytes) } }
        }, "XFiles-Shizuku-stderr").apply {
            isDaemon = true
            start()
        }

        fun finish(): String {
            thread.join()
            return bytes.toString(Charsets.UTF_8.name())
        }

        fun close() {
            closeQuietly(input)
            thread.interrupt()
        }
    }

    private class ShizukuInputStream(
        private val delegate: InputStream,
        private val process: IRemoteProcess,
        private val stderrDrain: ErrorDrain,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            closeQuietly(delegate)
            destroyQuietly(process)
            stderrDrain.close()
        }
    }

    private class ShizukuOutputStream(
        private val delegate: OutputStream,
        private val process: IRemoteProcess,
        private val stderrDrain: ErrorDrain,
    ) : OutputStream() {
        private var closed = false

        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            try {
                delegate.close()
                val code = process.waitFor()
                val error = stderrDrain.finish()
                if (code != 0) {
                    throw IOException(error.trim().ifEmpty {
                        "Shizuku write failed (exit $code)"
                    })
                }
            } catch (e: IOException) {
                destroyQuietly(process)
                throw e
            } catch (e: Exception) {
                destroyQuietly(process)
                throw IOException("Shizuku write failed", e)
            }
        }
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        runCatching { closeable?.close() }
    }

    private fun destroyQuietly(process: IRemoteProcess?) {
        runCatching { process?.destroy() }
    }
}
