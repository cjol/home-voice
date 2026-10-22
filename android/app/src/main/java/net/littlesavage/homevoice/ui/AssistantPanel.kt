package net.littlesavage.homevoice.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import net.littlesavage.homevoice.R
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import net.littlesavage.homevoice.protocol.UiMessage

/**
 * A compact panel at the bottom of the screen, over whatever app is in front: the conversation so
 * far (live transcript, replies as they're spoken) and any panel UI. Needs "Display over other
 * apps"; it never takes focus or touches outside itself. Android hides third-party overlays over
 * Settings and permission dialogs, and they can't show on the lock screen (full screen covers that).
 */
class AssistantPanel(private val context: Context) {
    private val visible = MutableStateFlow(false)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private val hide = Runnable {
        visible.value = false
        // A full-screen screen (e.g. a running table) keeps its conversation until it's closed.
        if (UiBus.fullscreen.value == null) Conversation.clear()
        main.postDelayed({ detach() }, 400)
    }

    /** Overlay possible right now (permission granted, screen on and unlocked). */
    fun canShow(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        return android.provider.Settings.canDrawOverlays(context) && pm.isInteractive && !km.isKeyguardLocked
    }

    /** Show (or keep showing) the panel. */
    fun show() = main.post {
        main.removeCallbacks(hide)
        visible.value = true
        if (view == null && canShow()) attach()
    }

    /** Session over: linger so the conversation can be read, longer if UI is up. */
    fun finish() = main.post {
        val ui = UiBus.panel.value
        val linger = when {
            ui == null -> LINGER_MS
            ui.ttlMs == 0L -> Long.MAX_VALUE // persistent panel UI: until closed
            else -> maxOf(LINGER_MS, ui.ttlMs ?: 20_000L)
        }
        main.removeCallbacks(hide)
        if (linger != Long.MAX_VALUE) main.postDelayed(hide, linger)
    }

    fun dismiss() = main.post {
        UiBus.panel.value?.let { UiBus.closeLocally(it) }
        main.removeCallbacks(hide)
        hide.run()
    }

    private fun attach() {
        if (view != null) return
        val o = OverlayOwner().also { owner = it }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { PanelHost(visible, onDismiss = { UiBus.onConversationClosed?.invoke(); dismiss() }) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        runCatching { wm.addView(v, lp) }.onSuccess { view = v; o.resume() }
    }

    private fun detach() {
        if (visible.value) return
        view?.let { runCatching { wm.removeView(it) } }
        owner?.destroy()
        view = null
        owner = null
    }

    companion object {
        const val LINGER_MS = 12_000L
    }
}

@Composable
private fun PanelHost(visible: MutableStateFlow<Boolean>, onDismiss: () -> Unit) {
    val shown by visible.collectAsState()
    val convo by Conversation.state.collectAsState()
    val ui by UiBus.panel.collectAsState()
    val fullscreen by UiBus.fullscreenVisible.collectAsState()
    // With a full-screen view up, the conversation is shown there; the panel only appears for its own UI.
    val show = shown && (!fullscreen || ui != null) && (convo.turns.isNotEmpty() || convo.active || ui != null)
    ui?.let { u -> LaunchedEffect(u) { val ttl = u.ttlMs ?: 20_000L; if (ttl > 0) { delay(ttl); UiBus.closeLocally(u) } } }
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = show, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
            val hidden by UiBus.transcriptHidden.collectAsState()
            Panel(convo, if (fullscreen || (hidden && ui != null)) emptyList() else convo.turns, ui, onDismiss)
        }
    }
}

@Composable
private fun Panel(convo: ConversationState, turns: List<Turn>, ui: UiMessage?, onDismiss: () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(28.dp))
            .background(Color(0xFF2B2930), RoundedCornerShape(28.dp)) // opaque, a step lighter than dark app surfaces
            .border(1.dp, Color(0xFF4A4458), RoundedCornerShape(28.dp))
            .heightIn(max = 440.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateLine(convo)
        Transcript(turns, maxTurns = if (ui != null) 1 else 3, modifier = Modifier.weight(1f, fill = false))
        ui?.let {
            Box(Modifier.fillMaxWidth().background(Color(0xFF0E0E10), RoundedCornerShape(18.dp)).padding(20.dp)) {
                UiView(it, scale = 0.6f)
            }
        }
        ConversationControls(convo, hasUi = ui != null, onEnd = onDismiss)
    }
}

/** Status dot + label; "Done" once the session has ended (the panel is lingering). */
@Composable
fun StateLine(convo: ConversationState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StateDot(if (convo.active) convo.state else "done")
        Text(
            if (!convo.active) "Say \"Hey Jarvis\" to continue"
            else when (convo.state) { "thinking" -> "Thinking…"; "speaking" -> "Speaking"; else -> "Listening…" },
            color = Color(0xFFB0B0B8), fontSize = 15.sp,
        )
    }
}

/**
 * One bottom action: stop speech first, then end the listening session on the next tap.
 * With a generated screen up, a toggle on the left hides the conversation (ending it) or shows it again.
 */
@Composable
fun ConversationControls(convo: ConversationState, hasUi: Boolean = false, onEnd: () -> Unit = { UiBus.onConversationClosed?.invoke() }) {
    val speaking = convo.active && convo.state == "speaking"
    val hidden by UiBus.transcriptHidden.collectAsState()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (hasUi) {
            TextButton(onClick = { if (hidden) UiBus.transcriptHidden.value = false else UiBus.hideTranscript() }) {
                Text(if (hidden) "Show conversation" else "Hide conversation", color = Color(0xFFB0B0B8), fontSize = 15.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { if (speaking) UiBus.onInterrupt?.invoke() else onEnd() },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color(0xFF494354), contentColor = Color(0xFFEADDFF),
            ),
        ) {
            Icon(
                painterResource(if (speaking) R.drawable.ic_stop_reply else R.drawable.ic_end_conversation),
                contentDescription = if (speaking) "Stop reply" else "End conversation",
            )
        }
    }
}

/** Follow content after layout, including a viewport resize or another streamed sentence. */
@Composable
fun Transcript(turns: List<Turn>, maxTurns: Int, textSize: Int = 22, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(turns) {
        snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    Column(modifier.verticalScroll(scroll)) {
        ConversationTurns(turns, maxTurns, textSize)
    }
}

/** The last few turns; older ones dimmed. */
@Composable
fun ConversationTurns(turns: List<Turn>, maxTurns: Int, textSize: Int = 22) {
    val shown = turns.takeLast(maxTurns)
    shown.forEachIndexed { i, t ->
        val dim = if (i < shown.size - 1) 0.55f else 1f
        Column(Modifier.alpha(dim), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (t.user.isNotBlank()) {
                Text(
                    t.user, fontSize = textSize.sp,
                    color = if (t.userFinal) Color(0xFFE6E1E5) else Color(0xFF9A96A0),
                    fontStyle = if (t.userFinal) FontStyle.Normal else FontStyle.Italic,
                )
            }
            if (t.reply.isNotBlank()) Text(t.reply.trim(), fontSize = textSize.sp, color = Color(0xFFD0BCFF))
        }
    }
}

@Composable
private fun StateDot(state: String) {
    val color = when (state) {
        "thinking" -> Color(0xFFFFB74D); "speaking" -> Color(0xFFD0BCFF); "done" -> Color(0xFF6B6870); else -> Color(0xFF4FC3F7)
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.7f, targetValue = 1.15f, label = "dot",
        animationSpec = infiniteRepeatable(tween(if (state == "thinking") 400 else 700), RepeatMode.Reverse),
    )
    Box(Modifier.size(14.dp).scale(if (state == "done") 1f else pulse).background(color, CircleShape))
}

/** Lifecycle + saved state for a ComposeView that lives in a window owned by a service. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
