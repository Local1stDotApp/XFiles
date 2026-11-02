package app.local1st.files.core.search

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * MediaStore's files table as a [FileNameIndex].
 *
 * From Android 11 the FUSE daemon that serves shared storage is MediaProvider itself, and it
 * writes the table as files are created, renamed and deleted, so the names stay current. With
 * all-files access the table also lists non-media files. It has no rows for Android/data or
 * Android/obb, nor for what is inside an archive.
 */
@RequiresApi(Build.VERSION_CODES.R)
class MediaStoreFileIndex(private val context: Context) : FileNameIndex {

    override fun coveredRoots(volumeRoots: List<String>): Set<String> {
        val storage = context.getSystemService(StorageManager::class.java) ?: return emptySet()
        // A USB drive is often mounted without being attached to MediaStore. It gets walked.
        val attached = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        return volumeRoots.filterTo(HashSet()) { root ->
            val name = runCatching { storage.getStorageVolume(File(root))?.mediaStoreVolumeName }.getOrNull()
            name != null && name in attached
        }
    }

    override fun matchingPaths(query: String, roots: List<String>, limit: Int): List<String> {
        if (roots.isEmpty() || limit <= 0) return emptyList()
        val selection = MediaStoreQuery.selection(query, roots)
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.sql)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selection.args.toTypedArray())
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            // Files still being written, and those in MediaStore's own trash, are still on disk.
            // A walk finds them, so the index should as well.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        val paths = ArrayList<String>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStoreQuery.PATH),
            args,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(paths::add)
        }
        return paths
    }

    /**
     * A folder gets a row when a file is created in it or a scan reaches it, so a new empty one
     * has none. Scanning it adds the row. The scan runs on a background thread.
     */
    override fun addFolder(path: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
}
