package net.littlesavage.homevoice.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * On-device openWakeWord (https://github.com/dscripka/openWakeWord) on ONNX Runtime.
 *
 * Streaming pipeline, fed one 80 ms chunk (1280 samples @ 16 kHz) at a time:
 *   audio --melspectrogram.onnx--> 32-bin mel frames (x/10 + 2)
 *         --embedding_model.onnx (76-frame window, hop 8)--> 96-d embedding
 *         --wakeword.onnx (last 16 embeddings)--> score 0..1
 *
 * Models are fetched by android/scripts/fetch-models.sh into assets/wakeword/.
 * TODO: verify scores against the Python reference on recorded samples.
 */
class OpenWakeWord(context: Context, private val threshold: Float = 0.5f) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val mel = session(context, "melspectrogram.onnx")
    private val embed = session(context, "embedding_model.onnx")
    private val ww = session(context, "wakeword.onnx")

    private val rawContext = 160 * 3 // extra samples for the STFT window, as in the reference impl
    private var raw = FloatArray(0)
    private val melFrames = ArrayDeque<FloatArray>().apply { repeat(76) { addLast(FloatArray(32) { 1f }) } }
    private val features = ArrayDeque<FloatArray>()
    private var cooldownChunks = 0

    private fun session(ctx: Context, name: String): OrtSession =
        env.createSession(ctx.assets.open("wakeword/$name").use { it.readBytes() }, opts)

    /** Returns true when the wake word is detected in this chunk. */
    fun process(chunk: ShortArray): Boolean {
        // Keep the tail of the previous chunk for STFT context.
        val input = FloatArray(raw.size + chunk.size)
        System.arraycopy(raw, 0, input, 0, raw.size)
        for (i in chunk.indices) input[raw.size + i] = chunk[i].toFloat()
        raw = input.copyOfRange(maxOf(0, input.size - rawContext), input.size)
        if (input.size < chunk.size + rawContext) return false // warm-up

        // 1. mel spectrogram -> [time,1,frames,32]
        val newMel = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { t ->
            mel.run(mapOf(mel.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val arr = (out[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                arr.map { f -> FloatArray(32) { f[it] / 10f + 2f } }
            }
        }
        newMel.takeLast(8).forEach { melFrames.addLast(it) }
        while (melFrames.size > 76) melFrames.removeFirst()

        // 2. embedding of the last 76 mel frames -> 96
        val window = FloatArray(76 * 32).also { buf -> melFrames.forEachIndexed { i, f -> f.copyInto(buf, i * 32) } }
        val emb = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, 76, 32, 1)).use { t ->
            embed.run(mapOf(embed.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                (out[0].value as Array<Array<Array<FloatArray>>>)[0][0][0].copyOf()
            }
        }
        features.addLast(emb)
        while (features.size > 16) features.removeFirst()
        if (features.size < 16) return false

        // 3. classifier on the last 16 embeddings
        val feat = FloatArray(16 * 96).also { buf -> features.forEachIndexed { i, f -> f.copyInto(buf, i * 96) } }
        val score = OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), longArrayOf(1, 16, 96)).use { t ->
            ww.run(mapOf(ww.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                (out[0].value as Array<FloatArray>)[0][0]
            }
        }
        lastScore = score
        if (cooldownChunks > 0) { cooldownChunks--; return false }
        if (score >= threshold) {
            cooldownChunks = 25 // ~2 s refractory period
            return true
        }
        return false
    }

    @Volatile var lastScore = 0f
        private set

    override fun close() {
        mel.close(); embed.close(); ww.close(); opts.close()
    }
}
