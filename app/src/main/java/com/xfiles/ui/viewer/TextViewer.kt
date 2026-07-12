package com.xfiles.ui.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditOff
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfiles.core.fs.XEntry
import com.xfiles.core.fs.XId
import com.xfiles.di.Graph
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TEXT_LIMIT_BYTES = 2 * 1024 * 1024

/**
 * Plain-text viewer with optional in-place editing for writable local files.
 * Files larger than 2 MiB are shown truncated and read-only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextViewer(entry: XEntry, onClose: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(entry.id) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                Graph.fsRegistry.forId(entry.id).openIn(entry).use { input ->
                    val bytes = input.readUpTo(TEXT_LIMIT_BYTES + 1)
                    val wasTruncated = bytes.size > TEXT_LIMIT_BYTES
                    val visible = if (wasTruncated) bytes.copyOf(TEXT_LIMIT_BYTES) else bytes
                    // String(UTF_8) replaces malformed sequences with U+FFFD.
                    String(visible, Charsets.UTF_8) to wasTruncated
                }
            }
        }
        result.fold(
            onSuccess = { (content, wasTruncated) ->
                text = content
                truncated = wasTruncated
            },
            onFailure = { loadError = it.message ?: "Cannot read ${entry.name}" },
        )
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(3000)
            feedback = null
        }
    }

    val canEdit = entry.scheme == XId.SCHEME_FILE && entry.canWrite && !truncated

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Write to a temp sibling then atomically move over the original, so a
                    // failed/interrupted write never destroys the file's existing contents.
                    // canEdit guarantees the file scheme, so java.io/java.nio is valid here.
                    val target = java.io.File(entry.localPath ?: entry.path)
                    val tmp = java.io.File(target.parentFile, ".${entry.name}.xfiles-tmp")
                    try {
                        tmp.outputStream().use { it.write(editText.toByteArray(Charsets.UTF_8)) }
                        java.nio.file.Files.move(
                            tmp.toPath(),
                            target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        )
                    } catch (e: Throwable) {
                        tmp.delete()
                        throw e
                    }
                }
            }
            saving = false
            result.fold(
                onSuccess = {
                    text = editText
                    editing = false
                    feedback = "Saved ${entry.name}"
                },
                onFailure = { feedback = it.message ?: "Save failed" },
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val shown = if (editing) editText else text
                    if (shown != null) {
                        Text(
                            "${countLines(shown)} lines",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            },
            actions = {
                if (canEdit && text != null) {
                    if (editing) {
                        IconButton(onClick = { save() }, enabled = !saving) {
                            Icon(Icons.Outlined.Save, contentDescription = "Save")
                        }
                    }
                    IconButton(onClick = {
                        if (!editing) editText = text.orEmpty()
                        editing = !editing
                    }) {
                        Icon(
                            if (editing) Icons.Outlined.EditOff else Icons.Outlined.Edit,
                            contentDescription = if (editing) "Stop editing" else "Edit",
                        )
                    }
                }
            },
        )

        if (truncated) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "File exceeds 2 MB — showing the first 2 MB (read-only)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loadError != null -> Text(
                    loadError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                text == null -> LoadingIndicator(Modifier.align(Alignment.Center))
                editing -> BasicTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
                else -> SelectionContainer {
                    Text(
                        text.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }

            feedback?.let { message ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private fun countLines(s: String): Int {
    var lines = 1
    for (c in s) if (c == '\n') lines++
    return lines
}

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 1 shl 16))
    val buffer = ByteArray(1 shl 16)
    var remaining = limit
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size, remaining))
        if (n < 0) break
        out.write(buffer, 0, n)
        remaining -= n
    }
    return out.toByteArray()
}
