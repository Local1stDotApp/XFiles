package com.xfiles.ui.search

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

/** Full-screen search below [MainViewModel.searchRoot]. Replaced by the real implementation. */
@Composable
fun SearchOverlay(vm: MainViewModel) {
    val root by vm.searchRoot.collectAsState()
    val r = root ?: return
    val close = { vm.searchRoot.value = null }

    BackHandler(onBack = close)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Search in ${r.name}")
        }
    }
}
