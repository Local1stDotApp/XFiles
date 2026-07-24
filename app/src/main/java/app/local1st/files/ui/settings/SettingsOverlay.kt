package app.local1st.files.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.local1st.files.BuildConfig
import app.local1st.files.R
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.fs.priv.ShizukuGate
import app.local1st.files.core.fs.priv.ShizukuState
import app.local1st.files.core.fs.priv.TransportId
import app.local1st.files.core.fs.priv.TransportPref
import app.local1st.files.core.prefs.SortBy
import app.local1st.files.core.prefs.ThemeMode
import app.local1st.files.core.util.ExternalOpenKind
import app.local1st.files.core.util.ExternalOpenRegistry
import app.local1st.files.di.Graph
import app.local1st.files.ui.components.PredictiveBackContainer
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuProvider

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
    val transportPref by settings.privilegedTransport.collectAsState(initial = null)
    val shizukuState by ShizukuGate.state.collectAsState()
    val permissionPermanentlyDenied by
        ShizukuGate.permissionPermanentlyDeniedState.collectAsState()
    var showShizukuHelp by rememberSaveable { mutableStateOf(false) }

    val activeTransport by produceState<TransportId?>(
        null,
        rootEnabled,
        transportPref,
        shizukuState,
    ) {
        value = withContext(Dispatchers.IO) {
            // Do not probe the AUTO default while a saved forced choice is still loading: on a
            // rooted device that could briefly exercise su despite an explicit Shizuku choice.
            transportPref?.takeIf { rootEnabled }?.let { PrivilegedAccess.activeFor(it)?.id }
        }
    }
    var archivesRegistered by remember {
        mutableStateOf(ExternalOpenRegistry.isEnabled(context, ExternalOpenKind.ARCHIVE))
    }
    var imagesRegistered by remember {
        mutableStateOf(ExternalOpenRegistry.isEnabled(context, ExternalOpenKind.IMAGE))
    }
    var videosRegistered by remember {
        mutableStateOf(ExternalOpenRegistry.isEnabled(context, ExternalOpenKind.VIDEO))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    PredictiveBackContainer(onBack = close) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.settings)) },
                    navigationIcon = {
                        TooltipIconButton(stringResource(R.string.back), Icons.AutoMirrored.Outlined.ArrowBack, onClick = close)
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
                SectionHeader(stringResource(R.string.appearance))
                RadioOptionsRow(
                    title = stringResource(R.string.theme),
                    options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.theme_light),
                        ThemeMode.DARK to stringResource(R.string.theme_dark),
                    ),
                    selected = themeMode,
                    onSelect = { scope.launch { settings.setThemeMode(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.dynamic_color),
                    subtitle = stringResource(R.string.dynamic_color_summary),
                    checked = dynamicColor,
                    onCheckedChange = { scope.launch { settings.setDynamicColor(it) } },
                )

                SectionHeader(stringResource(R.string.browsing))
                SwitchRow(
                    title = stringResource(R.string.show_hidden),
                    subtitle = stringResource(R.string.show_hidden_summary),
                    checked = showHidden,
                    onCheckedChange = { scope.launch { settings.setShowHidden(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.folders_first),
                    subtitle = stringResource(R.string.folders_first_summary),
                    checked = dirsFirst,
                    onCheckedChange = { scope.launch { settings.setDirsFirst(it) } },
                )
                RadioOptionsRow(
                    title = stringResource(R.string.sort_by),
                    options = listOf(
                        SortBy.NAME to stringResource(R.string.sort_name),
                        SortBy.SIZE to stringResource(R.string.sort_size),
                        SortBy.DATE to stringResource(R.string.sort_date),
                        SortBy.TYPE to stringResource(R.string.sort_type),
                    ),
                    selected = sortBy,
                    onSelect = { scope.launch { settings.setSortBy(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.descending),
                    subtitle = stringResource(R.string.descending_summary),
                    checked = sortDescending,
                    onCheckedChange = { scope.launch { settings.setSortDescending(it) } },
                )

                SectionHeader(stringResource(R.string.file_associations))
                SwitchRow(
                    title = stringResource(R.string.open_supported_archives_with_xfiles),
                    subtitle = stringResource(R.string.supported_archives_summary),
                    checked = archivesRegistered,
                    onCheckedChange = {
                        ExternalOpenRegistry.setEnabled(context, ExternalOpenKind.ARCHIVE, it)
                        archivesRegistered = it
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.view_images_with_xfiles),
                    checked = imagesRegistered,
                    onCheckedChange = {
                        ExternalOpenRegistry.setEnabled(context, ExternalOpenKind.IMAGE, it)
                        imagesRegistered = it
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.play_videos_with_xfiles),
                    checked = videosRegistered,
                    onCheckedChange = {
                        ExternalOpenRegistry.setEnabled(context, ExternalOpenKind.VIDEO, it)
                        videosRegistered = it
                    },
                )

                SectionHeader(stringResource(R.string.root))
                SwitchRow(
                    title = stringResource(R.string.root_access),
                    subtitle = stringResource(R.string.root_access_summary),
                    checked = rootEnabled,
                    onCheckedChange = { scope.launch { settings.setRootEnabled(it) } },
                )
                if (rootEnabled) {
                    SwitchRow(
                        title = stringResource(R.string.read_only),
                        subtitle = stringResource(R.string.read_only_summary),
                        checked = rootReadOnly,
                        onCheckedChange = { scope.launch { settings.setRootReadOnly(it) } },
                    )
                }
                RadioOptionsRow(
                    title = stringResource(R.string.transport),
                    options = listOf(
                        TransportPref.AUTO to stringResource(R.string.transport_auto),
                        TransportPref.SU to stringResource(R.string.transport_su),
                        TransportPref.SHIZUKU to stringResource(R.string.shizuku),
                        TransportPref.OFF to stringResource(R.string.transport_off),
                    ),
                    selected = transportPref ?: TransportPref.AUTO,
                    onSelect = { scope.launch { settings.setPrivilegedTransport(it) } },
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(
                                R.string.transport_status,
                                stringResource(activeTransportLabelRes(activeTransport)),
                                stringResource(shizukuStateLabelRes(shizukuState)),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (shizukuState == ShizukuState.PermissionRequired) {
                            Spacer(Modifier.height(8.dp))
                            if (permissionPermanentlyDenied) {
                                Text(
                                    stringResource(R.string.shizuku_grant_in_app),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { openShizuku(context) }) {
                                    Text(stringResource(R.string.open_shizuku))
                                }
                            } else {
                                OutlinedButton(onClick = { ShizukuGate.requestPermission() }) {
                                    Text(stringResource(R.string.shizuku_grant_permission))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showShizukuHelp = !showShizukuHelp }) {
                    Text(
                        stringResource(
                            if (showShizukuHelp) R.string.shizuku_help_hide
                            else R.string.shizuku_help_show,
                        ),
                    )
                }
                if (showShizukuHelp) {
                    ShizukuHelpCard(onOpenShizuku = { openShizuku(context) })
                }

                SectionHeader(stringResource(R.string.about))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "XFiles",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.alignByBaseline(),
                            )
                            Text(
                                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.app_tagline),
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
                            Text(stringResource(R.string.source_code))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}

@StringRes
private fun activeTransportLabelRes(transport: TransportId?): Int = when (transport) {
    TransportId.SU -> R.string.transport_su
    TransportId.SHIZUKU -> R.string.shizuku
    null -> R.string.transport_none
}

@StringRes
private fun shizukuStateLabelRes(state: ShizukuState): Int = when (state) {
    ShizukuState.NotInstalled -> R.string.shizuku_not_installed
    ShizukuState.NotRunning -> R.string.shizuku_not_running
    ShizukuState.PermissionRequired -> R.string.shizuku_permission_required
    ShizukuState.Ready -> R.string.shizuku_ready
}

@Composable
private fun ShizukuHelpCard(onOpenShizuku: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.shizuku_help_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.shizuku_help_start),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.shizuku_help_restart),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.shizuku_help_oem),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.shizuku_help_scope),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenShizuku) {
                Text(stringResource(R.string.open_shizuku))
            }
        }
    }
}

private fun openShizuku(context: Context) {
    val launched = runCatching {
        // Resolving the owner of Shizuku's global permission supports forks and avoids a
        // package-visibility query for a hardcoded package name.
        val packageName = context.packageManager
            .getPermissionInfo(ShizukuProvider.PERMISSION, 0)
            .packageName
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@runCatching false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
    if (!launched) {
        Toast.makeText(context, R.string.shizuku_app_not_available, Toast.LENGTH_SHORT).show()
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
