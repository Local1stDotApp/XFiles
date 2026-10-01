package app.local1st.files.ui.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import app.local1st.files.R
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.local1st.files.core.thumb.AppIcon
import app.local1st.files.core.thumb.PrivFile
import app.local1st.files.core.thumb.VideoThumb
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.Format
import java.io.File

// One tree level: 2.dp + 12.dp chevron. The lead keeps the chevron off the
// selection highlight / branch-line cap. Guides use the same width so a child's
// spine runs through the chevron.
private val ChevronGapStart = 2.dp
private val ChevronSize = 12.dp
private val IndentWidth = ChevronGapStart + ChevronSize
private val RowHeight = 56.dp

/** Action row shown after the pane-root list; same geometry as a depth-0 [EntryRow]. */
@Composable
fun AddLocationRow(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.add_location)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clip(RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = label },
    ) {
        Spacer(Modifier.width(IndentWidth))
        Box(Modifier.padding(end = 8.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EntryRow(
    node: TreeNode,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    enabled: Boolean = true,
    richContent: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val entry = node.entry
    val isVolume = entry.kind == EntryKind.VOLUME_INTERNAL ||
        entry.kind == EntryKind.VOLUME_SD ||
        entry.kind == EntryKind.VOLUME_USB
    val selectable = !isVolume &&
        entry.kind != EntryKind.APPS_ROOT &&
        entry.kind != EntryKind.ROOT &&
        entry.kind != EntryKind.LOCATION &&
        entry.kind != EntryKind.APP_COMPONENT_GROUP &&
        entry.kind != EntryKind.APP_COMPONENT

    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        focused -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    if (!richContent) {
        StartupEntryRow(
            node = node,
            selected = selected,
            focused = focused,
            onClick = onClick,
            enabled = enabled,
            selectable = selectable,
            isVolume = isVolume,
            modifier = modifier,
        )
        return
    }
    val guideColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
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
                // Round caps extend past the end by half the stroke; stop at the
                // column edge so the chevron's leading gap stays visible.
                val endX = x + unit / 2 - stroke / 2f
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
            ExpandChevron(
                expanded = node.expanded,
                label = stringResource(
                    if (node.expanded) R.string.collapse else R.string.expand,
                ),
                animate = true,
            )
        } else {
            Spacer(Modifier.width(IndentWidth))
        }

        // Icon or thumbnail (selection is the trailing control, to avoid mis-taps here).
        Box(Modifier.padding(end = 8.dp), contentAlignment = Alignment.Center) {
            val wantsThumbnail = EntryIcons.wantsThumbnail(entry)
            if (entry.kind == EntryKind.APP) {
                AsyncImage(
                    model = AppIcon(entry.path),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else if (wantsThumbnail) {
                EntryThumbnail(entry)
            } else {
                EntryIcon(
                    entry,
                    tint = if (entry.isContainer) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (isVolume) 28.dp else 24.dp),
                    expanded = node.expanded,
                )
            }
        }

        // Name + details. Weight expresses hierarchy: navigable containers read heavier than
        // plain files, volumes heaviest — the M3 Expressive variable-weight cue.
        Column(Modifier.weight(1f)) {
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
            val description = stringResource(if (selected) R.string.deselect else R.string.select)
            IconButton(onClick = onToggleSelect, enabled = enabled) {
                SelectionMark(selected, contentDescription = description)
            }
        }
    }
}

@Composable
private fun ExpandChevron(
    expanded: Boolean,
    label: String?,
    animate: Boolean,
) {
    val target = if (expanded) 90f else 0f
    val animated by animateFloatAsState(target, label = "chevron")
    val rotation = if (animate) animated else target
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // Separate sibling so Layout Inspector reports the canvas inset from the
    // selection highlight. Padding on the canvas itself is part of that node's
    // bounds, so the gap disappears.
    Spacer(Modifier.width(ChevronGapStart))
    Canvas(
        Modifier
            .size(ChevronSize)
            .semantics {
                if (label != null) {
                    contentDescription = label
                }
            },
    ) {
        // Rotate in the draw scope so the layout box stays in this slot.
        // Modifier.rotate() attaches a graphics layer; when the chevron points
        // down, Layout Inspector reports that layer on top of the folder icon.
        rotate(rotation) {
            // 90° tip (45° arms), same opening as Material's chevron, without its
            // 24dp-viewport padding. A square corner-to-corner stroke was ~53° and
            // read as a spike.
            val stroke = 1.25.dp.toPx()
            val pad = stroke / 2f + 0.5.dp.toPx()
            val half = size.minDimension / 2f - pad
            val cx = size.width / 2f
            val cy = size.height / 2f
            val path = Path().apply {
                moveTo(cx - half / 2f, cy - half)
                lineTo(cx + half / 2f, cy)
                lineTo(cx - half / 2f, cy + half)
            }
            drawPath(
                path,
                color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * Trailing selection mark. The unselected ring is drawn rather than a vector [Icon]: each Icon
 * rasterizes its own bitmap on first draw, and expanding a folder brings a dozen rows in within
 * one frame.
 */
@Composable
private fun SelectionMark(selected: Boolean, contentDescription: String?) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    if (selected) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    } else {
        Canvas(
            Modifier
                .size(22.dp)
                .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        ) {
            // The ring of Icons.Outlined.RadioButtonUnchecked: radius 9, stroke 2 in its 24-unit box.
            val unit = size.minDimension / 24f
            drawCircle(tint, radius = 9f * unit, style = Stroke(width = 2f * unit))
        }
    }
}

/**
 * First-draw row: same geometry and useful text, without Canvas guides, long-press machinery,
 * ripple/selection buttons, thumbnail painters, or animation nodes. The full row replaces it
 * after the lightweight list has already produced a visible frame.
 */
@Composable
private fun StartupEntryRow(
    node: TreeNode,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    selectable: Boolean,
    isVolume: Boolean,
    modifier: Modifier,
) {
    val entry = node.entry
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        focused -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    val wantsThumbnail = EntryIcons.wantsThumbnail(entry)
    val iconSize = when {
        entry.kind == EntryKind.APP -> 32.dp
        wantsThumbnail -> 36.dp
        isVolume -> 28.dp
        else -> 24.dp
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (node.depth > 0) Spacer(Modifier.width(IndentWidth * node.depth))
        if (entry.isContainer) {
            ExpandChevron(
                expanded = node.expanded,
                label = null,
                animate = false,
            )
        } else {
            Spacer(Modifier.width(IndentWidth))
        }
        Box(
            Modifier.padding(end = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                EntryIcons.forEntry(entry, expanded = node.expanded),
                contentDescription = null,
                tint = if (entry.isContainer) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
        }
        Column(Modifier.weight(1f)) {
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            val details = entryDetails(node)
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selectable) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                SelectionMark(selected, contentDescription = null)
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
                modifier = Modifier
                    .size(24.dp)
                    // A memory-cached thumbnail arrives while the row is first measured, before
                    // this draws; skip the draw then rather than rasterize a hidden vector.
                    .drawWithContent { if (!loaded) drawContent() },
            )
        }
        // LocalFileSystem clears localPath on a row that is, or resolves into, a bin entry,
        // so File() below never follows one. A file:// row without it is read through
        // openIn on IO, which refuses those links; composition does no file I/O here.
        val localPath = entry.localPath
        val grantImage by produceState<ByteArray?>(null, entry.id) {
            if (isVideo || localPath != null || entry.scheme != XId.SCHEME_FILE) return@produceState
            value = withContext(Dispatchers.IO) { thumbnailBytes(entry) }
        }
        val thumbModel = when {
            localPath == null && entry.scheme == XId.SCHEME_FILE && !isVideo -> grantImage
            isVideo && (localPath != null || entry.scheme == XId.SCHEME_ROOT) -> VideoThumb(
                path = localPath ?: entry.path,
                mtime = entry.mtime,
                size = entry.size,
                privileged = localPath == null,
            )
            localPath != null -> File(localPath)
            entry.scheme == XId.SCHEME_ROOT -> PrivFile(entry.path, entry.mtime, entry.size)
            else -> null
        }
        if (thumbModel != null) AsyncImage(
            model = thumbModel,
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

private const val THUMB_MAX_BYTES = 8L * 1024 * 1024

/** Small decode of a grant-only image. Null when the file is too large or cannot be opened. */
private fun thumbnailBytes(entry: XEntry): ByteArray? = runCatching {
    if (entry.size > THUMB_MAX_BYTES) return null
    Graph.fsRegistry.forId(entry.id).openIn(entry).use { input ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > THUMB_MAX_BYTES) return null
            out.write(chunk, 0, n)
        }
        out.toByteArray()
    }
}.getOrNull()

@Composable
private fun entryDetails(node: TreeNode): String {
    val entry = node.entry
    return when {
        entry.badge != null -> entry.badge
        !entry.isDir && entry.size >= 0 -> Format.details(
            Format.bytes(entry.size),
            Format.dateTime(entry.mtime),
        )
        entry.isDir -> Format.details(
            if (entry.childCountHint >= 0) {
                pluralStringResource(
                    R.plurals.item_count_plural, entry.childCountHint, entry.childCountHint,
                )
            } else "",
            Format.dateTime(entry.mtime),
        )
        else -> ""
    }
}
