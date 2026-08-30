package app.local1st.files.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/**
 * Pre-R immersive hide/show can leave Compose's IgnoringVisibility at 0 while the
 * platform stable insets have recovered. Union with the live insets: a visible bar
 * still pads, and a hidden bar does not collapse a screen that already read the
 * ignoring-visibility size.
 */
val WindowInsets.Companion.statusBarsStable: WindowInsets
    @OptIn(ExperimentalLayoutApi::class)
    @Composable get() = statusBarsIgnoringVisibility.union(statusBars)

val WindowInsets.Companion.navigationBarsStable: WindowInsets
    @OptIn(ExperimentalLayoutApi::class)
    @Composable get() = navigationBarsIgnoringVisibility.union(navigationBars)
