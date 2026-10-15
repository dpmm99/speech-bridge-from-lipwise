package com.example.speechbridge.session

import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.speechbridge.appConfig
import com.example.speechbridge.audio.MicCapture
import com.example.speechbridge.engine.SttConfig
import com.example.speechbridge.engine.SttEngine
import com.example.speechbridge.engine.SttListener
import com.example.speechbridge.engine.TransformEngine
import com.example.speechbridge.engine.TransformRequest
import com.example.speechbridge.engine.TransformResult
import com.example.speechbridge.engine.engines
import com.example.speechbridge.field.TextFieldBridge
import com.example.speechbridge.joinText
import com.example.speechbridge.replaceLastIgnoreCase
import kotlin.collections.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** What the keyboard sees from one recognition session. */
interface Sink {
    fun onReady()

    fun onSpeechStart()

    fun onRms(db: Float)

    fun onPartial(text: String)

    /**
     * A clip has been transcribed but not yet edited. Lets the caller show the words while the
     * editor is still working, instead of leaving the user staring at nothing.
     */
    fun onHeard(text: String)

    fun onEndOfSpeech()

    /** [fullMessage] is committed text plus this session's text, for read-back. */
    fun onFinal(sessionText: String, fullMessage: String)

    fun onError(code: Int)
}

/**
 * One recognition session: mic -> STT engine -> (per utterance) transform engine -> keyboard.
 *
 * The session keeps one running [sessionText]. Every utterance goes through the transform engine
 * together with the text so far, so a later utterance can rewrite an earlier one. After each
 * utterance the whole [sessionText] is sent as a partial result; keyboards replace their composing
 * text on each partial, which is what makes corrections visible. The final result is the same text.
 */
class DictationSession(
    private val stt: SttEngine,
    private val transform: TransformEngine,
    private val bridge: TextFieldBridge,
    private val committedBefore: String,
    private val languageTag: String?,
    private val wantPartials: Boolean,
    private val prependSpace: Boolean,
    private val silenceTimeoutMs: Long,
    private val sink: Sink,
    /** Already recording: the caller starts the mic before doing anything slow, see [start]. */
    private val mic: MicCapture,
    /**
     * Text already dictated that this session should build on. Used when the user keeps talking
     * after a round finished: without it the earlier stretch is dropped and only the new speech
     * is deliverable.
     */
    private val sessionBefore: String = "",
) {
    private val mutex = java.util.concurrent.locks.ReentrantLock()
    private val queue = ArrayDeque<String>()

    private var committed = committedBefore
    private var sessionText = sessionBefore
    private var done = false

    private var workerRunning = false
    private var workerFinished = false

    /** True while an utterance is inside the editor. */
    private var transforming = false

    private val noSpeechTimeoutMs = appConfig.speech.noSpeechTimeoutMs
    private var deadlineMs = -1L
    private var timerNoSpeech = false

    private val leadingSpace = prependSpace && committedBefore.isNotEmpty() &&
        !committedBefore.last().isWhitespace()

    private fun outbound(text: String): String = if (leadingSpace && text.isNotEmpty()) " $text" else text

    // --- hooks the STT engine calls (see [SessionListener]) -------------------------------------

    fun onSpeechStart() {
        clearTimer()
        sink.onSpeechStart()
    }

    fun onPartial(text: String) {
        if (!wantPartials || doneFlag()) return
        sink.onPartial(outbound(joinText(sessionSnapshot(), text)))
    }

    fun onUtteranceFinal(text: String) {
        if (text.isNotBlank()) {
            mutex.withLock { queue.addLast(text) }
            // Show it now: the editor has not run yet, and hearing the transcription is what
            // tells the user whether they need to say it again.
            sink.onHeard(text)
        }
        armTimer(silenceTimeoutMs)
    }

    fun onError(t: Throwable) {
        Log.e(TAG, "STT engine error", t)
        abort(SpeechRecognizer.ERROR_CLIENT)
    }

    // --- lifecycle -----------------------------------------------------------------------------

    fun start(): Boolean {
        // Speech-to-text mode is starting: begin loading the local model now, in the
        // background, so it is warm by the time an utterance needs the editor.
        engines.onSessionStart()

        stt.start(SttConfig(languageTag, committedBefore.takeLast(400)), SessionListener(this))

        // The mic has been recording since the session was requested. Attaching here delivers
        // everything captured while the field was being read and the engines were started, in
        // order and losslessly, before any live audio - so the words spoken before this point
        // are still transcribed instead of being cut off.
        mic.attach { buf ->
            if (!doneFlag()) {
                stt.acceptAudio(buf, buf.size)
                sink.onRms(mic.toRmsDb(buf, buf.size))
            }
        }

        workerRunning = true
        workerFinished = false
        thread {
            while (true) {
                val u = nextUtterance()
                if (u == null) {
                    if (!workerRunning) {
                        mutex.withLock { workerFinished = true }
                        break
                    }
                    java.lang.Thread.sleep(10)
                    continue
                }
                handleUtterance(u)
            }
        }

        thread {
            while (true) {
                if (timerDue()) {
                    if (timerNoSpeech) {
                        abort(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        break
                    }
                    if (pipelineBusy()) {
                        // The editor is still catching up on what was already said. Ending the
                        // session here is what used to throw away a sentence spoken during
                        // "Editing...": the words were recorded, but the session was already over.
                        armTimer(silenceTimeoutMs)
                        continue
                    }
                    finish()
                    break
                }
                if (doneFlag()) break
                java.lang.Thread.sleep(10)
            }
        }

        sink.onReady()
        armTimer(noSpeechTimeoutMs)
        return true
    }

    /** Normal end: user stopped (or silence): flush engine, finish transforms, deliver final. */
    fun finish() {
        mutex.withLock {
            if (done) return
            done = true
            workerRunning = false
            deadlineMs = -1L
        }
        mic.stop(true)
        try {
            runBlocking { stt.finish() }
        } catch (e: Throwable) {
            Log.e(TAG, "STT finish failed", e)
        }
        while (true) {
            val u = nextUtterance()
            if (u == null) break
            handleUtterance(u)
        }
        // The worker may still be mid-utterance; the mutex serialises it, so wait for it to settle.
        while (!workerFinished) java.lang.Thread.sleep(10)

        sink.onEndOfSpeech()
        val text = sessionSnapshot()
        if (text.isBlank()) sink.onError(SpeechRecognizer.ERROR_NO_MATCH)
        else sink.onFinal(outbound(text), joinText(committedSnapshot(), text))
    }

    /** Caller cancelled: drop everything, no callbacks. */
    fun cancel() = abort(null)

    private fun abort(errorCode: Int?) {
        mutex.withLock {
            if (done) return
            done = true
            workerRunning = false
            deadlineMs = -1L
            queue.clear()
        }
        mic.stop(false)
        stt.cancel()
        if (errorCode != null) sink.onError(errorCode)
    }

    private fun armTimer(ms: Long) {
        if (doneFlag()) return
        deadlineMs = android.os.SystemClock.elapsedRealtime() + ms
        timerNoSpeech = ms == noSpeechTimeoutMs
    }

    private fun clearTimer() {
        deadlineMs = -1L
    }

    private fun timerDue(): Boolean = mutex.withLock {
        deadlineMs >= 0L && android.os.SystemClock.elapsedRealtime() >= deadlineMs
    }

    private fun nextUtterance(): String? = mutex.withLock {
        if (queue.isEmpty()) null else queue.removeFirst()
    }

    private fun handleUtterance(utterance: String) {
        mutex.withLock { transforming = true }
        try {
            handleUtteranceLocked(utterance)
        } finally {
            mutex.withLock { transforming = false }
        }
    }

    /** An utterance still queued, or one being edited, means the pipeline is behind the speaker. */
    private fun pipelineBusy(): Boolean = mutex.withLock { transforming || queue.isNotEmpty() }

    private fun handleUtteranceLocked(utterance: String) {
        val result = try {
            runBlocking {
                transform.apply(TransformRequest(committedSnapshot(), sessionSnapshot(), utterance))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Transform failed, appending raw utterance", e)
            TransformResult(joinText(sessionSnapshot(), utterance))
        }

        val edit = result.committedEdit
        if (edit != null) {
            if (bridge.replaceLast(edit.find, edit.replace)) {
                mutex.withLock { committed = replaceLastIgnoreCase(committed, edit.find, edit.replace) }
            } else {
                Log.w(TAG, "Edit to committed text ignored (no field bridge or no match)")
            }
        }

        mutex.withLock { sessionText = result.sessionText }
        if (wantPartials && !doneFlag()) sink.onPartial(outbound(sessionSnapshot()))
    }

    // --- small guarded readers -------------------------------------------------------------------

    private fun doneFlag(): Boolean = mutex.withLock { done }

    private fun committedSnapshot(): String = mutex.withLock { committed }

    private fun sessionSnapshot(): String = mutex.withLock { sessionText }
}

/** Bridges the STT engine's callbacks into the session's hooks. */
class SessionListener(private val session: DictationSession) : SttListener {
    override fun onSpeechStart() = session.onSpeechStart()

    override fun onPartial(text: String) = session.onPartial(text)

    override fun onUtteranceFinal(text: String) = session.onUtteranceFinal(text)

    override fun onError(t: Throwable) = session.onError(t)
}

private const val TAG = "DictationSession"
