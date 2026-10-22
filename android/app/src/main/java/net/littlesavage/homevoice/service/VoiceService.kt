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
import net.littlesavage.homevoice.ui.AssistantPanel
import net.littlesavage.homevoice.ui.Conversation
import net.littlesavage.homevoice.ui.MainActivity
import net.littlesavage.homevoice.ui.OverlayActivity
import net.littlesavage.homevoice.ui.StartActivity
import net.littlesavage.homevoice.ui.UiBus
import net.littlesavage.homevoice.wakeword.OpenWakeWord

/**
 * Always-on listener. Microphone foreground service with a persistent
 * notification; keeps a partial wake lock so capture continues with the screen off.
 *
 *   IDLE      mic -> wake word only, nothing leaves the device
 *   SESSION   mic -> WebSocket (Flux on the server detects end of turn).
 *             Manual interruption only; microphone recognition pauses during replies.
 *             Session ends after FOLLOW_UP_MS of nothing happening.
 */
class VoiceService : LifecycleService() {

    data class Status(val connected: Boolean = false, val mode: String = "stopped", val agent: String = "idle", val lastTranscript: String = "", val lastReply: String = "", val wakeScore: Float = 0f, val lastWakeScore: Float = 0f)

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

        private const val ACTION_STOP = "net.littlesavage.homevoice.STOP"

        private const val ACTION_TEXT = "net.littlesavage.homevoice.TEXT"
        private const val ACTION_INJECT = "net.littlesavage.homevoice.INJECT"

        /** Debug: feed a PCM file into the mic path in place of live audio. */
        fun injectPcm(ctx: Context, path: String) =
            ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_INJECT).putExtra("path", path))

        /** Typed request: runs a turn without STT (handy on the emulator). */
        fun sendText(ctx: Context, text: String) =
            ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_TEXT).putExtra("text", text))
    }

    private lateinit var settings: Settings
    private lateinit var panel: AssistantPanel
    private var client: AgentClient? = null
    private var mic: MicCapture? = null
    private var player: PcmPlayer? = null
    private var wakeWord: OpenWakeWord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }

    private val preRoll = ArrayDeque<ShortArray>()
    @Volatile private var suppressMicUntil = 0L
    private val silentFrame = ShortArray(1280)

    @Volatile private var inSession = false
    @Volatile private var deadline = 0L
    @Volatile private var agentState = "idle"
    @Volatile private var replyTurnId: String? = null

    /** Stop the reply and tell the agent how much of it was heard, so its history matches. */
    private fun stopReply() {
        val turnId = replyTurnId ?: return
        val playing = player?.isPlaying == true
        val played = player?.stop() ?: 0
        if (playing) client?.send(ClientMessage.Played(turnId, played))
        replyTurnId = null
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        panel = AssistantPanel(this)
        UiBus.onDismissed = { id -> client?.send(ClientMessage.UiEvent(uiId = id, action = "dismissed")) }
        UiBus.onInterrupt = {
            client?.send(ClientMessage.Interrupt)
            stopReply()
            if (!inSession) beginSession()
            agentState = "listening"
            Conversation.setState("listening")
            suppressMicUntil = SystemClock.elapsedRealtime() + 600L
            deadline = suppressMicUntil + FOLLOW_UP_MS
        }
        UiBus.onConversationClosed = {
            client?.send(ClientMessage.Interrupt)
            stopReply()
            endSession("dismissed")
        }
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) settings.enabled = false
        if (!settings.enabled) {
            getSystemService(NotificationManager::class.java).cancel(4)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14+ refuses a microphone FGS started from the background (e.g. a START_STICKY
            // restart after the process was killed). Re-launch from a visible activity instead.
            Log.w(TAG, "can't start mic service from background: ${e.message}")
            resumeFromForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (mic == null) startPipeline()
        when (intent?.action) {
            ACTION_TEXT -> intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let { text ->
                if (!inSession) beginSession(sendStart = false)
                client?.send(ClientMessage.TextInput(text))
                publish { it.copy(lastTranscript = text) }
                Conversation.utterance(text, final = true)
                panel.show()
            }
            ACTION_INJECT -> intent.getStringExtra("path")?.let { path ->
                runCatching { java.io.File(path).readBytes() }
                    .onSuccess { mic?.inject(it); Log.i(TAG, "injected ${it.size / 32000.0}s from $path") }
                    .onFailure { Log.e(TAG, "inject failed", it) }
            }
        }
        return START_STICKY
    }

    /** "Display over other apps" lets us start the invisible StartActivity; otherwise ask the user to tap. */
    private fun resumeFromForeground() {
        val start = Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.provider.Settings.canDrawOverlays(this) && runCatching { startActivity(start) }.isSuccess) return
        getSystemService(NotificationManager::class.java).notify(
            4,
            NotificationCompat.Builder(this, CHANNEL_ALERTS).setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Home Voice stopped listening").setContentText("Tap to resume")
                .setContentIntent(PendingIntent.getActivity(this, 4, start, PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build(),
        )
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
            agentState = "listening"
            Conversation.setState("listening")
            suppressMicUntil = SystemClock.elapsedRealtime() + 600L
            deadline = suppressMicUntil + FOLLOW_UP_MS // only after actual playback drains
        }
        client = AgentClient(settings.serverUrl, settings.deviceId, settings.token, agentListener).also { it.connect() }
        mic = MicCapture(::onFrame).also { it.start() }
        publish { it.copy(mode = "listening for wake word") }
        updateNotification("Listening for wake word")
    }

    // Runs on the mic thread every 80 ms.
    private fun onFrame(frame: ShortArray) {
        // Half duplex: AEC on some phones still passes speaker audio through. Never
        // feed that audio to either recognizer, including the speaker/room tail.
        val now = SystemClock.elapsedRealtime()
        if (player?.isPlaying == true) suppressMicUntil = now + 600L
        if (now < suppressMicUntil) {
            if (inSession) client?.sendAudio(silentFrame)
            return
        }
        if (!inSession) {
            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 25) preRoll.removeFirst() // two seconds before detection
            if (wakeWord?.process(frame) == true) {
                beginSession()
                if (inSession) preRoll.forEach { client?.sendAudio(it) }
                preRoll.clear()
            }
            return
        }
        // Manual interruption only. Ignore speech while generating or playing a reply.
        if (agentState == "thinking" || agentState == "speaking") {
            client?.sendAudio(silentFrame)
            return
        }
        client?.sendAudio(frame)
        val busy = agentState == "thinking" || agentState == "speaking" || player?.isPlaying == true
        if (!busy && SystemClock.elapsedRealtime() > deadline) endSession("timeout")
    }

    private fun beginSession(sendStart: Boolean = true) {
        val c = client
        if (c == null || !c.connected) {
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 150)
            return
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        Log.i(TAG, "session start (wake score ${wakeWord?.lastScore})")
        if (sendStart) publish { it.copy(lastWakeScore = wakeWord?.lastScore ?: 0f) }
        inSession = true
        deadline = SystemClock.elapsedRealtime() + NO_SPEECH_MS
        if (sendStart) c.send(ClientMessage.StartSession(wakeWord = "hey_jarvis"))
        Conversation.begin()
        if (panel.canShow()) {
            panel.show()
        } else {
            // Application overlays are hidden by the keyguard. Start the lock-screen
            // capable activity immediately, even when the agent hasn't sent a card.
            UiBus.panel.value?.let { UiBus.showFullscreen(it) }
            ContextCompat.getMainExecutor(this).execute {
                OverlayActivity.bringToFront(this, "Home Voice")
            }
        }
        publish { it.copy(mode = "in conversation") }
        updateNotification("Listening…")
    }

    private fun endSession(reason: String) {
        if (!inSession) return
        inSession = false
        Log.i(TAG, "session end: $reason")
        Conversation.end()
        panel.finish()
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
                    // Back to listening (after a reply or a barge-in): give the user a fresh window to speak.
                    if (msg.state == "listening" && agentState != "listening") deadline = maxOf(deadline, SystemClock.elapsedRealtime() + NO_SPEECH_MS)
                    agentState = if (player?.isPlaying == true) "speaking" else msg.state
                    publish { it.copy(agent = msg.state) }
                    if (inSession) Conversation.setState(agentState)
                }
                is ServerMessage.Transcript -> {
                    if (msg.final) Log.i(TAG, "you: ${msg.text}") else Log.d(TAG, "partial: ${msg.text}")
                    deadline = SystemClock.elapsedRealtime() + NO_SPEECH_MS
                    publish { it.copy(lastTranscript = msg.text) }
                    Conversation.utterance(msg.text, msg.final)
                }
                is ServerMessage.AssistantText -> {
                    Log.i(TAG, "assistant: ${msg.text}")
                    publish { it.copy(lastReply = msg.text) }
                    Conversation.reply(msg.turnId, msg.text)
                    UiBus.transcriptHidden.value = false // a real exchange: bring the conversation back
                }
                is ServerMessage.TtsStart -> {
                    Log.i(TAG, "tts start")
                    agentState = "speaking"
                    if (inSession) Conversation.setState("speaking")
                    replyTurnId = msg.turnId
                    player?.begin(msg.sampleRate)
                }
                is ServerMessage.TtsEnd -> player?.end(msg.turnId)
                is ServerMessage.StopPlayback -> {
                    Log.i(TAG, "barge-in: stop playback")
                    stopReply()
                    deadline = SystemClock.elapsedRealtime() + NO_SPEECH_MS
                }
                is ServerMessage.Ui -> { Log.i(TAG, "ui ${msg.ui}"); showUi(msg.ui) }
                is ServerMessage.DismissUi -> UiBus.dismiss(msg.uiId)
                is ServerMessage.AuthRequired -> authNotification(msg.url)
                is ServerMessage.Error -> Log.e(TAG, "agent error: ${msg.message}")
                is ServerMessage.Ready -> {
                    Log.i(TAG, "ready model=${msg.model} tools=${msg.tools}")
                    client?.send(ClientMessage.UiState(UiBus.openIds()))
                }
                ServerMessage.Ignored -> {
                    // Not meant for the agent (false wake, background talk): drop it and stop listening.
                    Log.i(TAG, "agent ignored the last utterance")
                    Conversation.dropLast()
                    endSession("ignored")
                }
                ServerMessage.NewConversation -> Conversation.startFresh()
                ServerMessage.Pong -> Unit
            }
        }
    }

    /**
     * display = "fullscreen", or the screen is locked/off: full screen (persistent until closed if ttl 0).
     * Otherwise inside the panel over the current app. Same id = replace in place.
     */
    private fun showUi(ui: UiMessage) {
        if (ui.display == "fullscreen" || !panel.canShow()) {
            UiBus.showFullscreen(ui)
            OverlayActivity.bringToFront(this, ui.title ?: "Home Voice")
        } else {
            UiBus.showPanel(ui)
            panel.show()
        }
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
        .addAction(0, "Stop listening", PendingIntent.getService(
            this, 5, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))
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
        inSession = false
        Conversation.end()
        UiBus.dismiss()
        Conversation.clear()
        UiBus.onInterrupt = null
        UiBus.onConversationClosed = null
        panel.dismiss()
        mic?.stop(); mic = null
        player?.release(); player = null
        client?.close(); client = null
        wakeWord?.close(); wakeWord = null
        wakeLock?.takeIf { it.isHeld }?.release()
        publish { Status() }
        super.onDestroy()
    }
}
