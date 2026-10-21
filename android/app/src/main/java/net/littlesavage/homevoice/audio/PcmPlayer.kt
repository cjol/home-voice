package net.littlesavage.homevoice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streams PCM s16le mono (24 kHz TTS) as it arrives. [stop] flushes instantly
 * for barge-in. [onDrained] fires once all audio for a turn has played.
 */
class PcmPlayer(private val onDrained: (turnId: String) -> Unit) {
    private sealed interface Item {
        class Audio(val bytes: ByteArray) : Item
        class End(val turnId: String) : Item
    }

    private val queue = LinkedBlockingQueue<Item>()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile var isPlaying = false
        private set
    private var headAtBegin = 0L
    @Volatile private var framesWritten = 0L

    /** ms of the current reply that has actually come out of the speaker. */
    val playedMs: Long
        get() = track?.let { t -> ((t.playbackHeadPosition.toLong() and 0xffffffffL) - headAtBegin) * 1000 / t.sampleRate } ?: 0

    fun begin(sampleRate: Int) {
        stop()
        val t = track?.takeIf { it.sampleRate == sampleRate } ?: createTrack(sampleRate).also { track?.release(); track = it }
        framesWritten = 0L
        headAtBegin = t.playbackHeadPosition.toLong() and 0xffffffffL
        t.play()
        isPlaying = true
        if (thread == null) startThread()
    }

    fun write(bytes: ByteArray) { if (isPlaying) queue.put(Item.Audio(bytes)) }

    fun end(turnId: String) { queue.put(Item.End(turnId)) }

    /** Barge-in: drop everything queued and silence immediately. Returns how much had played (ms). */
    fun stop(): Long {
        val played = if (isPlaying) playedMs else 0
        generation++
        queue.clear()
        isPlaying = false
        track?.run { runCatching { pause(); flush() } }
        return played
    }

    fun release() {
        stop()
        running = false
        thread?.interrupt()
        thread = null
        track?.release()
        track = null
    }

    private fun createTrack(rate: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                // TODO: compare USAGE_ASSISTANT vs USAGE_VOICE_COMMUNICATION for AEC quality on the Pixel Tablet.
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
        .build()

    private fun startThread() {
        running = true
        thread = Thread({
            while (running) {
                val item = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
                val gen = generation
                when (item) {
                    is Item.Audio -> {
                        val written = track?.write(item.bytes, 0, item.bytes.size, AudioTrack.WRITE_BLOCKING) ?: 0
                        if (gen == generation && written > 0) framesWritten += written / 2
                    }
                    is Item.End -> {
                        // Wait for the hardware buffer to empty before declaring done.
                        while (running && gen == generation && track?.let {
                            (it.playbackHeadPosition.toLong() and 0xffffffffL) - headAtBegin < framesWritten
                        } == true) Thread.sleep(20)
                        if (gen == generation) { isPlaying = false; onDrained(item.turnId) }
                    }
                }
            }
        }, "pcm-player").apply { start() }
    }
}
