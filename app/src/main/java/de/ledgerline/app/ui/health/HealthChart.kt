package de.ledgerline.app.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** One plotted series: x = time (any monotonic units), y = display value. */
data class ChartSeries(val points: List<Pair<Double, Double>>, val color: Color)

/** A faint horizontal reference band [yLow, yHigh] in display units. */
data class ChartBand(val yLow: Double, val yHigh: Double, val color: Color)

/**
 * A minimal, dependency-free time-series line chart drawn on a Compose [Canvas] (the web uses
 * uPlot; Android draws natively so nothing bloats the bundle and it renders offline). Supports one
 * or two series (blood pressure = systolic + diastolic), faint reference bands, an optional goal
 * line, filled area under a single series, and point dots. Auto-scales Y to the data (plus any
 * band) with a little headroom.
 */
@Composable
fun HealthChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    bands: List<ChartBand> = emptyList(),
    goalLine: Double? = null,
    goalColor: Color = Color(0xFF7066F5),
    gridColor: Color = Color(0x14000000),
) {
    val allY = buildList {
        series.forEach { s -> s.points.forEach { add(it.second) } }
        bands.forEach { add(it.yLow); add(it.yHigh) }
        goalLine?.let { add(it) }
    }
    if (series.all { it.points.isEmpty() } || allY.isEmpty()) return

    var minY = allY.min()
    var maxY = allY.max()
    if (minY == maxY) { minY -= 1.0; maxY += 1.0 }
    val padY = (maxY - minY) * 0.12
    minY -= padY; maxY += padY

    // X spans the union of all series' x values.
    val allX = series.flatMap { it.points.map { p -> p.first } }
    val minX = allX.min()
    val maxX = allX.max()
    val spanX = (maxX - minX).takeIf { it > 0 } ?: 1.0
    val spanY = (maxY - minY).takeIf { it > 0 } ?: 1.0

    Canvas(modifier = modifier.fillMaxWidth().height(180.dp)) {
        val w = size.width
        val h = size.height
        fun px(x: Double) = ((x - minX) / spanX * w).toFloat()
        fun py(y: Double) = (h - (y - minY) / spanY * h).toFloat()

        // Reference bands (behind everything).
        bands.forEach { b ->
            val top = py(b.yHigh).coerceIn(0f, h)
            val bottom = py(b.yLow).coerceIn(0f, h)
            drawRect(color = b.color, topLeft = Offset(0f, min(top, bottom)), size = androidx.compose.ui.geometry.Size(w, kotlin.math.abs(bottom - top)))
        }

        // Faint horizontal gridlines (quartiles).
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }

        // Optional goal line (weight).
        goalLine?.let { g ->
            val y = py(g)
            if (y in 0f..h) {
                drawDashedLine(goalColor.copy(alpha = 0.6f), Offset(0f, y), Offset(w, y))
            }
        }

        series.forEachIndexed { idx, s ->
            if (s.points.isEmpty()) return@forEachIndexed
            val pts = s.points.map { Offset(px(it.first), py(it.second)) }

            // Filled area under a single-series line (skip for the second bp series).
            if (series.size == 1) {
                val fill = Path().apply {
                    moveTo(pts.first().x, h)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, h)
                    close()
                }
                drawPath(fill, s.color.copy(alpha = 0.10f))
            }

            val line = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(line, s.color, style = Stroke(width = 2.5f))
            // Dots (small; hidden when there are very many points to avoid clutter).
            if (pts.size <= 60) pts.forEach { drawCircle(s.color, radius = 3f, center = it) }
        }
    }
}

private fun DrawScope.drawDashedLine(color: Color, start: Offset, end: Offset) {
    val total = end.x - start.x
    val dash = 10f
    var x = start.x
    while (x < end.x) {
        val x2 = min(x + dash, end.x)
        drawLine(color, Offset(x, start.y), Offset(x2, end.y), strokeWidth = 1.5f)
        x = x2 + dash
    }
    max(0f, total) // keep the compiler from warning on unused
}
