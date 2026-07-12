package app.local1st.files.ui.viewer

import app.local1st.files.core.fs.XEntry

/** Full-screen viewer requested by MainViewModel; rendered by ViewerHost. */
sealed interface ViewerRequest {
    data class Image(val items: List<XEntry>, val startIndex: Int) : ViewerRequest
    data class Text(val entry: XEntry) : ViewerRequest
    data class Hex(val entry: XEntry) : ViewerRequest
    data class Media(val entry: XEntry, val playlist: List<XEntry>) : ViewerRequest
}
