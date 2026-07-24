package app.local1st.files

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.addLastModifiedToFileCacheKey
import coil3.video.VideoFrameDecoder
import app.local1st.files.core.ops.BackgroundJobs
import app.local1st.files.core.ops.OpsService
import app.local1st.files.core.thumb.AppIconFetcher
import app.local1st.files.core.thumb.PrivFileFetcher
import app.local1st.files.core.thumb.VideoThumbFetcher
import app.local1st.files.di.Graph
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class XFilesApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Graph.init(this)
        startOpsServiceWhenBusy()
    }

    /** Bring up the foreground service on the empty→busy edge so work survives backgrounding. */
    private fun startOpsServiceWhenBusy() {
        Graph.appScope.launch {
            combine(Graph.opEngine.active, BackgroundJobs.active) { ops, jobs ->
                ops.isNotEmpty() || jobs.isNotEmpty()
            }
                .distinctUntilChanged()
                .collect { busy -> if (busy) OpsService.start(this@XFilesApp) }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // File-model requests (image thumbnails, the viewer) key their memory-cache
            // entries by path+mtime, so overwriting a file in place drops the stale image.
            .addLastModifiedToFileCacheKey(true)
            .components {
                add(VideoFrameDecoder.Factory())
                add(VideoThumbFetcher.Factory(this@XFilesApp))
                add(VideoThumbFetcher.Key())
                add(PrivFileFetcher.Factory())
                add(PrivFileFetcher.Key())
                add(AppIconFetcher.Factory(this@XFilesApp))
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                }
            }
            .build()

    companion object {
        lateinit var instance: XFilesApp
            private set
    }
}
