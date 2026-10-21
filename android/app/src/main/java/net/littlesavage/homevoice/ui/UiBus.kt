package net.littlesavage.homevoice.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.littlesavage.homevoice.protocol.UiMessage

/** One turn of the conversation as shown on screen. */
data class Turn(val user: String = "", val userFinal: Boolean = false, val reply: String = "", val replyTurnId: String? = null)

data class ConversationState(val active: Boolean = false, val state: String = "listening", val turns: List<Turn> = emptyList())

/**
 * The current voice conversation (shared by the panel and the full-screen view). Turns are kept for
 * the whole session and while the panel lingers afterwards, so a follow-up continues the same thread.
 */
object Conversation {
    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state

    fun begin() = _state.update { it.copy(active = true, state = "listening") }
    fun end() = _state.update { it.copy(active = false) }
    fun clear() = _state.update { if (it.active) it else ConversationState() }
    fun setState(s: String) = _state.update { it.copy(state = s) }

    /** Speech after a finished reply starts a new turn; otherwise it updates the current one. */
    fun utterance(text: String, final: Boolean) = _state.update { c ->
        val last = c.turns.lastOrNull()
        val turns = if (last == null || last.reply.isNotEmpty()) c.turns + Turn(text, final) else c.turns.dropLast(1) + last.copy(user = text, userFinal = final)
        c.copy(turns = turns.takeLast(MAX_TURNS))
    }

    fun reply(turnId: String, text: String) = _state.update { c ->
        val last = c.turns.lastOrNull() ?: Turn(userFinal = true)
        val updated = if (last.replyTurnId == turnId) last.copy(reply = last.reply + " " + text) else if (last.reply.isEmpty()) last.copy(reply = text, replyTurnId = turnId) else null
        c.copy(turns = (if (updated != null) c.turns.dropLast(1) + updated else c.turns + Turn(userFinal = true, reply = text, replyTurnId = turnId)).takeLast(MAX_TURNS))
    }

    private const val MAX_TURNS = 20
}

/**
 * Agent UI on screen: at most one in the panel and one full screen, each addressed by id
 * (a message with the same id replaces it in place).
 */
object UiBus {
    private val _panel = MutableStateFlow<UiMessage?>(null)
    val panel: StateFlow<UiMessage?> = _panel
    private val _fullscreen = MutableStateFlow<UiMessage?>(null)
    val fullscreen: StateFlow<UiMessage?> = _fullscreen

    /** Set by OverlayActivity while it's in front (the panel steps aside unless it has UI of its own). */
    val fullscreenVisible = MutableStateFlow(false)

    /** Told when the device closes a screen itself (tap, ttl), so the agent's idea of the screen stays right. */
    var onInterrupt: (() -> Unit)? = null
    var onConversationClosed: (() -> Unit)? = null

    var onDismissed: ((id: String) -> Unit)? = null

    fun showPanel(ui: UiMessage) {
        if (_fullscreen.value?.id == ui.id) _fullscreen.value = null
        _panel.value = ui
    }

    fun showFullscreen(ui: UiMessage) {
        if (_panel.value?.id == ui.id) _panel.value = null
        _fullscreen.value = ui
    }

    fun openIds(): List<String> = listOfNotNull(_panel.value?.id, _fullscreen.value?.id)

    /** From the agent (dismiss_ui): null id = everything. */
    fun dismiss(id: String? = null) {
        if (id == null || _panel.value?.id == id) _panel.value = null
        if (id == null || _fullscreen.value?.id == id) _fullscreen.value = null
    }

    /** Closed on the device: also tell the agent. */
    fun closeLocally(ui: UiMessage) {
        dismiss(ui.id)
        onConversationClosed?.invoke()
        onDismissed?.invoke(ui.id)
        if (_fullscreen.value == null && _panel.value == null) Conversation.clear()
    }
}
