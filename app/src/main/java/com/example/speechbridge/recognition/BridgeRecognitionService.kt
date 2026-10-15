package com.example.speechbridge.recognition

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionService.Callback
import com.example.speechbridge.Settings
import com.example.speechbridge.audio.MicCapture
import com.example.speechbridge.engine.engines
import com.example.speechbridge.field.currentBridge
import com.example.speechbridge.field.NoTextFieldBridge
import com.example.speechbridge.session.contextStore
import com.example.speechbridge.session.Sink
import com.example.speechbridge.session.DictationSession
import com.example.speechbridge.tts.ReadBack

/** The provider that keyboards reach through SpeechRecognizer. Delegates to [DictationSession]. */
class BridgeRecognitionService : RecognitionService() {

    private lateinit var settings: Settings
    private lateinit var readBack: ReadBack
    private var session: DictationSession? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this.getSharedPreferences("speech_bridge", android.content.Context.MODE_PRIVATE))
        readBack = ReadBack(this)
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        session?.cancel()
        session = null
        readBack.stop()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            safe { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }

        // Open the microphone first. Everything before this point costs the user the start of
        // their sentence, and reading the focused field goes through an accessibility service,
        // which can take a while. MicCapture buffers what it captures until the session attaches
        // its consumer, so nothing recorded in the meantime is lost.
        val stt = engines.sttFactory()
        val mic = MicCapture(stt.sampleRate)
        if (!mic.start()) {
            stt.cancel()
            safe { listener.error(SpeechRecognizer.ERROR_AUDIO) }
            return
        }

        val uid = listener.callingUid
        val bridge = if (settings.useFieldBridge) currentBridge() else NoTextFieldBridge()
        val committed = bridge.readFocusedText() ?: contextStore.get(uid, settings.contextTtlMs)

        val sink = SessionSink(listener, uid, settings, readBack)
        val created = DictationSession(
            stt = stt,
            transform = engines.transformFactory(),
            bridge = bridge,
            committedBefore = committed,
            languageTag = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
            wantPartials = recognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false),
            prependSpace = settings.prependSpace,
            silenceTimeoutMs = settings.silenceTimeoutMs,
            sink = sink,
            mic = mic,
        )
        session = created
        created.start()
    }

    override fun onStopListening(listener: Callback) {
        session?.finish()
    }

    override fun onCancel(listener: Callback) {
        session?.cancel()
        session = null
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        readBack.shutdown()
        super.onDestroy()
    }

}

/**
 * Extra the test screen reads alongside [SpeechRecognizer.RESULTS_RECOGNITION]: the
 * verbatim transcript, before the editor rewrote it. The result itself stays the edited
 * text, because that is what a keyboard should insert.
 */
const val EXTRA_HEARD = "com.example.speechbridge.HEARD"

/** True while the clip has been transcribed but the editor has not answered yet. */
const val EXTRA_PROCESSING = "com.example.speechbridge.PROCESSING"

/** Bridges a [DictationSession] back into the caller's [Callback]. */
class SessionSink(
    private val callback: Callback,
    private val uid: Int,
    private val settings: Settings,
    private val readBack: ReadBack,
) : Sink {

    /** Everything the recognizer said this session, before the editor touched it. */
    private val heard = StringBuilder()

    override fun onReady() = safe { callback.readyForSpeech(Bundle()) }

    override fun onSpeechStart() = safe { callback.beginningOfSpeech() }

    override fun onRms(db: Float) = safe { callback.rmsChanged(db) }

    /** Words as heard, sent immediately with the "still editing" flag set. */
    override fun onHeard(text: String) {
        if (text.isBlank()) return
        if (heard.isNotEmpty()) heard.append(' ')
        heard.append(text.trim())
        safe { callback.partialResults(resultBundle(text, false, heard.toString(), true)) }
    }

    override fun onPartial(text: String) =
        safe { callback.partialResults(resultBundle(text, false, heard.toString(), false)) }

    override fun onEndOfSpeech() = safe { callback.endOfSpeech() }

    override fun onFinal(sessionText: String, fullMessage: String) {
        safe { callback.results(resultBundle(sessionText, true, heard.toString(), false)) }
        contextStore.put(uid, fullMessage)
        if (settings.readBack) readBack.speak(fullMessage)
    }

    override fun onError(code: Int) = safe { callback.error(code) }
}

private inline fun safe(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        // Client went away (RemoteException etc.); nothing useful to do.
    }
}

private fun resultBundle(text: String, withConfidence: Boolean, heard: String, processing: Boolean): Bundle = Bundle().apply {
    val results = java.util.ArrayList<String>()
    results.add(text)
    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, results)
    if (withConfidence) putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
    putString(EXTRA_HEARD, heard)
    putBoolean(EXTRA_PROCESSING, processing)
}
