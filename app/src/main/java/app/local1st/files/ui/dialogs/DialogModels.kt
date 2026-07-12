package app.local1st.files.ui.dialogs

import app.local1st.files.core.fs.XEntry

/** Modal UI requested by MainViewModel; rendered by MainDialogs (ui/dialogs/Dialogs.kt). */
sealed interface DialogRequest {
    data class ConfirmDelete(val entries: List<XEntry>) : DialogRequest
    data class Rename(val entry: XEntry) : DialogRequest
    data class NewFolder(val parent: XEntry) : DialogRequest
    data class CompressTo(val sources: List<XEntry>, val destDir: XEntry) : DialogRequest
    data class Details(val entry: XEntry) : DialogRequest
    data object SortOptions : DialogRequest

    /** Long-press context menu for one entry (bottom sheet). */
    data class EntryMenu(val entry: XEntry) : DialogRequest
}
