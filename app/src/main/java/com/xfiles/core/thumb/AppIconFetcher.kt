package com.xfiles.core.thumb

import android.content.Context
import android.content.pm.PackageManager
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options

/**
 * Coil model for an installed app's launcher icon.
 * Use as `AsyncImage(model = AppIcon(entry.path), ...)` for `apps://` entries.
 */
data class AppIcon(val packageName: String)

/**
 * Coil3 fetcher resolving [AppIcon] to the app's icon drawable via [PackageManager].
 * Register with `ImageLoader.Builder.components { add(AppIconFetcher.Factory(context)) }`.
 */
class AppIconFetcher(
    private val context: Context,
    private val data: AppIcon,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val drawable = try {
            context.packageManager.getApplicationIcon(data.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null // app uninstalled since the entry was listed; let the request error out
        }
        return ImageFetchResult(
            image = drawable.asImage(),
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<AppIcon> {
        override fun create(data: AppIcon, options: Options, imageLoader: ImageLoader): Fetcher =
            AppIconFetcher(context, data)
    }
}
