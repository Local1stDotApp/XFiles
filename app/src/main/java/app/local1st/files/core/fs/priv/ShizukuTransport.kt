package app.local1st.files.core.fs.priv

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import app.local1st.files.BuildConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Privileged transport backed by Shizuku's adb-shell uid. Calls block and must run on
 * Dispatchers.IO. Directory listings share one shell; streams and long commands do not.
 */
object ShizukuTransport : PrivilegedTransport {

    override val id: TransportId = TransportId.SHIZUKU
    override val supportsFileDescriptors: Boolean = true

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

    private const val SERVICE_BIND_TIMEOUT_SECONDS = 15L
    private val serviceLock = Any()

    @Volatile
    private var userService: IPrivFileService? = null
    private var userServiceBinder: IBinder? = null
    private var userServiceDeathRecipient: IBinder.DeathRecipient? = null
    private var bindingLatch: CountDownLatch? = null
    private var acceptingUserService = false

    // Every identity field is explicit and stable: daemon defaults to true, processNameSuffix is
    // mandatory, and a class-name-derived tag would change when R8 renames implementation types.
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, PrivFileService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("privfs")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("xfiles-privfs")
    }

    private val userServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IPrivFileService.Stub.asInterface(binder)
            val deathRecipient = IBinder.DeathRecipient { dropUserService(binder) }
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (_: RemoteException) {
                completeBindingWithoutService()
                return
            }

            val oldBinder: IBinder?
            val oldDeathRecipient: IBinder.DeathRecipient?
            val latch: CountDownLatch?
            val reject: Boolean
            synchronized(serviceLock) {
                reject = !acceptingUserService
                if (reject) {
                    oldBinder = null
                    oldDeathRecipient = null
                    latch = null
                } else {
                    oldBinder = userServiceBinder
                    oldDeathRecipient = userServiceDeathRecipient
                    userService = service
                    userServiceBinder = binder
                    userServiceDeathRecipient = deathRecipient
                    latch = bindingLatch
                    bindingLatch = null
                }
            }
            if (reject) {
                runCatching { binder.unlinkToDeath(deathRecipient, 0) }
                runCatching { service.destroy() }
                runCatching {
                    Shizuku.unbindUserService(userServiceArgs, userServiceConnection, false)
                }
                return
            }
            if (oldBinder != null && oldDeathRecipient != null && oldBinder !== binder) {
                runCatching { oldBinder.unlinkToDeath(oldDeathRecipient, 0) }
            }
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Shizuku dispatches this when the user-service binder dies.
            dropUserService()
        }

        override fun onBindingDied(name: ComponentName) {
            // Android may invalidate a binding without a normal disconnect callback.
            dropUserService()
            completeBindingWithoutService()
        }

        override fun onNullBinding(name: ComponentName) {
            // A null binding can never satisfy an fd request; let this call fall back promptly.
            dropUserService()
            completeBindingWithoutService()
        }
    }

    @Synchronized
    override fun reset() {
        closeShell()
        resetUserService()
    }

    @Throws(IOException::class)
    @Synchronized
    override fun exec(script: String): String {
        boundUserServiceOrNull()?.let { return execThroughService(it, script) }

        // A remote-process shell is retained only for devices where the user service cannot bind.
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
        boundUserServiceOrNull()?.let { return execThroughService(it, script) }
        return execOneShotWithRemoteProcess(script)
    }

    private fun execOneShotWithRemoteProcess(script: String): String {
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
        openFd(path, write = false)?.let { descriptor ->
            return try {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            } catch (e: Exception) {
                closeQuietly(descriptor)
                throw IOException("Cannot create Shizuku input stream", e)
            }
        }

        // `cat` is a compatibility fallback only when the user service cannot bind.
        return openReadWithRemoteProcess(path)
    }

    private fun openReadWithRemoteProcess(path: String): InputStream {
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
        openFd(path, write = true)?.let { descriptor ->
            return try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            } catch (e: Exception) {
                closeQuietly(descriptor)
                throw IOException("Cannot create Shizuku output stream", e)
            }
        }

        // `cat` is a compatibility fallback only when the user service cannot bind.
        return openWriteWithRemoteProcess(path)
    }

    private fun openWriteWithRemoteProcess(path: String): OutputStream {
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

    @Throws(IOException::class)
    override fun openFd(path: String, write: Boolean): ParcelFileDescriptor? {
        val service = boundUserServiceOrNull() ?: return null
        val mode = if (write) {
            ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return try {
            service.open(path, mode)
                ?: throw IOException("Privileged service returned no file descriptor")
        } catch (e: IllegalStateException) {
            throw IOException(e.message ?: "Privileged service could not open $path", e)
        } catch (e: RemoteException) {
            dropUserService(service.asBinder())
            throw IOException("Privileged file service disconnected while opening $path", e)
        } catch (e: RuntimeException) {
            if (!service.asBinder().isBinderAlive) dropUserService(service.asBinder())
            throw IOException("Cannot open $path through privileged file service", e)
        }
    }

    private fun execThroughService(service: IPrivFileService, script: String): String = try {
        service.exec(script)
    } catch (e: IllegalStateException) {
        throw IOException(e.message ?: "Privileged command failed", e)
    } catch (e: RemoteException) {
        // Replaying a command after a lost reply could duplicate a mutation, so only the next
        // call rebinds; remote-process fallback is used solely when binding failed up front.
        dropUserService(service.asBinder())
        throw IOException("Privileged file service disconnected during command", e)
    } catch (e: RuntimeException) {
        if (!service.asBinder().isBinderAlive) dropUserService(service.asBinder())
        throw IOException("Cannot execute command through privileged file service", e)
    }

    private fun boundUserServiceOrNull(): IPrivFileService? {
        userService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        if (!isAvailable()) return null

        val latch: CountDownLatch
        var startBinding = false
        synchronized(serviceLock) {
            userService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            latch = bindingLatch ?: CountDownLatch(1).also {
                bindingLatch = it
                acceptingUserService = true
                startBinding = true
            }
        }

        if (startBinding) {
            val started = runCatching {
                // This guarded call throws before Shizuku has delivered its own binder.
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            }.isSuccess
            if (!started) completeBindingWithoutService(latch)
        }

        // Shizuku posts ServiceConnection callbacks to the main looper. Never deadlock it: the
        // first main-thread call starts binding and temporarily uses the compatibility fallback.
        if (Looper.myLooper() == Looper.getMainLooper()) return userService

        val connected = try {
            latch.await(SERVICE_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!connected) completeBindingWithoutService(latch)
        return userService?.takeIf { it.asBinder().isBinderAlive }
    }

    private fun completeBindingWithoutService(expected: CountDownLatch? = null) {
        val latch: CountDownLatch?
        synchronized(serviceLock) {
            if (expected != null && bindingLatch !== expected) return
            latch = bindingLatch
            bindingLatch = null
        }
        latch?.countDown()
    }

    private fun dropUserService(expectedBinder: IBinder? = null) {
        val binder: IBinder?
        val deathRecipient: IBinder.DeathRecipient?
        val latch: CountDownLatch?
        synchronized(serviceLock) {
            if (expectedBinder != null && userServiceBinder !== expectedBinder) return
            binder = userServiceBinder
            deathRecipient = userServiceDeathRecipient
            userService = null
            userServiceBinder = null
            userServiceDeathRecipient = null
            acceptingUserService = false
            latch = bindingLatch
            bindingLatch = null
        }
        if (binder != null && deathRecipient != null) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        latch?.countDown()
    }

    private fun resetUserService() {
        val service: IPrivFileService?
        val wasBinding: Boolean
        synchronized(serviceLock) {
            service = userService
            // A timeout clears the waiter but deliberately still accepts a late connection.
            // Track that pending launch too so reset can ask Shizuku to remove it.
            wasBinding = acceptingUserService && userService == null
            acceptingUserService = false
        }
        dropUserService()

        // destroy is required because Shizuku unbind only releases this connection.
        runCatching { service?.destroy() }
        if (service == null && wasBinding) {
            // A timed-out launch may still be in flight; ask Shizuku to remove that process too.
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        }
        runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, false) }
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
