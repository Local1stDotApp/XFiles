package app.local1st.files.core.util

import android.content.Context
import android.os.Build
import android.os.Environment
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.fs.priv.shQuote
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONObject

object XapkObbInstaller {

    open class ObbPlacementException(message: String, cause: Throwable? = null) : IOException(message, cause)

    class UnknownSourcesPermissionException(message: String) : ObbPlacementException(message)

    class ObbEntry internal constructor(
        internal val source: ZipEntry,
        internal val relativeDestination: String,
    )

    class Placement internal constructor(
        private val writtenFiles: List<File>,
        private val backups: List<Pair<File, File>>,
    ) {
        fun cleanUp() {
            writtenFiles.forEach { file ->
                if (!file.delete() && PrivilegedAccess.usable()) {
                    runCatching { PrivilegedAccess.active?.exec("rm -f -- ${shQuote(file.path)}") }
                }
            }
            backups.forEach { (destination, backup) ->
                val restored = runCatching {
                    Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.isSuccess
                if (!restored && XapkObbInstaller.rootWritable()) {
                    runCatching {
                        PrivilegedAccess.active?.exec(
                            "mv -f -- ${shQuote(backup.path)} ${shQuote(destination.path)}",
                        )
                    }
                }
            }
        }

        fun commit() {
            backups.forEach { (_, backup) ->
                if (!backup.delete() && XapkObbInstaller.rootWritable()) {
                    runCatching { PrivilegedAccess.active?.exec("rm -f -- ${shQuote(backup.path)}") }
                }
            }
        }
    }

    fun findObbs(zip: ZipFile): List<ObbEntry> {
        val byName = buildMap<String, ZipEntry> {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) put(normalizeZipPath(entry.name).lowercase(Locale.ROOT), entry)
            }
        }
        val packageName = installedPackageName(zip, byName.values)
        val fromManifest: List<ObbEntry>? = runCatching<List<ObbEntry>?> {
            val manifest = byName.entries.firstOrNull {
                !it.key.contains('/') && it.key.equals("manifest.json", ignoreCase = true)
            }?.value ?: return@runCatching null
            val json = zip.getInputStream(manifest).bufferedReader().use { JSONObject(it.readText()) }
            json.optString("package_name").takeIf { it.isNotBlank() }?.let { declared ->
                if (declared != packageName) {
                    throw ObbPlacementException(
                        "XAPK manifest package $declared does not match base APK package $packageName",
                    )
                }
            }
            val expansions = json.optJSONArray("expansions") ?: return@runCatching null
            buildList<ObbEntry> {
                for (i in 0 until expansions.length()) {
                    val item = expansions.optJSONObject(i)
                        ?: throw ObbPlacementException("Invalid XAPK expansion at index $i")
                    val sourcePath = normalizeZipPath(item.optString("file"))
                    val source = byName[sourcePath.lowercase(Locale.ROOT)]
                    val destination = listOf("install_path", "install_location")
                        .asSequence()
                        .map { item.optString(it) }
                        .mapNotNull(::obbRelativePath)
                        .firstOrNull()
                        ?: obbRelativePath(sourcePath)
                        ?: throw ObbPlacementException("Cannot map XAPK expansion '$sourcePath' to Android/obb")
                    requireDestinationPackage(destination, packageName)
                    if (source == null) {
                        val file = File(Environment.getExternalStorageDirectory(), destination)
                        if (!destinationExists(file)) {
                            throw ObbPlacementException("XAPK expansion '$sourcePath' is missing")
                        }
                        continue
                    }
                    add(ObbEntry(source, destination))
                }
            }
        }.getOrElse { error ->
            if (error is ObbPlacementException) throw error
            throw ObbPlacementException("Cannot read XAPK manifest: ${error.message ?: "invalid JSON"}", error)
        }
        if (fromManifest != null) return fromManifest.distinctBy { it.relativeDestination }

        return byName.mapNotNull { (path, entry) ->
            obbRelativePath(path)?.also { requireDestinationPackage(it, packageName) }
                ?.let { ObbEntry(entry, it) }
        }.distinctBy { it.relativeDestination }
    }

    fun place(
        context: Context,
        zip: ZipFile,
        obbs: List<ObbEntry>,
        progress: InstallProgress = InstallProgress(),
    ): Placement {
        if (obbs.isNotEmpty() && !canRequestPackageInstalls(context) && !rootWritable()) {
            throw UnknownSourcesPermissionException(
                "Enable ‘Install unknown apps’ for XFiles, then retry. The permission cache may also require an XFiles restart.",
            )
        }
        if (obbs.isNotEmpty()) progress.onPhase(InstallPhase.EXTRACTING_OBB)
        val externalRoot = Environment.getExternalStorageDirectory().canonicalFile
        val written = mutableListOf<File>()
        val backups = mutableListOf<Pair<File, File>>()
        // Expansion files run to gigabytes, so report one bar across the whole set. A retried
        // file restarts from the offset it began at, keeping the count monotonic.
        val totalBytes = obbs.sumOf { it.source.size.coerceAtLeast(0) }
        var doneBytes = 0L
        try {
            obbs.forEach { obb ->
                val startedAtBytes = doneBytes
                doneBytes += obb.source.size.coerceAtLeast(0)
                val destination = File(externalRoot, obb.relativeDestination).canonicalFile
                if (!destination.path.startsWith(externalRoot.path + File.separator)) {
                    throw ObbPlacementException("Invalid OBB install path")
                }
                val existingSize = destinationSize(destination)
                if (existingSize == obb.source.size && existingSize >= 0) {
                    progress.onBytes(doneBytes, totalBytes)
                    return@forEach
                }
                if (existingSize >= 0) {
                    backups += destination to backUp(destination)
                }
                var directFailure: Exception? = null
                zip.getInputStream(obb.source).use { input ->
                    try {
                        writeDirect(input, destination, progress, startedAtBytes, totalBytes)
                        written += destination
                        return@forEach
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        directFailure = e
                    }
                }
                if (!rootWritable()) {
                    val cause = directFailure?.message ?: directFailure?.javaClass?.simpleName ?: "write failed"
                    throw ObbPlacementException(
                        if (canRequestPackageInstalls(context)) {
                            "Couldn't write ${destination.name}: $cause. The ‘Install unknown apps’ grant may be too recent; restart XFiles and retry."
                        } else {
                            "Couldn't write ${destination.name}: $cause. Enable ‘Install unknown apps’ for XFiles, restart XFiles if needed, then retry."
                        },
                        directFailure,
                    )
                }
                val wrote = zip.getInputStream(obb.source).use { input ->
                    writeWithRoot(input, destination, progress, startedAtBytes, totalBytes)
                }
                if (!wrote) {
                    throw ObbPlacementException("Root failed to place ${destination.name}", directFailure)
                }
                written += destination
            }
            return Placement(written, backups)
        } catch (e: Exception) {
            Placement(written, backups).cleanUp()
            if (e is CancellationException) throw e
            if (e is ObbPlacementException) throw e
            throw ObbPlacementException("Couldn't place OBB: ${e.message ?: "write failed"}", e)
        }
    }

    private fun writeDirect(
        input: java.io.InputStream,
        destination: File,
        progress: InstallProgress,
        doneBytes: Long,
        totalBytes: Long,
    ) {
        val parent = destination.parentFile ?: throw IOException("Invalid OBB destination")
        val temp = File(parent, ".${destination.name}.xfiles-${UUID.randomUUID()}.part")
        try {
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create ${parent.path}")
            FileOutputStream(temp).use { output ->
                copyWithProgress(input, output, progress, doneBytes, totalBytes)
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun backUp(destination: File): File {
        val parent = destination.parentFile ?: throw ObbPlacementException("Invalid OBB destination")
        val backup = File(parent, ".${destination.name}.xfiles-backup-${UUID.randomUUID()}")
        val directFailure = runCatching {
            Files.move(destination.toPath(), backup.toPath())
        }.exceptionOrNull()
        if (directFailure == null) return backup
        if (!rootWritable()) {
            throw ObbPlacementException("Couldn't back up ${destination.name}", directFailure)
        }
        val transport = PrivilegedAccess.active
            ?: throw ObbPlacementException("Couldn't back up ${destination.name}", directFailure)
        try {
            transport.exec(
                "mv -f -- ${shQuote(destination.path)} ${shQuote(backup.path)}",
            )
            return backup
        } catch (e: Exception) {
            throw ObbPlacementException("Couldn't back up ${destination.name}", e)
        }
    }

    private fun destinationSize(file: File): Long {
        if (file.isFile) return file.length()
        if (!rootWritable()) return -1
        val transport = PrivilegedAccess.active ?: return -1
        return transport.exec("stat -c %s -- ${shQuote(file.path)} 2>/dev/null || echo -1")
            .trim().lineSequence().lastOrNull()?.toLongOrNull() ?: -1
    }

    private fun destinationExists(file: File): Boolean = destinationSize(file) >= 0

    private fun writeWithRoot(
        input: java.io.InputStream,
        destination: File,
        progress: InstallProgress,
        doneBytes: Long,
        totalBytes: Long,
    ): Boolean {
        val parent = destination.parentFile ?: throw IOException("Invalid OBB destination")
        val temp = File(parent, ".${destination.name}.xfiles-${UUID.randomUUID()}.part")
        val transport = PrivilegedAccess.active ?: throw IOException("Root access is unavailable")
        val quotedParent = shQuote(parent.path)
        val quotedTemp = shQuote(temp.path)
        val quotedDestination = shQuote(destination.path)
        transport.exec("mkdir -p -- $quotedParent && chmod 0755 $quotedParent")
        try {
            transport.openWrite(temp.path).use { output ->
                copyWithProgress(input, output, progress, doneBytes, totalBytes)
            }
            val result = transport.exec(
                "chmod 0644 $quotedTemp && mv -f -- $quotedTemp $quotedDestination && " +
                    "chmod 0644 $quotedDestination && echo wrote",
            )
            return result.trim() == "wrote"
        } catch (e: Exception) {
            runCatching { transport.exec("rm -f -- $quotedTemp") }
            throw e
        }
    }

    private fun rootWritable(): Boolean =
        PrivilegedAccess.enabled && !PrivilegedAccess.readOnly && PrivilegedAccess.active != null

    private fun normalizeZipPath(path: String): String = path.replace('\\', '/').trimStart('/')

    private fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun installedPackageName(zip: ZipFile, entries: Collection<ZipEntry>): String {
        val basePackages = entries.asSequence()
            .filter { it.name.substringAfterLast('/').endsWith(".apk", ignoreCase = true) }
            .mapNotNull { apk ->
                runCatching {
                    zip.getInputStream(apk).use { apkInput ->
                        ZipInputStream(apkInput).use { inner ->
                            generateSequence { inner.nextEntry }
                                .firstOrNull { normalizeZipPath(it.name) == "AndroidManifest.xml" }
                                ?.let { AndroidManifestBlock.load(inner) }
                        }
                    }
                }.getOrNull()
            }
            .filter { !it.isSplit }
            .mapNotNull { it.packageName?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()
        if (basePackages.size != 1) {
            throw ObbPlacementException("XAPK must contain exactly one readable base APK")
        }
        return basePackages.single()
    }

    private fun requireDestinationPackage(destination: String, packageName: String) {
        val destinationPackage = normalizeZipPath(destination).split('/').getOrNull(2)
        if (destinationPackage != packageName) {
            throw ObbPlacementException(
                "OBB package '${destinationPackage ?: "missing"}' does not match base APK package $packageName",
            )
        }
    }

    private fun obbRelativePath(path: String): String? {
        val normalized = normalizeZipPath(path)
        val marker = "android/obb/"
        val start = normalized.lowercase().indexOf(marker)
        if (start < 0) return null
        val suffix = normalized.substring(start + marker.length)
        if (suffix.isBlank() || suffix.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        return "Android/obb/$suffix"
    }
}
