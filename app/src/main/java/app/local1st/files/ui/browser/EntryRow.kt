package app.local1st.files.ui.browser

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.thumb.AppIcon
import app.local1st.files.core.thumb.VideoThumb
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.Format
import java.io.File

private val IndentWidth = 14.dp
private val RowHeight = 56.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EntryRow(
    node: TreeNode,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = node.entry
    val isVolume = entry.kind == EntryKind.VOLUME_INTERNAL ||
        entry.kind == EntryKind.VOLUME_SD ||
        entry.kind == EntryKind.VOLUME_USB
    val selectable = !isVolume &&
        entry.kind != EntryKind.APPS_ROOT &&
        entry.kind != EntryKind.ROOT &&
        entry.kind != EntryKind.APP_COMPONENT_GROUP &&
        entry.kind != EntryKind.APP_COMPONENT

    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        focused -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    val guideColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // Tree guide lines + connector elbow. The ancestor spines show the full nesting path —
        // parent, grandparent, and so on up to the root.
        if (node.depth > 0) {
            Canvas(
                Modifier
                    .width(IndentWidth * node.depth)
                    .fillMaxHeight(),
            ) {
                val unit = IndentWidth.toPx()
                val stroke = 1.dp.toPx()
                // guides[i] tells whether the ancestor at depth i has a following sibling; that
                // ancestor's sibling-spine sits one indent to the left, at level i-1. (guides[0]
                // is the root — no spine.) The spine at this row's OWN level (depth-1) is the
                // connector below — never drawn here, so a last child doesn't get a second,
                // non-closing line over its "└".
                node.guides.forEachIndexed { i, draw ->
                    if (draw && i >= 1) {
                        val gx = unit * (i - 1) + unit / 2
                        drawLine(guideColor, Offset(gx, 0f), Offset(gx, size.height), stroke)
                    }
                }
                val x = unit * (node.depth - 1) + unit / 2
                val midY = size.height / 2
                val endX = x + unit / 2
                if (node.isLastChild) {
                    // Rounded "└": the vertical stops here and curves into the branch — the arc
                    // alone marks the last item, so it uses the same tone/weight as other guides.
                    val r = unit * 0.4f
                    val path = Path().apply {
                        moveTo(x, 0f)
                        lineTo(x, midY - r)
                        quadraticBezierTo(x, midY, x + r, midY)
                        lineTo(endX, midY)
                    }
                    drawPath(
                        path,
                        guideColor,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                } else {
                    // "├": the spine continues past this item to its following siblings.
                    drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), stroke)
                    drawLine(guideColor, Offset(x, midY), Offset(endX, midY), stroke, cap = StrokeCap.Round)
                }
            }
        }

        // Expand chevron for containers, aligned space for leaves.
        if (entry.isContainer) {
            val rotation by animateFloatAsState(if (node.expanded) 90f else 0f, label = "chevron")
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = if (node.expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotation),
            )
        } else {
            Box(Modifier.size(20.dp))
        }

        // Icon or thumbnail (selection is the trailing control, to avoid mis-taps here).
        Box(Modifier.padding(start = 2.dp, end = 10.dp), contentAlignment = Alignment.Center) {
            if (entry.kind == EntryKind.APP) {
                AsyncImage(
                    model = AppIcon(entry.path),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else if (EntryIcons.wantsThumbnail(entry)) {
                EntryThumbnail(entry)
            } else {
                Icon(
                    EntryIcons.forEntry(entry, node.expanded),
                    contentDescription = null,
                    tint = if (entry.isContainer) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (isVolume) 28.dp else 24.dp),
                )
            }
        }

        // Name + details. Weight expresses hierarchy: navigable containers read heavier than
        // plain files, volumes heaviest — the M3 Expressive variable-weight cue.
        Column(Modifier.weight(1f).animateContentSize()) {
            Text(
                entry.name,
                style = if (isVolume) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.bodyLarge,
                fontWeight = when {
                    isVolume -> FontWeight.SemiBold
                    entry.isContainer -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (node.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            val details = node.error ?: entryDetails(node)
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (node.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isVolume && entry.progress >= 0f) {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp, end = 8.dp)
                        .height(4.dp),
                )
            }
        }

        if (node.loading) {
            LoadingIndicator(modifier = Modifier.size(28.dp))
        }

        if (selectable) {
            IconButton(onClick = onToggleSelect) {
                Icon(
                    if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "Deselect" else "Select",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * Thumbnail with a vector-icon fallback: the icon shows until the image actually arrives
 * (video frame extraction can take seconds on a cold cache) and stays if loading fails,
 * so the slot is never blank. Videos additionally get a small play badge.
 */
@Composable
private fun EntryThumbnail(entry: XEntry) {
    val isVideo = FileTypes.categoryOf(entry.name, entry.mime) == FileCategory.VIDEO
    var loaded by remember(entry.id) { mutableStateOf(false) }
    Box(contentAlignment = Alignment.Center) {
        if (!loaded) {
            Icon(
                EntryIcons.forEntry(entry),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        AsyncImage(
            model = if (isVideo) VideoThumb(entry.localPath!!, entry.mtime, entry.size)
            else File(entry.localPath!!),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { loaded = it is AsyncImagePainter.State.Success },
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        if (isVideo && loaded) {
            Box(
                Modifier
                    .size(16.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

private fun entryDetails(node: TreeNode): String {
    val entry = node.entry
    return when {
        entry.badge != null -> entry.badge
        !entry.isDir && entry.size >= 0 -> {
            val date = Format.dateTime(entry.mtime)
            if (date.isEmpty()) Format.bytes(entry.size) else "${Format.bytes(entry.size)} · $date"
        }
        entry.isDir && entry.childCountHint >= 0 -> "${entry.childCountHint} items"
        // Folders otherwise show just their name — dropping the bare timestamp declutters the tree.
        else -> ""
    }
}
