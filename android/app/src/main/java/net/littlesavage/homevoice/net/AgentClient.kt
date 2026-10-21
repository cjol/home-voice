package net.littlesavage.homevoice.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import net.littlesavage.homevoice.protocol.ClientMessage
import net.littlesavage.homevoice.protocol.ServerMessage
import net.littlesavage.homevoice.protocol.decodeServerMessage
import net.littlesavage.homevoice.protocol.encode
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Persistent WebSocket to this device's Durable Object, with reconnect.
 * URL: <server>/agents/voice-agent/<deviceId>?token=<token>
 */
class AgentClient(
    serverUrl: String,
    private val deviceId: String,
    token: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(msg: ServerMessage)
        fun onAudio(bytes: ByteArray)
    }

    private val url = serverUrl.replaceFirst(Regex("^http"), "ws") + "/agents/voice-agent/$deviceId?token=$token"
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var backoffMs = 1_000L
    @Volatile private var closed = false
    @Volatile var connected = false
        private set

    fun connect() {
        if (closed) return
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                backoffMs = 1_000L
                send(ClientMessage.Hello(deviceId = deviceId, appVersion = "0.1.0"))
                listener.onConnected()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                decodeServerMessage(text)?.let(listener::onMessage) ?: Log.d(TAG, "ignored: $text")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onAudio(bytes.toByteArray())
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped("closed $code $reason")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped("failure ${t.message} ${response?.code}")
        })
    }

    private fun dropped(why: String) {
        Log.w(TAG, "socket $why")
        connected = false
        listener.onDisconnected()
        if (closed) return
        main.postDelayed({ connect() }, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
    }

    fun send(msg: ClientMessage) { ws?.send(msg.encode()) }

    fun sendAudio(frame: ShortArray) {
        if (!connected) return
        val bytes = ByteArray(frame.size * 2)
        for (i in frame.indices) {
            val s = frame[i].toInt()
            bytes[2 * i] = (s and 0xff).toByte()
            bytes[2 * i + 1] = (s shr 8 and 0xff).toByte()
        }
        ws?.send(bytes.toByteString())
    }

    fun close() {
        closed = true
        ws?.close(1000, "bye")
        http.dispatcher.executorService.shutdown()
    }

    companion object { private const val TAG = "AgentClient" }
}
