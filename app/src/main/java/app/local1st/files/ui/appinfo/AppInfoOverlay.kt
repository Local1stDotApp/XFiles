package app.local1st.files.ui.appinfo

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import coil3.compose.AsyncImage
import app.local1st.files.core.thumb.AppIcon
import app.local1st.files.core.util.AppDetails
import app.local1st.files.core.util.AppInspector
import app.local1st.files.core.util.CertInfo
import app.local1st.files.core.util.Format
import app.local1st.files.ui.components.PredictiveBackContainer
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-screen rich app-details screen, shown while [MainViewModel.appDetails] holds a package. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppInfoOverlay(vm: MainViewModel) {
    val packageName by vm.appDetails.collectAsState()
    val pkg = packageName ?: return
    val close = { vm.appDetails.value = null }

    val context = LocalContext.current
    val details by produceState<AppDetails?>(initialValue = null, pkg) {
        value = withContext(Dispatchers.IO) { AppInspector.inspect(context, pkg) }
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
                    title = { Text(details?.label ?: pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        TooltipIconButton(stringResource(R.string.back), Icons.AutoMirrored.Outlined.ArrowBack, onClick = close)
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            val d = details
            if (d == null) {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                return@Scaffold
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                Header(d)
                OverviewCard(d)
                SignatureCard(d.certificates)
                ExpandableSection(stringResource(R.string.permissions), d.permissions.size) {
                    if (d.permissions.isEmpty()) EmptyLine()
                    else d.permissions.forEach { MonoLine(it) }
                }
                d.components.forEach { group ->
                    ExpandableSection(group.title, group.names.size) {
                        if (group.names.isEmpty()) EmptyLine()
                        else group.names.forEach { MonoLine(it) }
                    }
                }
                if (d.features.isNotEmpty()) {
                    ExpandableSection(stringResource(R.string.features), d.features.size) {
                        d.features.forEach { MonoLine(it) }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    }
}

@Composable
private fun Header(d: AppDetails) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = AppIcon(d.packageName),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(d.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            CopyableText(d.packageName, MaterialTheme.typography.bodyMedium)
            Text(
                "v${d.versionName} (${d.versionCode})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverviewCard(d: AppDetails) {
    val kind = buildList {
        if (d.system) add(stringResource(R.string.system)) else add(stringResource(R.string.user))
        if (d.debuggable) add(stringResource(R.string.debuggable))
        if (!d.enabled) add(stringResource(R.string.disabled))
    }.joinToString(" · ")
    SectionCard {
        InfoRow(stringResource(R.string.type), kind)
        InfoRow(stringResource(R.string.sdk_versions), "${d.minSdk} / ${d.targetSdk} / ${d.compileSdk}")
        InfoRow(stringResource(R.string.apk_size), Format.bytes(d.apkSize) + if (d.splitCount > 0) "  ·  ${stringResource(R.string.splits_format, d.splitCount)}" else "")
        InfoRow(stringResource(R.string.installed), Format.dateTime(d.firstInstallTime))
        InfoRow(stringResource(R.string.updated), Format.dateTime(d.lastUpdateTime))
        InfoRow("UID", d.uid.toString())
        d.installerPackage?.let { InfoRow(stringResource(R.string.installer), it) }
        InfoRow(stringResource(R.string.apk_path), d.sourceDir)
        InfoRow(stringResource(R.string.data_path), d.dataDir)
    }
}

@Composable
private fun SignatureCard(certs: List<CertInfo>) {
    ExpandableSection(stringResource(R.string.signature), certs.size, initiallyExpanded = certs.size == 1) {
        if (certs.isEmpty()) {
            EmptyLine()
            return@ExpandableSection
        }
        certs.forEachIndexed { i, c ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            LabeledHash("SHA-256", c.sha256)
            LabeledHash("SHA-1", c.sha1)
            if (c.subject.isNotEmpty()) InfoRow("Subject", c.subject)
            if (c.issuer.isNotEmpty()) InfoRow("Issuer", c.issuer)
            if (c.algorithm.isNotEmpty()) InfoRow("Algorithm", c.algorithm)
            if (c.validTo > 0) InfoRow(stringResource(R.string.valid), "${Format.dateTime(c.validFrom)} → ${Format.dateTime(c.validTo)}")
        }
    }
}

@Composable
private fun LabeledHash(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        CopyableText(value, MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
}

// ---- small building blocks ----

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    count: Int,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                    modifier = Modifier.padding(start = 8.dp).size(22.dp).rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun EmptyLine() {
    Text(stringResource(R.string.none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Tap-to-copy text (handy for package names and cert hashes). */
@Composable
private fun CopyableText(text: String, style: androidx.compose.ui.text.TextStyle) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.copied)
    Text(
        text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clickable {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
    )
}
