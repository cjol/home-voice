package net.littlesavage.homevoice.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.littlesavage.homevoice.protocol.UiMessage

/** What the assistant panel shows for the current exchange. */
data class Exchange(
    val state: String = "listening", // listening | thinking | speaking
    val user: String = "",
    val userFinal: Boolean = false,
    val reply: String = "",
    val replyTurnId: String? = null,
    val ui: UiMessage? = null,
)

/**
 * A compact panel at the bottom of the screen, over whatever app is in front: live transcript,
 * the reply as it's spoken, and any agent UI. Needs "Display over other apps"; it never takes
 * focus or touches outside itself, so the app underneath keeps working. Overlay windows can't
 * show on the lock screen, so when the screen is off/locked agent UI goes to [OverlayActivity].
 */
class AssistantPanel(private val context: Context) {
    private val _exchange = MutableStateFlow<Exchange?>(null)
    val exchange: StateFlow<Exchange?> = _exchange

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private val hide = Runnable { _exchange.value = null; main.postDelayed({ detach() }, 400) }

    /** Overlay possible right now (permission granted, screen on and unlocked). */
    fun canShow(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        return android.provider.Settings.canDrawOverlays(context) && pm.isInteractive && !km.isKeyguardLocked
    }

    fun start() = main.post {
        main.removeCallbacks(hide)
        _exchange.value = Exchange()
        if (canShow()) attach()
    }

    fun update(f: (Exchange) -> Exchange) = main.post {
        main.removeCallbacks(hide)
        _exchange.update { it?.let(f) ?: f(Exchange()) }
        if (view == null && canShow()) attach()
    }

    /** Session over: linger long enough to read the reply (or the UI's ttl). */
    fun finish() = main.post {
        val ui = _exchange.value?.ui
        val linger = if (ui != null) (ui.ttlMs ?: 20_000L).coerceIn(4_000L, 30_000L) else 5_000L
        main.removeCallbacks(hide)
        main.postDelayed(hide, linger)
    }

    fun dismiss() = main.post { main.removeCallbacks(hide); hide.run() }

    private fun attach() {
        if (view != null) return
        val o = OverlayOwner().also { owner = it }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { PanelHost(exchange, onDismiss = { dismiss() }) }
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
        if (_exchange.value != null) return
        view?.let { runCatching { wm.removeView(it) } }
        owner?.destroy()
        view = null
        owner = null
    }
}

@Composable
private fun PanelHost(exchange: StateFlow<Exchange?>, onDismiss: () -> Unit) {
    val ex by exchange.collectAsState()
    var last by remember { mutableStateOf<Exchange?>(null) }
    if (ex != null) last = ex
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = ex != null,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut(),
        ) {
            last?.let { Panel(it, onDismiss) }
        }
    }
}

@Composable
private fun Panel(ex: Exchange, onDismiss: () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(28.dp))
            .background(Color(0xFF2B2930), RoundedCornerShape(28.dp)) // opaque, a step lighter than dark app surfaces
            .border(1.dp, Color(0xFF4A4458), RoundedCornerShape(28.dp))
            .clickable(onClick = onDismiss)
            .animateContentSize()
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateDot(ex.state)
            Text(
                when (ex.state) { "thinking" -> "Thinking…"; "speaking" -> "Speaking"; else -> "Listening…" },
                color = Color(0xFFB0B0B8), fontSize = 15.sp,
            )
        }
        if (ex.user.isNotBlank()) {
            Text(
                ex.user, fontSize = 22.sp,
                color = if (ex.userFinal) Color(0xFFE6E1E5) else Color(0xFF9A96A0),
                fontStyle = if (ex.userFinal) FontStyle.Normal else FontStyle.Italic,
            )
        }
        if (ex.reply.isNotBlank()) Text(ex.reply, fontSize = 22.sp, color = Color(0xFFD0BCFF))
        ex.ui?.let {
            Box(Modifier.fillMaxWidth().background(Color(0xFF0E0E10), RoundedCornerShape(18.dp)).padding(20.dp)) {
                UiView(it, scale = 0.6f)
            }
        }
    }
}

@Composable
private fun StateDot(state: String) {
    val color = when (state) { "thinking" -> Color(0xFFFFB74D); "speaking" -> Color(0xFFD0BCFF); else -> Color(0xFF4FC3F7) }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.7f, targetValue = 1.15f, label = "dot",
        animationSpec = infiniteRepeatable(tween(if (state == "thinking") 400 else 700), RepeatMode.Reverse),
    )
    Box(Modifier.size(14.dp).scale(pulse).background(color, CircleShape))
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
