package app.local1st.files.core.thumb

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.DisplayMetrics
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import app.local1st.files.core.util.FileTypes
import app.local1st.files.vendor.arsclib.arsc.chunk.xml.AndroidManifestBlock
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Coil model for the launcher icon inside an installable file on disk: an APK, or a split bundle
 * (.apks/.apkm/.xapk). [mtime]/[size] are part of the cache key, so a newer download saved over an
 * older one shows its own icon.
 */
data class ApkIcon(val path: String, val mtime: Long, val size: Long) {

    internal val cacheKey: String get() = "apk-icon:$path:$mtime:$size"

    companion object {
        /** Extensions whose icon PackageManager can read; an .aab's manifest is protobuf. */
        val extensions: Set<String> = setOf("apk") + FileTypes.apkBundleExtensions
    }
}

/**
 * Reads the launcher icon out of an installable file and keeps it in Coil's disk cache.
 *
 * An APK goes straight to PackageManager, the same parser the icon comes from once the app is
 * installed. A bundle is a zip of APKs. Most carry their icon beside them as icon.png (APKPure,
 * APKMirror and SAI all write one); for the rest, the base APK is copied out first, because the
 * parser takes a path, not a stream. That copy is the slow case the disk cache is mostly for.
 */
class ApkIconFetcher(
    private val context: Context,
    private val data: ApkIcon,
    private val imageLoader: ImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cache = imageLoader.diskCache
        when (val read = readCached(cache)) {
            is CacheRead.Hit -> return read.result
            CacheRead.Failed -> return null
            CacheRead.Miss -> {}
        }
        // Each parse maps the package and loads its resource table; a folder of APKs must not
        // start them all at once.
        return permits.withPermit {
            // The wait may have been for this very file, read for the other pane.
            when (val read = readCached(cache)) {
                is CacheRead.Hit -> return@withPermit read.result
                CacheRead.Failed -> return@withPermit null
                CacheRead.Miss -> {}
            }
            val icon = readIcon()
            writeCache(cache, icon)
            icon?.let {
                ImageFetchResult(
                    image = it.asImage(),
                    isSampled = false,
                    dataSource = DataSource.DISK,
                )
            }
        }
    }

    private sealed interface CacheRead {
        class Hit(val result: FetchResult) : CacheRead
        object Failed : CacheRead
        object Miss : CacheRead
    }

    private fun readCached(cache: DiskCache?): CacheRead {
        val snapshot = cache?.openSnapshot(data.cacheKey) ?: return CacheRead.Miss
        val meta = runCatching { cache.fileSystem.metadataOrNull(snapshot.data) }.getOrNull()
        if ((meta?.size ?: 0L) > 0L) {
            return CacheRead.Hit(
                SourceFetchResult(
                    source = ImageSource(
                        file = snapshot.data,
                        fileSystem = cache.fileSystem,
                        diskCacheKey = data.cacheKey,
                        closeable = snapshot,
                    ),
                    mimeType = "image/png",
                    dataSource = DataSource.DISK,
                ),
            )
        }
        snapshot.close()
        // An empty entry records a file with no icon to give. It expires: reading also fails for
        // reasons that pass (an I/O hiccup, memory pressure), and one retry an hour is cheap.
        val age = System.currentTimeMillis() - (meta?.lastModifiedAtMillis ?: 0L)
        if (age < NEGATIVE_TTL_MS) return CacheRead.Failed
        cache.remove(data.cacheKey)
        return CacheRead.Miss
    }

    /** Stores [icon], or an empty entry when there is none. */
    private fun writeCache(cache: DiskCache?, icon: Bitmap?) {
        val editor = cache?.openEditor(data.cacheKey) ?: return
        try {
            val written = icon == null || cache.fileSystem.write(editor.data) {
                icon.compress(Bitmap.CompressFormat.PNG, 100, outputStream())
            }
            // compress reports a full disk as `false`. That must leave no entry rather than an
            // empty one: freeing up space should be enough to get the icon back.
            if (written) editor.commit() else editor.abort()
        } catch (_: Exception) {
            runCatching { editor.abort() }
        }
    }

    private fun readIcon(): Bitmap? = try {
        val file = File(data.path)
        if (file.extension.equals("apk", ignoreCase = true)) packageIcon(file) else bundleIcon(file)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        // PackageManager decodes an icon at its full size. A package that declares an absurd one
        // costs its row the icon, not the app its process.
        null
    }

    private fun packageIcon(apk: File): Bitmap? {
        val pm = context.packageManager
        val app = archiveInfo(pm, apk.path)?.applicationInfo ?: return null
        // No icon declared: PackageManager would substitute the stock robot, and the row's own
        // glyph already says that.
        if (app.icon == 0) return null
        // An uninstalled package's resources are opened from these; older releases' parser
        // leaves them unset.
        app.sourceDir = apk.path
        app.publicSourceDir = apk.path
        // Read the densest variant whatever the screen, so the bitmap is as sharp as its size.
        val drawable = pm.getResourcesForApplication(app)
            .getDrawableForDensity(app.icon, DisplayMetrics.DENSITY_XXXHIGH, null)
            ?: return null
        // Drawn into a bitmap, an adaptive icon takes this device's icon shape, as on the launcher.
        return drawable.toBitmap(ICON_PX, ICON_PX)
    }

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)
        }

    private fun bundleIcon(bundle: File): Bitmap? = ZipFile(bundle).use { zip ->
        zip.getEntry(BUNDLE_ICON)?.let { decodeBundleIcon(zip, it) }
            ?: baseApkOf(zip)?.let { baseIcon(zip, it) }
    }

    /** The bundle's own icon.png, sampled on the way in: nothing bounds how big it is. */
    private fun decodeBundleIcon(zip: ZipFile, entry: ZipEntry): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= ICON_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = zip.getInputStream(entry)
            .use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val scale = ICON_PX.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        val scaled = decoded.scale(
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Copies [base] out of the bundle and reads its icon there. */
    private fun baseIcon(zip: ZipFile, base: ZipEntry): Bitmap? {
        // An icon is not worth a copy this big, nor filling up the disk.
        if (base.size !in 1..MAX_BASE_BYTES) return null
        val dir = File(context.cacheDir, WORK_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        sweepStale(dir)
        if (dir.usableSpace < base.size + FREE_SPACE_FLOOR) return null
        // A fresh name every time: resources are cached by path, and a reused one could hand back
        // the previous bundle's. Deleting the copy right after is safe; resources still open on it
        // keep the data until they are released.
        val copy = File.createTempFile("base", ".apk", dir)
        return try {
            zip.getInputStream(base).use { input -> copy.outputStream().use { input.copyTo(it) } }
            packageIcon(copy)
        } finally {
            copy.delete()
        }
    }

    /** Copies left behind by a process that died mid-copy; one in use is never this old. */
    private fun sweepStale(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_WORK_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    class Factory(private val context: Context) : Fetcher.Factory<ApkIcon> {
        override fun create(data: ApkIcon, options: Options, imageLoader: ImageLoader): Fetcher =
            ApkIconFetcher(context, data, imageLoader)
    }

    /** Without an explicit keyer a custom model has no memory-cache key at all. */
    class Key : Keyer<ApkIcon> {
        override fun key(data: ApkIcon, options: Options): String = data.cacheKey
    }

    companion object {
        /** A launcher icon's own size at xxxhdpi; every slot this one is drawn in is smaller. */
        private const val ICON_PX = 192
        private const val BUNDLE_ICON = "icon.png"
        private const val WORK_DIR = "apk_icon_work"
        private const val MAX_BASE_BYTES = 128L * 1024 * 1024
        private const val FREE_SPACE_FLOOR = 256L * 1024 * 1024
        private const val NEGATIVE_TTL_MS = 60L * 60 * 1000
        private const val STALE_WORK_MS = 10L * 60 * 1000

        /** bundletool's name for the base, then SAI's and APKMirror's. */
        private val BASE_NAMES = setOf("base-master.apk", "base.apk")

        private val permits = Semaphore(2)

        /**
         * The base APK of [zip], a split bundle. A lone APK is the base; past that, bundletool,
         * SAI and APKMirror name it, and APKPure names it after the package, so the last word
         * goes to the manifest: the base is the APK that names no split.
         */
        internal fun baseApkOf(zip: ZipFile): ZipEntry? {
            val apks = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .toList()
            return apks.singleOrNull()
                ?: apks.firstOrNull { it.name.substringAfterLast('/').lowercase() in BASE_NAMES }
                ?: apks.firstOrNull { apk ->
                    runCatching { manifestOf(zip, apk)?.isSplit == false }.getOrDefault(false)
                }
        }

        private fun manifestOf(zip: ZipFile, apk: ZipEntry): AndroidManifestBlock? =
            zip.getInputStream(apk).use { input ->
                ZipInputStream(input).use { inner ->
                    generateSequence { inner.nextEntry }
                        .firstOrNull { it.name == "AndroidManifest.xml" }
                        ?.let { AndroidManifestBlock.load(inner) }
                }
            }
    }
}
