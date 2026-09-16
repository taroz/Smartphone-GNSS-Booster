package org.furo.rtcmstreamer.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.ceil
import kotlin.math.floor

/**
 * East-North 2D plot of the solution track (Compose Canvas).
 * Origin = first received solution (E=N=0, shown as the darker axis lines).
 * Each point is colored by quality (Q), consecutive points joined by a gray line, and the
 * latest point is always centered, drawn larger with a black edge. Background grid + scale bar.
 * Pinch to zoom.
 */
@Composable
fun PlotView(
    track: List<TrackPoint>,
    pxPerMeter: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            // zoom is the per-gesture multiplicative factor; apply it to the current scale.
            detectTransformGestures { _, _, zoom, _ -> onZoom(zoom) }
        }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        val latest = track.lastOrNull()
        val eL = latest?.e ?: 0f
        val nL = latest?.n ?: 0f

        fun screen(e: Float, n: Float) = Offset(
            cx + (e - eL) * pxPerMeter,
            cy - (n - nL) * pxPerMeter, // North up
        )

        drawGrid(eL, nL, pxPerMeter, cx, cy)

        // trajectory line
        for (i in 1 until track.size) {
            drawLine(
                Color(0xFF888888),
                screen(track[i - 1].e, track[i - 1].n),
                screen(track[i].e, track[i].n),
                2f,
            )
        }
        // points colored by quality
        for (p in track) {
            drawCircle(qColor(p.q), 16f, screen(p.e, p.n))
        }
        // latest point emphasized: larger, with black edge
        latest?.let { p ->
            val c = screen(p.e, p.n)
            drawCircle(qColor(p.q), 27f, c)
            drawCircle(Color.Black, 27f, c, style = Stroke(width = 4f))
        }
    }
}

/** Draw a meter grid with "nice" spacing, darker lines at the origin (E=0 / N=0), plus a scale bar. */
private fun DrawScope.drawGrid(eL: Float, nL: Float, pxPerMeter: Float, cx: Float, cy: Float) {
    val w = size.width
    val h = size.height
    val step = niceStepMeters(pxPerMeter) // meters per grid cell
    val gridColor = Color(0xFFE3E3E3)
    val axisColor = Color(0xFFB0B0B0)

    // vertical lines (constant E)
    val eMin = eL - cx / pxPerMeter
    val eMax = eL + (w - cx) / pxPerMeter
    var m = floor(eMin / step).toInt()
    val mMax = ceil(eMax / step).toInt()
    while (m <= mMax) {
        val x = cx + (m * step - eL) * pxPerMeter
        drawLine(if (m == 0) axisColor else gridColor, Offset(x, 0f), Offset(x, h), if (m == 0) 2f else 1f)
        m++
    }
    // horizontal lines (constant N)
    val nMin = nL - (h - cy) / pxPerMeter
    val nMax = nL + cy / pxPerMeter
    var k = floor(nMin / step).toInt()
    val kMax = ceil(nMax / step).toInt()
    while (k <= kMax) {
        val y = cy - (k * step - nL) * pxPerMeter
        drawLine(if (k == 0) axisColor else gridColor, Offset(0f, y), Offset(w, y), if (k == 0) 2f else 1f)
        k++
    }

    // scale bar (bottom-left): one grid cell wide
    val barLen = step * pxPerMeter
    val x0 = 50f
    val y0 = h - 60f
    drawLine(Color.DarkGray, Offset(x0, y0), Offset(x0 + barLen, y0), 4f)
    drawLine(Color.DarkGray, Offset(x0, y0 - 10f), Offset(x0, y0 + 10f), 4f)
    drawLine(Color.DarkGray, Offset(x0 + barLen, y0 - 10f), Offset(x0 + barLen, y0 + 10f), 4f)
    val label = if (step < 1.0) "%.2f m".format(step) else "%.0f m".format(step)
    val paint = Paint().apply {
        color = android.graphics.Color.DKGRAY
        textSize = 34f
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(label, x0, y0 - 18f, paint)
}

/** Pick a grid spacing (meters) so one cell is ~90 px on screen. */
private fun niceStepMeters(pxPerMeter: Float): Float {
    val targetPx = 90f
    val raw = targetPx / pxPerMeter // meters for ~90 px
    val steps = floatArrayOf(
        0.05f, 0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f,
    )
    return steps.firstOrNull { it >= raw } ?: 10000f
}

// Solution-quality colors matching RTKLIB rtkplot markerColor[0][q].
fun qColor(q: Int): Color = when (q) {
    1 -> Color(0xFF006400) // FIX    - dark green
    2 -> Color(0xFFFFAA00) // FLOAT  - orange
    3 -> Color(0xFFFF00FF) // SBAS   - magenta
    4 -> Color(0xFF000080) // DGPS   - dark blue
    5 -> Color(0xFFFF0000) // SINGLE - red
    6 -> Color(0xFF808000) // PPP    - olive
    else -> Color(0xFFC0C0C0) // none - silver
}
