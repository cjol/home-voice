package net.littlesavage.homevoice.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.littlesavage.homevoice.protocol.UiMessage

/** Current agent-provided screen, shared between the service and the overlay. */
object UiBus {
    private val _current = MutableStateFlow<UiMessage?>(null)
    val current: StateFlow<UiMessage?> = _current

    fun show(ui: UiMessage) { _current.value = ui }

    fun dismiss(id: String? = null) {
        if (id == null || _current.value?.id == id) _current.value = null
    }
}
