package net.littlesavage.homevoice.ui

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                ) { current?.let { UiView(it, Modifier.widthIn(max = 900.dp)) } }
            }
        }
    }
}
