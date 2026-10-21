package net.littlesavage.homevoice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.littlesavage.homevoice.R
import net.littlesavage.homevoice.Settings
import net.littlesavage.homevoice.audio.MicCapture
import net.littlesavage.homevoice.audio.PcmPlayer
import net.littlesavage.homevoice.net.AgentClient
import net.littlesavage.homevoice.protocol.ClientMessage
import net.littlesavage.homevoice.protocol.ServerMessage
import net.littlesavage.homevoice.protocol.UiMessage
import net.littlesavage.homevoice.ui.MainActivity
import net.littlesavage.homevoice.ui.OverlayActivity
import net.littlesavage.homevoice.ui.UiBus
import net.littlesavage.homevoice.wakeword.OpenWakeWord

/**
 * Always-on listener. Microphone foreground service with a persistent
 * notification; keeps a partial wake lock so capture continues with the screen off.
 *
 *   IDLE      mic -> wake word only, nothing leaves the device
 *   SESSION   mic -> WebSocket (Flux on the server detects end of turn).
 *             Wake word still runs locally: hearing it during playback = barge-in.
 *             Session ends after FOLLOW_UP_MS of nothing happening.
 */
class VoiceService : LifecycleService() {

    data class Status(val connected: Boolean = false, val mode: String = "stopped", val agent: String = "idle", val lastTranscript: String = "", val lastReply: String = "", val wakeScore: Float = 0f)

    companion object {
        private const val TAG = "VoiceService"
        private const val CHANNEL_SERVICE = "service"
        const val CHANNEL_ALERTS = "alerts"
        private const val NOTIF_ID = 1
        private const val FOLLOW_UP_MS = 8_000L
        private const val NO_SPEECH_MS = 6_000L

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, VoiceService::class.java))
    }

    private lateinit var settings: Settings
    private var client: AgentClient? = null
    private var mic: MicCapture? = null
    private var player: PcmPlayer? = null
    private var wakeWord: OpenWakeWord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }

    @Volatile private var inSession = false
    @Volatile private var deadline = 0L
    @Volatile private var agentState = "idle"

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (mic == null) startPipeline()
        return START_STICKY
    }

    private fun startPipeline() {
        if (!settings.isConfigured) {
            updateNotification("Not configured: open the app")
            return
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homevoice:mic").apply { acquire() }

        wakeWord = runCatching { OpenWakeWord(this, settings.wakeThreshold) }
            .onFailure { Log.e(TAG, "wake word models missing? run scripts/fetch-models.sh", it) }
            .getOrNull()

        player = PcmPlayer { turnId ->
            client?.send(ClientMessage.PlaybackDone(turnId))
            deadline = SystemClock.elapsedRealtime() + FOLLOW_UP_MS // listen for a follow-up
        }
        client = AgentClient(settings.serverUrl, settings.deviceId, settings.token, agentListener).also { it.connect() }
        mic = MicCapture(::onFrame).also { it.start() }
        publish { it.copy(mode = "listening for wake word") }
        updateNotification("Listening for wake word")
    }

    // Runs on the mic thread every 80 ms.
    private fun onFrame(frame: ShortArray) {
        val heard = wakeWord?.process(frame) == true
        if (!inSession) {
            if (heard) beginSession()
            return
        }
        client?.sendAudio(frame)
        if (heard && player?.isPlaying == true) {
            // Local barge-in.
            player?.stop()
            client?.send(ClientMessage.Interrupt)
            deadline = SystemClock.elapsedRealtime() + NO_SPEECH_MS
        }
        val busy = agentState == "thinking" || agentState == "speaking" || player?.isPlaying == true
        if (!busy && SystemClock.elapsedRealtime() > deadline) endSession("timeout")
    }

    private fun beginSession() {
        val c = client
        if (c == null || !c.connected) {
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 150)
            return
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        inSession = true
        deadline = SystemClock.elapsedRealtime() + NO_SPEECH_MS
        c.send(ClientMessage.StartSession(wakeWord = "hey_jarvis"))
        publish { it.copy(mode = "in conversation") }
        updateNotification("Listening…")
    }

    private fun endSession(reason: String) {
        if (!inSession) return
        inSession = false
        client?.send(ClientMessage.EndSession(reason))
        publish { it.copy(mode = "listening for wake word") }
        updateNotification("Listening for wake word")
    }

    private val agentListener = object : AgentClient.Listener {
        override fun onConnected() = publish { it.copy(connected = true) }
        override fun onDisconnected() {
            publish { it.copy(connected = false) }
            player?.stop()
            inSession = false
        }
        override fun onAudio(bytes: ByteArray) { player?.write(bytes) }
        override fun onMessage(msg: ServerMessage) {
            when (msg) {
                is ServerMessage.State -> {
                    agentState = msg.state
                    publish { it.copy(agent = msg.state) }
                }
                is ServerMessage.Transcript -> {
                    deadline = SystemClock.elapsedRealtime() + NO_SPEECH_MS
                    publish { it.copy(lastTranscript = msg.text) }
                }
                is ServerMessage.AssistantText -> publish { it.copy(lastReply = msg.text) }
                is ServerMessage.TtsStart -> player?.begin(msg.sampleRate)
                is ServerMessage.TtsEnd -> player?.end(msg.turnId)
                is ServerMessage.StopPlayback -> player?.stop()
                is ServerMessage.Ui -> showUi(msg.ui)
                is ServerMessage.DismissUi -> UiBus.dismiss(msg.uiId)
                is ServerMessage.AuthRequired -> authNotification(msg.url)
                is ServerMessage.Error -> Log.e(TAG, "agent error: ${msg.message}")
                is ServerMessage.Ready -> Log.i(TAG, "ready model=${msg.model} tools=${msg.tools}")
                ServerMessage.Pong -> Unit
            }
        }
    }

    private fun showUi(ui: UiMessage) {
        UiBus.show(ui)
        OverlayActivity.bringToFront(this, ui.title ?: "Home Voice")
    }

    private fun publish(f: (Status) -> Status) {
        _status.value = f(_status.value).copy(wakeScore = wakeWord?.lastScore ?: 0f)
    }

    // ---- notifications -------------------------------------------------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Assistant screens", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle("Home Voice")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))

    private fun authNotification(url: String) {
        val pi = PendingIntent.getActivity(this, 2, Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(
            3,
            NotificationCompat.Builder(this, CHANNEL_ALERTS).setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Connect Executor").setContentText("Tap to sign in so the assistant can use your apps")
                .setContentIntent(pi).setAutoCancel(true).build(),
        )
    }

    override fun onDestroy() {
        mic?.stop(); mic = null
        player?.release(); player = null
        client?.close(); client = null
        wakeWord?.close(); wakeWord = null
        wakeLock?.takeIf { it.isHeld }?.release()
        publish { Status() }
        super.onDestroy()
    }
}
