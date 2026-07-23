package app.local1st.files.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

object ExternalOpenResolver {
    fun resolve(context: Context, intent: Intent): Pair<ExternalOpenKind, XEntry> {
        if (intent.action != Intent.ACTION_VIEW) throw IOException("Unsupported external action")
        val uri = intent.data ?: throw IOException("No file was provided")
        val expectedKind = ExternalOpenRegistry.kindOf(intent.component)
            ?: throw IOException("Unknown external open target")
        val metadata = metadata(context, uri, intent.type)
        validate(expectedKind, metadata.name, metadata.mime)

        val entry = when (expectedKind) {
            ExternalOpenKind.ARCHIVE -> cacheArchive(context, uri, metadata)
            ExternalOpenKind.IMAGE,
            ExternalOpenKind.VIDEO,
            -> XEntry(
                id = uri.toString(),
                name = metadata.name,
                isDir = false,
                size = metadata.size,
                mime = metadata.mime,
                canWrite = false,
                kind = EntryKind.FILE,
                localPath = uri.takeIf { it.scheme == "file" }?.path,
            )
        }
        return expectedKind to entry
    }

    private data class Metadata(val name: String, val size: Long, val mime: String?)

    private fun metadata(context: Context, uri: Uri, explicitMime: String?): Metadata {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: throw IOException("Invalid file URI"))
            return Metadata(file.name, file.length(), explicitMime ?: FileTypes.mimeOf(file.name))
        }
        var name: String? = null
        var size = -1L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared-file"
        return Metadata(
            name = resolvedName,
            size = size,
            mime = explicitMime ?: context.contentResolver.getType(uri) ?: FileTypes.mimeOf(resolvedName),
        )
    }

    private fun validate(kind: ExternalOpenKind, name: String, mime: String?) {
        val accepted = when (kind) {
            ExternalOpenKind.ARCHIVE ->
                FileTypes.isSupportedArchive(name) || mime in ARCHIVE_MIME_TYPES_WITHOUT_OCTET_STREAM
            ExternalOpenKind.IMAGE -> mime?.startsWith("image/") == true ||
                FileTypes.categoryOf(name, mime) == FileCategory.IMAGE
            ExternalOpenKind.VIDEO -> mime?.startsWith("video/") == true ||
                FileTypes.categoryOf(name, mime) == FileCategory.VIDEO
        }
        if (!accepted) throw IOException("Unsupported file type: $name")
    }

    private fun cacheArchive(context: Context, uri: Uri, metadata: Metadata): XEntry {
        if (uri.scheme == "file" && FileTypes.isSupportedArchive(metadata.name)) {
            val path = uri.path ?: throw IOException("Invalid file URI")
            return XEntry(
                id = XId.file(path),
                name = metadata.name,
                isDir = false,
                size = File(path).length(),
                mime = metadata.mime,
                canWrite = false,
                kind = EntryKind.ARCHIVE,
                localPath = path,
            )
        }
        val cacheDir = File(context.cacheDir, CACHE_DIRECTORY)
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) throw IOException("Cannot create archive cache")
        cacheDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > CACHE_MAX_AGE_MILLIS }
            ?.forEach { it.delete() }
        val archiveName = if (FileTypes.isSupportedArchive(metadata.name)) {
            metadata.name
        } else {
            metadata.name + (extensionFor(metadata.mime) ?: throw IOException("Unsupported archive type"))
        }
        val safeName = archiveName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)
            .ifBlank { "archive.zip" }
        val cached = File(cacheDir, "${UUID.randomUUID()}-$safeName")
        try {
            val input = if (uri.scheme == "file") {
                File(uri.path ?: throw IOException("Invalid file URI")).inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
                ?: throw IOException("Cannot read ${metadata.name}")
            input.use { source -> FileOutputStream(cached).buffered().use(source::copyTo) }
        } catch (error: Exception) {
            cached.delete()
            throw error
        }
        return XEntry(
            id = XId.file(cached.absolutePath),
            name = metadata.name,
            isDir = false,
            size = cached.length(),
            mime = metadata.mime,
            canWrite = false,
            kind = EntryKind.ARCHIVE,
            localPath = cached.absolutePath,
        )
    }

    private val ARCHIVE_MIME_TYPES_WITHOUT_OCTET_STREAM = setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/java-archive",
        "application/vnd.android.package-archive",
        "application/x-7z-compressed",
        "application/vnd.rar",
        "application/x-rar-compressed",
        "application/x-tar",
        "application/x-gtar",
        "application/x-compressed-tar",
        "application/gzip",
        "application/x-gzip",
        "application/x-bzip2",
        "application/x-bzip-compressed-tar",
        "application/x-xz",
        "application/x-xz-compressed-tar",
    )

    private fun extensionFor(mime: String?): String? = when (mime) {
        "application/zip", "application/x-zip-compressed" -> ".zip"
        "application/java-archive" -> ".jar"
        "application/vnd.android.package-archive" -> ".apk"
        "application/x-7z-compressed" -> ".7z"
        "application/vnd.rar", "application/x-rar-compressed" -> ".rar"
        "application/x-tar", "application/x-gtar", "application/x-compressed-tar" -> ".tar"
        "application/gzip", "application/x-gzip" -> ".gz"
        "application/x-bzip2", "application/x-bzip-compressed-tar" -> ".bz2"
        "application/x-xz", "application/x-xz-compressed-tar" -> ".xz"
        else -> null
    }

    private const val CACHE_DIRECTORY = "external-archives"
    private const val CACHE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1_000
}
