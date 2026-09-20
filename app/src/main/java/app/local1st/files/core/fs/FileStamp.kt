package app.local1st.files.core.fs

import java.io.File
import java.io.IOException

/**
 * Size and modification time of a file at one moment. What was read under one stamp may only be
 * written back while the file still carries it: a cached row table is keyed to one, and a save
 * checks one before splicing, so an edit made against bytes another writer has since replaced
 * fails instead of landing on the wrong offsets.
 */
data class FileStamp(val size: Long, val mtime: Long) {
    /** True when [file] still has this size and modification time. */
    fun matches(file: File): Boolean = file.length() == size && file.lastModified() == mtime

    companion object {
        fun of(file: File): FileStamp = FileStamp(file.length(), file.lastModified())
    }
}

/** Bytes `[from, to)` of a file as it was read, to be replaced by [bytes] when it is saved. */
class ByteRangeEdit(val from: Long, val to: Long, val bytes: ByteArray)

/** The file no longer carries the stamp the edits were made under, so they were not written. */
class FileChangedException(name: String) : IOException("$name was changed by another app")
