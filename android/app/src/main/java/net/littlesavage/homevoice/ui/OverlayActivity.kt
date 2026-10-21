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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import net.littlesavage.homevoice.R
import net.littlesavage.homevoice.protocol.UiMessage
import net.littlesavage.homevoice.service.VoiceService

/** Full-screen agent UI (display = "fullscreen", or any UI while the screen is locked), with the conversation underneath. */
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(NotificationManager::class.java).cancel(FSI_NOTIF_ID)
        setContent {
            val ui by UiBus.fullscreen.collectAsState()
            val convo by Conversation.state.collectAsState()
            val current = ui
            // Keyed on the content, so a replacement (same id, new rows) restarts the timer.
            LaunchedEffect(current) {
                val ttl = current?.ttlMs ?: 0L
                if (ttl > 0) { delay(ttl); current?.let { UiBus.closeLocally(it) } }
            }
            LaunchedEffect(current, convo.active) {
                if (current == null && !convo.active) finish()
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                val compact = LocalConfiguration.current.screenWidthDp < 600
                Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(if (compact) 12.dp else 24.dp)) {
                    if (current != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        // Explicit close: a stray tap shouldn't wipe a table you're building.
                        FilledTonalIconButton(onClick = { current?.let { UiBus.closeLocally(it) } }, modifier = Modifier.size(56.dp)) {
                            Text("✕", fontSize = 24.sp)
                        }
                    }
                    if (current != null) {
                        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                            UiView(current, Modifier.widthIn(max = 1100.dp).fillMaxWidth(), scale = if (compact) 0.65f else 1f)
                        }
                    } else {
                        Text("Home Voice", color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    if (convo.turns.isNotEmpty() || convo.active) {
                        Column(
                            Modifier.fillMaxWidth()
                                .then(if (current == null) Modifier.weight(1f) else Modifier.heightIn(max = if (compact) 260.dp else 340.dp))
                                .padding(top = 12.dp)
                                .background(Color(0xFF1C1B1F), RoundedCornerShape(24.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StateLine(convo)
                            Transcript(convo.turns, maxTurns = 3, textSize = if (compact) 16 else 20,
                                modifier = Modifier.weight(1f, fill = current == null))
                            ConversationControls(convo)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); UiBus.fullscreenVisible.value = true }
    override fun onPause() { super.onPause(); UiBus.fullscreenVisible.value = false }

    @Deprecated("Back closes the screen")
    override fun onBackPressed() {
        UiBus.fullscreen.value?.let { UiBus.closeLocally(it) } ?: run {
            UiBus.onConversationClosed?.invoke()
            super.onBackPressed()
        }
    }
}
