package app.local1st.files.ui.viewer

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/** Seekable media source for root:// paths backed by a fresh privileged fd per open. */
@UnstableApi
class PrivilegedDataSource : BaseDataSource(false) {

    private var uri: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var input: InputStream? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        check(descriptor == null) { "DataSource is already open" }
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            if (dataSpec.uri.scheme != XId.SCHEME_ROOT) {
                throw IOException("Unsupported privileged URI: ${dataSpec.uri}")
            }
            val path = dataSpec.uri.path ?: throw IOException("Privileged URI has no path")
            val transport = PrivilegedAccess.active
                ?.takeIf { PrivilegedAccess.enabled && it.supportsFileDescriptors }
                ?: throw IOException("Privileged file-descriptor access is unavailable")
            val openedDescriptor = transport.openFd(path, write = false)
                ?: throw IOException("Privileged transport cannot open file descriptors")
            descriptor = openedDescriptor

            val size = openedDescriptor.statSize
            if (size >= 0 && dataSpec.position > size) {
                throw DataSourceException(
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                )
            }
            try {
                // skip() may degrade to reading; lseek keeps large ExoPlayer range requests O(1).
                Os.lseek(openedDescriptor.fileDescriptor, dataSpec.position, OsConstants.SEEK_SET)
            } catch (e: ErrnoException) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }

            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                size >= 0 -> size - dataSpec.position
                else -> C.LENGTH_UNSET.toLong()
            }
            input = ParcelFileDescriptor.AutoCloseInputStream(openedDescriptor)
            transferStarted(dataSpec)
            opened = true
            return bytesRemaining
        } catch (e: Throwable) {
            closeResources()
            uri = null
            throw when (e) {
                is IOException -> e
                is Error -> e
                else -> DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(bytesRemaining, length.toLong()).toInt()
        }
        val count = try {
            checkNotNull(input).read(buffer, offset, toRead)
        } catch (e: Throwable) {
            closeAfterReadFailure(e)
        }
        if (count < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        var failure: Throwable? = null
        try {
            input?.close()
        } catch (e: Throwable) {
            failure = e
        }
        try {
            // AutoCloseInputStream owns the PFD, but this also covers partial construction.
            descriptor?.close()
        } catch (e: Throwable) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
        input = null
        descriptor = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
        if (opened) {
            opened = false
            try {
                transferEnded()
            } catch (e: Throwable) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let {
            throw if (it is IOException) {
                DataSourceException(it, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            } else {
                it
            }
        }
    }

    /** Closes an fd acquired during a failed open without emitting an unmatched transfer end. */
    private fun closeResources() {
        runCatching { input?.close() }
        runCatching { descriptor?.close() }
        input = null
        descriptor = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
        opened = false
    }

    private fun closeAfterReadFailure(cause: Throwable): Nothing {
        val failure = if (cause is IOException) {
            DataSourceException(cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } else {
            cause
        }
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = PrivilegedDataSource()
    }
}
