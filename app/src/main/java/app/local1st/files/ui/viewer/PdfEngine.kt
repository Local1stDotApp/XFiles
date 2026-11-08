package app.local1st.files.ui.viewer

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt

/** One end of a text selection on a page. */
internal sealed interface TextEdge {
    /**
     * A character of the page's text, counted from its start; past the end means the end.
     * Only for asking: the platform reports the ends of a selection as points, with index -1.
     */
    data class Index(val index: Int) : TextEdge

    /** The text nearest a point, in page points (1/72 inch) from the page's top left. */
    data class Near(val point: Offset) : TextEdge
}

/** Text selected on one page. */
internal class PdfTextSelection(
    val pageIndex: Int,
    val text: String,
    /** One rectangle per line, in page points from the page's top left, in reading order. */
    val lines: List<Rect>,
) {
    /** Where the selection starts: the bottom left of its first line, as the platform takes it back. */
    val startCorner: Offset get() = lines.first().let { Offset(it.left, it.bottom) }

    /** Where it stops: the bottom right of its last line. */
    val stopCorner: Offset get() = lines.last().let { Offset(it.right, it.bottom) }
}

/**
 * The platform renderer behind a PDF. It opens one page at a time, so callers take turns.
 *
 * [PdfRenderer] reads the text on a page from Android 15. Android 12 to 14 have the same calls in
 * [PdfRendererPreV] once a system update has brought the PDF module to SDK extension 13. Without
 * that, and below Android 12, pages are pictures only.
 */
internal interface PdfEngine {
    val pageCount: Int

    /** True when pages can [PdfEnginePage.select] text. */
    val readsText: Boolean

    fun <T> withPage(index: Int, block: (PdfEnginePage) -> T): T

    fun close()

    companion object {
        /** Throws what the platform throws: [java.io.IOException] for a damaged file, [SecurityException] for a password. */
        fun open(openDescriptor: () -> ParcelFileDescriptor): PdfEngine {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
            ) {
                try {
                    return ModuleEngine(openRenderer(openDescriptor, ::PdfRendererPreV))
                } catch (_: LinkageError) {
                    // The extension level claims a class this build lacks: render pictures instead.
                }
            }
            return FrameworkEngine(openRenderer(openDescriptor, ::PdfRenderer))
        }

        /** The renderer owns the descriptor once constructed; until then, a failure closes it here. */
        private fun <R> openRenderer(
            openDescriptor: () -> ParcelFileDescriptor,
            create: (ParcelFileDescriptor) -> R,
        ): R {
            val descriptor = openDescriptor()
            return try {
                create(descriptor)
            } catch (error: Throwable) {
                runCatching { descriptor.close() }
                throw error
            }
        }
    }
}

internal interface PdfEnginePage {
    /** Page width in points (1/72 inch). */
    val width: Int

    /** Page height in points (1/72 inch). */
    val height: Int

    /** Draws the whole page, scaled to fill [bitmap]. */
    fun render(bitmap: Bitmap)

    /** The text from [start] to [stop], or null when there is none or the engine reads no text. */
    fun select(start: TextEdge, stop: TextEdge): PdfTextSelection?

    /** All the page's text, or null when it has none or the engine reads no text. */
    fun selectAll(): PdfTextSelection?
}

private class FrameworkEngine(private val renderer: PdfRenderer) : PdfEngine {
    override val pageCount: Int get() = renderer.pageCount
    override val readsText: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override fun <T> withPage(index: Int, block: (PdfEnginePage) -> T): T =
        renderer.openPage(index).use { page -> block(FrameworkPage(index, page)) }

    override fun close() = renderer.close()
}

private class FrameworkPage(private val index: Int, private val page: PdfRenderer.Page) : PdfEnginePage {
    override val width: Int get() = page.width
    override val height: Int get() = page.height

    override fun render(bitmap: Bitmap) {
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    }

    override fun select(start: TextEdge, stop: TextEdge): PdfTextSelection? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            FrameworkText.select(page, index, start, stop)
        } else {
            null
        }

    override fun selectAll(): PdfTextSelection? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            FrameworkText.selectAll(page, index)
        } else {
            null
        }
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private object FrameworkText {
    fun select(page: PdfRenderer.Page, index: Int, start: TextEdge, stop: TextEdge): PdfTextSelection? =
        page.selectContent(boundary(start), boundary(stop))?.let { toTextSelection(it, index) }

    fun selectAll(page: PdfRenderer.Page, index: Int): PdfTextSelection? =
        select(page, index, TextEdge.Index(0), TextEdge.Index(Int.MAX_VALUE))

    private fun boundary(edge: TextEdge): SelectionBoundary = when (edge) {
        is TextEdge.Index -> SelectionBoundary(edge.index)
        is TextEdge.Near -> SelectionBoundary(Point(edge.point.x.roundToInt(), edge.point.y.roundToInt()))
    }

    private fun toTextSelection(selection: PageSelection, index: Int): PdfTextSelection? {
        val contents = selection.selectedTextContents
        return pdfTextSelection(
            pageIndex = index,
            texts = contents.map { it.text },
            lines = contents.flatMap { content ->
                content.bounds.map { Rect(it.left, it.top, it.right, it.bottom) }
            },
        )
    }
}

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private class ModuleEngine(private val renderer: PdfRendererPreV) : PdfEngine {
    override val pageCount: Int get() = renderer.pageCount
    override val readsText: Boolean = true

    override fun <T> withPage(index: Int, block: (PdfEnginePage) -> T): T =
        renderer.openPage(index).use { page -> block(ModulePage(index, page)) }

    override fun close() = renderer.close()
}

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private class ModulePage(private val index: Int, private val page: PdfRendererPreV.Page) : PdfEnginePage {
    override val width: Int get() = page.width
    override val height: Int get() = page.height

    override fun render(bitmap: Bitmap) {
        page.render(
            bitmap,
            null,
            null,
            RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build(),
        )
    }

    override fun select(start: TextEdge, stop: TextEdge): PdfTextSelection? =
        page.selectContent(boundary(start), boundary(stop))?.let(::toTextSelection)

    override fun selectAll(): PdfTextSelection? =
        select(TextEdge.Index(0), TextEdge.Index(Int.MAX_VALUE))

    private fun boundary(edge: TextEdge): SelectionBoundary = when (edge) {
        is TextEdge.Index -> SelectionBoundary(edge.index)
        is TextEdge.Near -> SelectionBoundary(Point(edge.point.x.roundToInt(), edge.point.y.roundToInt()))
    }

    private fun toTextSelection(selection: PageSelection): PdfTextSelection? {
        val contents = selection.selectedTextContents
        return pdfTextSelection(
            pageIndex = index,
            texts = contents.map { it.text },
            lines = contents.flatMap { content ->
                content.bounds.map { Rect(it.left, it.top, it.right, it.bottom) }
            },
        )
    }
}

/**
 * A selection worth showing, from what the platform returned: null for nothing but white space,
 * or for text with no place on the page to mark. The platform ends lines with CR LF; text
 * copied on Android ends them with LF.
 */
internal fun pdfTextSelection(
    pageIndex: Int,
    texts: List<String>,
    lines: List<Rect>,
): PdfTextSelection? {
    val text = texts.joinToString("").replace("\r\n", "\n").replace('\r', '\n')
    if (text.isBlank() || lines.isEmpty()) return null
    return PdfTextSelection(pageIndex, text, lines)
}
