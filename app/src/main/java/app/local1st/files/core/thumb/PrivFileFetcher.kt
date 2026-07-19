package app.local1st.files.core.thumb

import android.os.ParcelFileDescriptor
import app.local1st.files.core.fs.priv.PrivilegedAccess
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.IOException
import okio.FileSystem
import okio.buffer
import okio.source

/** Privileged file model whose identity changes when the source is overwritten in place. */
data class PrivFile(val path: String, val mtime: Long, val size: Long)

/** Supplies Coil directly from the binder-opened fd; the ImageSource owns its lifetime. */
class PrivFileFetcher(private val data: PrivFile) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val transport = PrivilegedAccess.active
            ?.takeIf { PrivilegedAccess.enabled && it.supportsFileDescriptors }
            ?: return null
        val descriptor = transport.openFd(data.path, write = false) ?: return null
        return try {
            val source = ParcelFileDescriptor.AutoCloseInputStream(descriptor).source().buffer()
            SourceFetchResult(
                source = ImageSource(source, FileSystem.SYSTEM),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (e: Throwable) {
            runCatching { descriptor.close() }
            if (e is Error) throw e
            throw IOException("Cannot read ${data.path} through privileged access", e)
        }
    }

    class Factory : Fetcher.Factory<PrivFile> {
        override fun create(data: PrivFile, options: Options, imageLoader: ImageLoader): Fetcher =
            PrivFileFetcher(data)
    }

    /** Custom models need an explicit key; mtime/size preserve local-file invalidation semantics. */
    class Key : Keyer<PrivFile> {
        override fun key(data: PrivFile, options: Options): String =
            "priv-file:${data.path}:${data.mtime}:${data.size}"
    }
}
