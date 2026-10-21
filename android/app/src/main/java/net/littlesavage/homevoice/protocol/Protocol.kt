@file:OptIn(ExperimentalSerializationApi::class)

package net.littlesavage.homevoice.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/** Mirror of shared/src/protocol.ts (v1). Keep the two in sync. */
const val PROTOCOL_VERSION = 1
const val MIC_SAMPLE_RATE = 16_000
const val TTS_SAMPLE_RATE = 24_000

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ClientMessage {
    @Serializable @SerialName("hello")
    data class Hello(val v: Int = PROTOCOL_VERSION, val deviceId: String, val appVersion: String? = null) : ClientMessage
    @Serializable @SerialName("start_session")
    data class StartSession(val wakeWord: String? = null) : ClientMessage
    @Serializable @SerialName("end_session")
    data class EndSession(val reason: String? = null) : ClientMessage
    @Serializable @SerialName("interrupt")
    data object Interrupt : ClientMessage
    @Serializable @SerialName("playback_done")
    data class PlaybackDone(val turnId: String) : ClientMessage
    @Serializable @SerialName("text_input")
    data class TextInput(val text: String) : ClientMessage
    @Serializable @SerialName("ui_event")
    data class UiEvent(val uiId: String, val action: String, val value: String? = null) : ClientMessage
    @Serializable @SerialName("ping")
    data object Ping : ClientMessage
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ServerMessage {
    @Serializable @SerialName("ready")
    data class Ready(val v: Int, val model: String, val tools: String) : ServerMessage
    @Serializable @SerialName("state")
    data class State(val state: String) : ServerMessage
    @Serializable @SerialName("transcript")
    data class Transcript(val text: String, val final: Boolean) : ServerMessage
    @Serializable @SerialName("assistant_text")
    data class AssistantText(val turnId: String, val text: String) : ServerMessage
    @Serializable @SerialName("tts_start")
    data class TtsStart(val turnId: String, val sampleRate: Int, val encoding: String) : ServerMessage
    @Serializable @SerialName("tts_end")
    data class TtsEnd(val turnId: String) : ServerMessage
    @Serializable @SerialName("stop_playback")
    data class StopPlayback(val turnId: String? = null) : ServerMessage
    @Serializable @SerialName("ui")
    data class Ui(val ui: UiMessage) : ServerMessage
    @Serializable @SerialName("dismiss_ui")
    data class DismissUi(val uiId: String? = null) : ServerMessage
    @Serializable @SerialName("auth_required")
    data class AuthRequired(val url: String) : ServerMessage
    @Serializable @SerialName("error")
    data class Error(val message: String) : ServerMessage
    @Serializable @SerialName("pong")
    data object Pong : ServerMessage
}

fun ClientMessage.encode(): String = ProtocolJson.encodeToString(ClientMessage.serializer(), this)

/** Returns null for unknown message types (forward compatibility). */
fun decodeServerMessage(text: String): ServerMessage? =
    runCatching { ProtocolJson.decodeFromString(ServerMessage.serializer(), text) }.getOrNull()
