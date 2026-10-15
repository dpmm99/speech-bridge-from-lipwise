package com.example.speechbridge.stt

import android.util.Log
import com.example.speechbridge.Settings
import com.example.speechbridge.appConfig
import com.example.speechbridge.engine.SttConfig
import com.example.speechbridge.engine.SttEngine
import com.example.speechbridge.engine.SttListener
import com.example.speechbridge.models.ModelCatalog
import com.example.speechbridge.models.ModelStore
import kotlin.collections.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.concurrent.thread

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lipwise's models transcribe whole clips, not a live stream, so this engine does the endpointing:
 * an energy VAD cuts the mic stream into utterances, and each clip goes through the [Transcriber]
 * on one worker (in order). Final results only; there are no partials.
 *
 * The energy VAD is deliberately simple. Swap in Silero/WebRTC VAD if noisy rooms trip it.
 */
class ClipSttEngine(
    private val transcriber: Transcriber,
    private val settings: Settings,
    private val catalog: ModelCatalog,
    private val store: ModelStore,
) : SttEngine {

    override val sampleRate = 16_000

    private val mutex = java.util.concurrent.locks.ReentrantLock()
    private val queue = ArrayDeque<FloatArray>()
    private var listener: SttListener? = null
    private var languageHint = "auto"
    private var vocabulary: List<String> = emptyList<String>()
    private var running = false
    private var workerFinished = false
    private var modelId = ""
    private var modelPath = ""
    private var modelReady = false
    private var modelFailed = false

    // Endpointing knobs, read from speechbridge.config.json when a session starts.
    private var speechRms = 0.012
    private var silentPeak = 0.015f
    private var minSamples = 4_800
    private var preRollChunks = 15
    private var endQuietChunks = 12
    private var maxSamples = 28 * 16_000

    private val preRoll = ArrayDeque<ShortArray>()
    private var segment: ArrayDeque<ShortArray>? = null
    private var segmentSamples = 0
    private var quietChunks = 0

    override fun start(config: SttConfig, listener: SttListener) {
        this.listener = listener
        languageHint = settings.language.takeIf { it != "auto" }
            ?: config.languageTag?.substringBefore('-')?.substringBefore('_')?.lowercase()?.ifBlank { null }
            ?: "auto"

        val model = catalog.find(settings.selectedModel)
        if (model == null || !store.isDownloaded(model)) {
            listener.onError(IllegalStateException("No speech model downloaded. Open Speech Bridge > Speech models."))
            return
        }
        val path = store.file(model).getAbsolutePath()
        vocabulary = settings.vocabulary
        modelId = model.id
        modelPath = path
        modelReady = false
        modelFailed = false

        val knobs = appConfig.speech
        speechRms = knobs.speechThresholdRms
        silentPeak = knobs.silentPeak.toFloat()
        minSamples = knobs.minClipMs * sampleRate / 1000
        maxSamples = knobs.maxSegmentMs * sampleRate / 1000
        preRollChunks = maxOf(1, knobs.preRollMs / CHUNK_MS)
        endQuietChunks = maxOf(1, knobs.endOfUtteranceSilenceMs / CHUNK_MS)

        queue.clear()
        preRoll.clear()
        segment = null
        segmentSamples = 0
        quietChunks = 0
        running = true
        workerFinished = false

        thread {
            // Load the model here, on the worker, rather than on the caller's thread: the
            // recognition service has to be able to report "ready" straight away, and
            // loading now means the weights are resident by the time the first utterance
            // is cut, which is the whole point of starting at speech-to-text mode.
            try {
                transcriber.ensureLoaded(modelId, modelPath)
                modelReady = true
            } catch (e: Throwable) {
                Log.e(TAG, "could not load " + modelPath, e)
                modelFailed = true
                listener?.onError(e)
                mutex.withLock {
                    running = false
                    workerFinished = true
                }
                return@thread
            }
            while (true) {
                val clip = nextClip()
                if (clip == null) {
                    if (!running) {
                        mutex.withLock { workerFinished = true }
                        break
                    }
                    java.lang.Thread.sleep(10)
                    continue
                }
                transcribeAndDeliver(clip)
            }
        }
    }

    override fun acceptAudio(pcm: ShortArray, length: Int) {
        val chunk = ShortArray(length)
        for (i in 0..<length) chunk[i] = pcm[i]
        val loud = rms(chunk) >= speechRms
        var started = false

        mutex.withLock {
            val seg = segment
            if (seg == null) {
                if (loud) {
                    val s = ArrayDeque<ShortArray>()
                    preRoll.forEach { s.addLast(it) }
                    s.addLast(chunk)
                    segment = s
                    segmentSamples = 0
                    s.forEach { segmentSamples += it.size }
                    quietChunks = 0
                    preRoll.clear()
                    started = true
                } else {
                    preRoll.addLast(chunk)
                    if (preRoll.size > preRollChunks) preRoll.removeFirst()
                }
            } else {
                seg.addLast(chunk)
                segmentSamples += chunk.size
                quietChunks = if (loud) 0 else quietChunks + 1
                if (quietChunks >= endQuietChunks || segmentSamples >= maxSamples) endSegmentLocked()
            }
        }

        if (started) listener?.onSpeechStart()
    }

    /** Flush: the last clip is transcribed and delivered before this returns. */
    override suspend fun finish() {
        mutex.withLock {
            endSegmentLocked()
            running = false
        }
        while (true) {
            val clip = nextClip()
            if (clip != null) {
                transcribeAndDeliver(clip)
                continue
            }
            val done = mutex.withLock { workerFinished }
            if (done) break
            java.lang.Thread.sleep(10)
        }
    }

    override fun cancel() {
        listener = null
        mutex.withLock {
            queue.clear()
            segment = null
            segmentSamples = 0
            running = false
        }
    }

    /** Caller must hold [mutex]. */
    private fun endSegmentLocked() {
        val seg = segment ?: return
        segment = null
        val out = FloatArray(segmentSamples)
        var i = 0
        for (c in seg) {
            for (s in c) {
                out[i] = s.toFloat() / 32767f
                i += 1
            }
        }
        segmentSamples = 0
        queue.addLast(out)
    }

    private fun nextClip(): FloatArray? = mutex.withLock {
        if (queue.isEmpty()) null else queue.removeFirst()
    }

    private fun transcribeAndDeliver(clip: FloatArray) {
        val l = listener ?: return
        if (clip.size < minSamples || peak(clip) < silentPeak) return
        // Wait rather than skip: the first utterance usually arrives while a fresh model is
        // still loading, and dropping it is exactly how the start of a sentence goes missing.
        if (!awaitModel()) return
        val text = try {
            transcriber.transcribe(clip, languageHint, vocabulary).trim()
        } catch (e: Throwable) {
            Log.e(TAG, "transcription failed", e)
            return
        }
        // Worth logging: when a transcript comes out wrong, the first questions are how much
        // audio the model actually saw and what language hint it was given.
        Log.i(TAG, "clip " + seconds(clip) + "s, lang=" + languageHint + " -> " + text)
        if (text.isNotBlank() && !isNoiseTag(text)) l.onUtteranceFinal(text)
    }

    private fun seconds(clip: FloatArray): Float = clip.size.toFloat() / sampleRate.toFloat()

    /** Blocks until the model is loaded. False when the load failed. */
    private fun awaitModel(): Boolean {
        while (true) {
            if (modelReady) return true
            if (modelFailed) return false
            java.lang.Thread.sleep(10)
        }
    }

    private fun rms(a: ShortArray): Double {
        if (a.isEmpty()) return 0.0
        var sum = 0.0
        for (s in a) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / a.size) / 32768.0
    }

    private fun peak(a: FloatArray): Float {
        var m = 0f
        for (v in a) m = maxOf(m, abs(v))
        return m
    }

    /** Whisper-style non-speech markers such as "[BLANK_AUDIO]" or "(music)". */
    private fun isNoiseTag(t: String): Boolean =
        (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")"))
}

private const val TAG = "ClipSttEngine"

/** MicCapture hands over sampleRate / 10 samples at a time, i.e. 100 ms. */
private const val CHUNK_MS = 100
