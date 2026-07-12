package com.xfiles.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xfiles.ui.main.MainViewModel

/**
 * Hosts the full-screen viewer requested via [MainViewModel.viewer].
 * Individual viewers live in ui/viewer/ (image, text, hex, media).
 */
@Composable
fun ViewerHost(vm: MainViewModel) {
    val request by vm.viewer.collectAsState()
    val req = request ?: return
    val close = { vm.viewer.value = null }

    BackHandler(onBack = close)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Placeholder host — replaced by dedicated viewers.
            Text(
                when (req) {
                    is ViewerRequest.Image -> "Image viewer: ${req.items[req.startIndex].name}"
                    is ViewerRequest.Text -> "Text viewer: ${req.entry.name}"
                    is ViewerRequest.Hex -> "Hex viewer: ${req.entry.name}"
                    is ViewerRequest.Media -> "Media player: ${req.entry.name}"
                },
            )
        }
    }
}
