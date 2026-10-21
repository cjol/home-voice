package net.littlesavage.homevoice.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.littlesavage.homevoice.protocol.UiMessage
import kotlin.math.roundToLong

/**
 * Renders agent UI (shared/src/ui.ts, v1). Hand-written Compose per `kind`; unknown fields are ignored.
 * [scale] 1 = full screen (OverlayActivity); ~0.6 = inside the assistant panel.
 */
@Composable
fun UiView(ui: UiMessage, modifier: Modifier = Modifier, scale: Float = 1f) {
    fun sz(v: Int): TextUnit = (v * scale).sp
    Column(modifier, verticalArrangement = Arrangement.spacedBy((16 * scale).dp)) {
        ui.title?.let { Text(it, fontSize = sz(40), color = Color.White, fontWeight = FontWeight.Medium) }
        when (ui) {
            is UiMessage.Card -> {
                ui.subtitle?.let { Text(it, fontSize = sz(22), color = Color.LightGray) }
                Text(ui.body, fontSize = sz(28), color = toneColor(ui.tone))
                // TODO: load ui.imageUrl (add Coil).
            }
            is UiMessage.ListUi -> Column(verticalArrangement = Arrangement.spacedBy((12 * scale).dp)) {
                ui.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontSize = sz(26), color = Color.White)
                            item.subtitle?.let { Text(it, fontSize = sz(18), color = Color.Gray) }
                        }
                        item.trailing?.let { Text(it, fontSize = sz(22), color = Color.LightGray) }
                    }
                }
            }
            is UiMessage.Status -> {
                Text(ui.text, fontSize = sz(30), color = toneColor(ui.tone))
                ui.progress?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
            }
            is UiMessage.Chart -> ChartView(ui, scale)
            is UiMessage.Table -> TableView(ui, scale)
        }
    }
}

@Composable
private fun ChartView(ui: UiMessage.Chart, scale: Float) {
    val palette = listOf(Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFE57373))
    val maxY = niceMax(ui.series.flatMap { it.points }.maxOfOrNull { it.y } ?: 1.0)
    val ticks = (0..4).map { maxY * it / 4 }
    val label = { v: Double -> fmt(v) }
    Column {
        Row(Modifier.fillMaxWidth().height((320 * scale).dp)) {
            // y-axis labels, top to bottom
            Column(Modifier.fillMaxHeight().padding(end = 8.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                ticks.reversed().forEach { Text(label(it), color = Color.Gray, fontSize = (14 * scale).sp) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                ticks.forEach { t ->
                    val y = size.height * (1 - (t / maxY).toFloat())
                    drawLine(Color(0x33FFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f,
                        pathEffect = if (t == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                }
                ui.series.forEachIndexed { si, s ->
                    val color = palette[si % palette.size]
                    val step = size.width / s.points.size.coerceAtLeast(1)
                    if (ui.chartType == "line") {
                        val path = Path()
                        s.points.forEachIndexed { i, p ->
                            val o = Offset(step * i + step / 2, size.height * (1 - (p.y / maxY).toFloat()))
                            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                            drawCircle(color, 5f, o)
                        }
                        drawPath(path, color, style = Stroke(width = 5f))
                    } else {
                        val barW = step / (ui.series.size + 1)
                        s.points.forEachIndexed { i, p ->
                            val h = size.height * (p.y / maxY).toFloat()
                            drawRect(color, Offset(step * i + barW * si + barW / 2, size.height - h), Size(barW, h))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = (48 * scale).dp), horizontalArrangement = Arrangement.SpaceAround) {
            ui.series.firstOrNull()?.points?.forEach { Text(it.x, color = Color.Gray, fontSize = (16 * scale).sp) }
        }
        val legend = listOfNotNull(ui.unit, ui.series.takeIf { it.size > 1 }?.joinToString("  ·  ") { it.label })
        if (legend.isNotEmpty()) Text(legend.joinToString("   "), color = Color.Gray, fontSize = (14 * scale).sp)
    }
}

@Composable
private fun TableView(ui: UiMessage.Table, scale: Float) {
    val n = ui.columns.size.coerceAtLeast(ui.rows.maxOfOrNull { it.size } ?: 1)
    // First column (labels) gets more room than the numeric ones.
    val weights = List(n) { if (it == 0) 2f else 1f }
    val endAligned = { i: Int -> ui.columns.getOrNull(i)?.align == "end" }
    @Composable
    fun RowOf(cells: List<String>, color: Color, size: Int, weight: FontWeight = FontWeight.Normal, bg: Color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().background(bg, RoundedCornerShape((8 * scale).dp)).padding(vertical = (10 * scale).dp, horizontal = (12 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until n) {
                Text(
                    cells.getOrElse(i) { "" }, Modifier.weight(weights[i]), color = color, fontSize = (size * scale).sp,
                    fontWeight = weight, textAlign = if (endAligned(i)) TextAlign.End else TextAlign.Start,
                )
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy((2 * scale).dp)) {
        RowOf(ui.columns.map { it.label }, Color(0xFFB0B0B8), 18, FontWeight.Medium)
        HorizontalDivider(color = Color(0x33FFFFFF))
        ui.rows.forEachIndexed { i, r -> RowOf(r, Color.White, 26, bg = if (i % 2 == 1) Color(0x0FFFFFFF) else Color.Transparent) }
        ui.footer?.let {
            HorizontalDivider(color = Color(0x66FFFFFF))
            RowOf(it, Color(0xFFD0BCFF), 28, FontWeight.SemiBold)
        }
        ui.note?.let { Text(it, color = Color.Gray, fontSize = (15 * scale).sp, modifier = Modifier.padding(top = (8 * scale).dp)) }
    }
}

/** Round the axis maximum up to 1, 2 or 5 x 10^n. */
private fun niceMax(v: Double): Double {
    if (v <= 0) return 1.0
    val mag = Math.pow(10.0, Math.floor(Math.log10(v)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= v }
}

private fun fmt(v: Double): String = when {
    v >= 1_000_000 -> "${(v / 100_000).roundToLong() / 10.0}M"
    v >= 10_000 -> "${(v / 1000).roundToLong()}k"
    v >= 1000 -> "${(v / 100).roundToLong() / 10.0}k"
    v == Math.floor(v) -> v.toLong().toString()
    else -> "%.1f".format(v)
}

internal fun toneColor(tone: String?) = when (tone) {
    "success" -> Color(0xFF81C784)
    "warning" -> Color(0xFFFFB74D)
    "error" -> Color(0xFFE57373)
    "info" -> Color(0xFF4FC3F7)
    else -> Color.White
}
