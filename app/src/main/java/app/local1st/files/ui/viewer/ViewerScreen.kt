package app.local1st.files.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.local1st.files.ui.main.MainViewModel

/**
 * Full-screen viewer destination. Navigation 3 owns system and predictive-back handling;
 * viewer chrome calls [onBack] for its explicit close action.
 */
@Composable
fun ViewerScreen(vm: MainViewModel, request: ViewerRequest, onBack: () -> Unit) {
    val req = request
    val close = onBack

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when (req) {
            is ViewerRequest.Image -> ImageViewer(req.items, req.startIndex, close)
            is ViewerRequest.Text -> TextViewer(
                entry = req.entry,
                startEditing = req.startEditing,
                onClose = close,
            )
            is ViewerRequest.Hex -> HexViewer(req.entry, close)
            is ViewerRequest.Pdf -> PdfViewer(req.entry, close) { vm.openWith(req.entry) }
            is ViewerRequest.Media -> MediaViewer(req.entry, req.playlist, close)
        }
    }
}
