package com.xfiles.core.fs

/** A storage volume shown as a pane root. */
data class Volume(
    val entry: XEntry,
    val label: String,
    val totalBytes: Long,
    val freeBytes: Long,
)

/** Provides the roots each pane starts with. */
interface RootsRepository {
    /** Storage volumes (internal, SD, USB OTG) currently mounted. */
    fun volumes(): List<Volume>

    /** Full root list for a pane: volumes + special roots (app manager, ...). */
    fun paneRoots(): List<XEntry>
}
