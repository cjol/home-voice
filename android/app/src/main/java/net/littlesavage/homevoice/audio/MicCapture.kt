package net.littlesavage.homevoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import net.littlesavage.homevoice.protocol.MIC_SAMPLE_RATE

/**
 * Continuous 16 kHz mono PCM capture in 80 ms frames (1280 samples, the
 * openWakeWord chunk size). Uses the VOICE_COMMUNICATION source so the
 * platform echo canceller references our own TTS playback, plus the
 * AcousticEchoCanceler / NoiseSuppressor / AGC effects where available.
 */
class MicCapture(private val onFrame: (ShortArray) -> Unit) {
    companion object {
        const val FRAME_SAMPLES = 1280
        private const val TAG = "MicCapture"
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    @SuppressLint("MissingPermission") // checked by the caller before starting the service
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SAMPLES * 2 * 4),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
        attachEffects(rec.audioSessionId)
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({
            val frame = ShortArray(FRAME_SAMPLES)
            while (running) {
                var off = 0
                while (off < FRAME_SAMPLES && running) {
                    val n = rec.read(frame, off, FRAME_SAMPLES - off)
                    if (n < 0) { Log.e(TAG, "read error $n"); running = false; break }
                    off += n
                }
                if (off == FRAME_SAMPLES) onFrame(frame.copyOf())
            }
        }, "mic-capture").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun attachEffects(session: Int) {
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = true; effects += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = true; effects += it }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = true; effects += it }
        Log.i(TAG, "effects: ${effects.map { it.javaClass.simpleName }}")
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        effects.forEach { it.release() }
        effects.clear()
        record?.run { runCatching { stop() }; release() }
        record = null
    }
}
