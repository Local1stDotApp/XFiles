package com.xfiles

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.video.VideoFrameDecoder
import com.xfiles.core.thumb.AppIconFetcher
import com.xfiles.di.Graph

class XFilesApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Graph.init(this)
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
