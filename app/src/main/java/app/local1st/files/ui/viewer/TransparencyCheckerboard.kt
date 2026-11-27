package app.local1st.files.ui.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Viewer backdrop. A plain black fill hid where a dark picture ended and made its transparent
 * pixels look black; a checkerboard reads as "nothing here" in either case.
 */
@Immutable
class TransparencyCheckerboard(
    val brush: Brush,
    /** For text and spinners drawn straight on the checkerboard. */
    val contentColor: Color,
)

private val Square = 12.dp

/** Same squares and greys as the Pro app's image viewer, light or dark with the theme. */
@Composable
fun rememberTransparencyCheckerboard(): TransparencyCheckerboard {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val squarePx = with(LocalDensity.current) { Square.roundToPx() }.coerceAtLeast(1)
    return remember(dark, squarePx) {
        val (lightSquare, darkSquare) = if (dark) {
            Color(0xFF383838) to Color(0xFF1F1F1F)
        } else {
            Color(0xFFE6E6E6) to Color(0xFFC7C7C7)
        }
        val tile = ImageBitmap(squarePx * 2, squarePx * 2)
        Canvas(tile).apply {
            val paint = Paint()
            val side = squarePx.toFloat()
            paint.color = lightSquare
            drawRect(Rect(0f, 0f, side * 2, side * 2), paint)
            paint.color = darkSquare
            drawRect(Rect(0f, 0f, side, side), paint)
            drawRect(Rect(side, side, side * 2, side * 2), paint)
        }
        TransparencyCheckerboard(
            brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)),
            contentColor = if (dark) Color.White else Color.Black,
        )
    }
}
