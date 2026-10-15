package com.example.speechbridge.tts

import android.content.Context
import android.speech.tts.TextToSpeech

/** Reads the confirmed message back with the system TTS engine. */
class ReadBack(private val context: Context) {
    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    private var tts: TextToSpeech? = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            val queued = pending
            if (queued != null) speak(queued)
            pending = null
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: return
        if (!ready) {
            pending = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun stop() {
        pending = null
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
