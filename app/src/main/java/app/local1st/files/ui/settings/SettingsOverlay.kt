package app.local1st.files.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.local1st.files.core.prefs.SortBy
import app.local1st.files.core.prefs.ThemeMode
import app.local1st.files.di.Graph
import app.local1st.files.ui.components.PredictiveBackContainer
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.MainViewModel
import kotlinx.coroutines.launch

/** Full-screen settings overlay, visible while [MainViewModel.showSettings] is true. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsOverlay(vm: MainViewModel) {
    val show by vm.showSettings.collectAsState()
    if (!show) return
    val close = { vm.showSettings.value = false }

    val settings = Graph.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val dynamicColor by settings.dynamicColor.collectAsState(initial = true)
    val showHidden by settings.showHidden.collectAsState(initial = false)
    val dirsFirst by settings.dirsFirst.collectAsState(initial = true)
    val sortBy by settings.sortBy.collectAsState(initial = SortBy.NAME)
    val sortDescending by settings.sortDescending.collectAsState(initial = false)
    val rootEnabled by settings.rootEnabled.collectAsState(initial = false)
    val rootReadOnly by settings.rootReadOnly.collectAsState(initial = true)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    PredictiveBackContainer(onBack = close) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        TooltipIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, onClick = close)
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                SectionHeader("Appearance")
                RadioOptionsRow(
                    title = "Theme",
                    options = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                    ),
                    selected = themeMode,
                    onSelect = { scope.launch { settings.setThemeMode(it) } },
                )
                SwitchRow(
                    title = "Dynamic color",
                    subtitle = "Colors from your wallpaper (Android 12+)",
                    checked = dynamicColor,
                    onCheckedChange = { scope.launch { settings.setDynamicColor(it) } },
                )

                SectionHeader("Browsing")
                SwitchRow(
                    title = "Show hidden files",
                    subtitle = "Include dot-files and hidden folders",
                    checked = showHidden,
                    onCheckedChange = { scope.launch { settings.setShowHidden(it) } },
                )
                SwitchRow(
                    title = "Folders first",
                    subtitle = "List folders before files",
                    checked = dirsFirst,
                    onCheckedChange = { scope.launch { settings.setDirsFirst(it) } },
                )
                RadioOptionsRow(
                    title = "Sort by",
                    options = listOf(
                        SortBy.NAME to "Name",
                        SortBy.SIZE to "Size",
                        SortBy.DATE to "Date",
                        SortBy.TYPE to "Type",
                    ),
                    selected = sortBy,
                    onSelect = { scope.launch { settings.setSortBy(it) } },
                )
                SwitchRow(
                    title = "Descending",
                    subtitle = "Reverse the sort order",
                    checked = sortDescending,
                    onCheckedChange = { scope.launch { settings.setSortDescending(it) } },
                )

                SectionHeader("Root")
                SwitchRow(
                    title = "Root access",
                    subtitle = "Browse the whole system as superuser (needs su)",
                    checked = rootEnabled,
                    onCheckedChange = { scope.launch { settings.setRootEnabled(it) } },
                )
                if (rootEnabled) {
                    SwitchRow(
                        title = "Read-only",
                        subtitle = "Block changes that need root",
                        checked = rootReadOnly,
                        onCheckedChange = { scope.launch { settings.setRootReadOnly(it) } },
                    )
                }

                SectionHeader("About")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("XFiles", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Offline, open-source file manager. No network, no telemetry.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/Local1stDotApp/XFiles"),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure {
                                    Toast.makeText(
                                        context,
                                        "github.com/Local1stDotApp/XFiles",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                        ) {
                            Text("Source code")
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> RadioOptionsRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .selectable(
                            selected = value == selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(value) },
                        )
                        .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == selected, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
