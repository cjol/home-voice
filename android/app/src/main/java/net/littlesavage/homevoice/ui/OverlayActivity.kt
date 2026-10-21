package net.littlesavage.homevoice.ui

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay
import net.littlesavage.homevoice.R
import net.littlesavage.homevoice.protocol.UiMessage
import net.littlesavage.homevoice.service.VoiceService

/** Full-screen renderer for agent UI. Dismisses itself after the message's ttl. */
class OverlayActivity : ComponentActivity() {
    companion object {
        private const val DEFAULT_TTL_MS = 20_000L
        private const val FSI_NOTIF_ID = 2

        /**
         * Bring the overlay to the front from the service. With "Display over other apps"
         * we may start activities from the background directly; otherwise use a
         * full-screen-intent notification (shows full screen when locked/screen off,
         * as a heads-up while another app is in use).
         */
        fun bringToFront(ctx: Context, title: String) {
            val intent = Intent(ctx, OverlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (android.provider.Settings.canDrawOverlays(ctx)) {
                ctx.startActivity(intent)
                return
            }
            val pi = PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            ctx.getSystemService(NotificationManager::class.java).notify(
                FSI_NOTIF_ID,
                NotificationCompat.Builder(ctx, VoiceService.CHANNEL_ALERTS).setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle(title).setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setFullScreenIntent(pi, true)
                    .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(DEFAULT_TTL_MS).build(),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(NotificationManager::class.java).cancel(FSI_NOTIF_ID)
        setContent {
            val ui by UiBus.current.collectAsState()
            val current = ui
            LaunchedEffect(current?.id) {
                if (current == null) { finish(); return@LaunchedEffect }
                val ttl = current.ttlMs ?: DEFAULT_TTL_MS
                if (ttl > 0) { delay(ttl); UiBus.dismiss(current.id) }
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black).clickable { UiBus.dismiss() }.padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { current?.let { UiView(it) } }
            }
        }
    }
}

@Composable
fun UiView(ui: UiMessage) {
    Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ui.title?.let { Text(it, fontSize = 40.sp, color = Color.White) }
        when (ui) {
            is UiMessage.Card -> {
                ui.subtitle?.let { Text(it, fontSize = 22.sp, color = Color.LightGray) }
                Text(ui.body, fontSize = 28.sp, color = toneColor(ui.tone))
                // TODO: load ui.imageUrl (add Coil).
            }
            is UiMessage.ListUi -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ui.items) { item ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontSize = 26.sp, color = Color.White)
                            item.subtitle?.let { Text(it, fontSize = 18.sp, color = Color.Gray) }
                        }
                        item.trailing?.let { Text(it, fontSize = 22.sp, color = Color.LightGray) }
                    }
                }
            }
            is UiMessage.Status -> {
                Text(ui.text, fontSize = 30.sp, color = toneColor(ui.tone))
                ui.progress?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
            }
            is UiMessage.Chart -> ChartView(ui)
        }
    }
}

@Composable
private fun ChartView(ui: UiMessage.Chart) {
    val palette = listOf(Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFE57373))
    val maxY = ui.series.flatMap { it.points }.maxOfOrNull { it.y }?.takeIf { it > 0 } ?: 1.0
    Column {
        Canvas(Modifier.fillMaxWidth().height(320.dp)) {
            ui.series.forEachIndexed { si, s ->
                val color = palette[si % palette.size]
                val n = s.points.size.coerceAtLeast(1)
                val step = size.width / n
                if (ui.chartType == "line") {
                    val path = Path()
                    s.points.forEachIndexed { i, p ->
                        val o = Offset(step * i + step / 2, size.height * (1 - (p.y / maxY).toFloat()))
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, color, style = Stroke(width = 6f))
                } else {
                    val barW = step / (ui.series.size + 1)
                    s.points.forEachIndexed { i, p ->
                        val h = size.height * (p.y / maxY).toFloat()
                        drawRect(color, Offset(step * i + barW * si + barW / 2, size.height - h), Size(barW, h))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            ui.series.firstOrNull()?.points?.forEach { Text(it.x, color = Color.Gray, fontSize = 16.sp) }
        }
        ui.unit?.let { Text(it, color = Color.Gray) }
    }
}

private fun toneColor(tone: String?) = when (tone) {
    "success" -> Color(0xFF81C784)
    "warning" -> Color(0xFFFFB74D)
    "error" -> Color(0xFFE57373)
    "info" -> Color(0xFF4FC3F7)
    else -> Color.White
}
