package com.xfiles.ui.settings

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

/** Settings screen overlay. Replaced by the real implementation. */
@Composable
fun SettingsOverlay(vm: MainViewModel) {
    val show by vm.showSettings.collectAsState()
    if (!show) return
    val close = { vm.showSettings.value = false }

    BackHandler(onBack = close)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
    }
}
