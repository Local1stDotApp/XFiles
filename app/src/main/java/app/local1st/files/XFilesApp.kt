package app.local1st.files

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.video.VideoFrameDecoder
import app.local1st.files.core.ops.OpsService
import app.local1st.files.core.thumb.AppIconFetcher
import app.local1st.files.di.Graph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class XFilesApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Graph.init(this)
        startOpsServiceWhenBusy()
    }

    /** Bring up the foreground service on the empty→busy edge so ops survive backgrounding. */
    private fun startOpsServiceWhenBusy() {
        Graph.appScope.launch {
            Graph.opEngine.active
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { busy -> if (busy) OpsService.start(this@XFilesApp) }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
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
