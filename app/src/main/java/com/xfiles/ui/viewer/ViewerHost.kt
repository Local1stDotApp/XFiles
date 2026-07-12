package com.xfiles.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xfiles.ui.main.MainViewModel

/**
 * Hosts the full-screen viewer requested via [MainViewModel.viewer].
 * Composed above the rest of MainScreen; back press or a viewer's close action dismisses it.
 */
@Composable
fun ViewerHost(vm: MainViewModel) {
    val request by vm.viewer.collectAsStateWithLifecycle()
    val req = request ?: return
    val close: () -> Unit = { vm.viewer.value = null }

    BackHandler(onBack = close)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // key() drops all viewer state when a different request replaces the current one.
        key(req) {
            when (req) {
                is ViewerRequest.Image -> ImageViewer(req.items, req.startIndex, close)
                is ViewerRequest.Text -> TextViewer(req.entry, close)
                is ViewerRequest.Hex -> HexViewer(req.entry, close)
                is ViewerRequest.Media -> MediaViewer(req.entry, req.playlist, close)
            }
        }
    }
}
