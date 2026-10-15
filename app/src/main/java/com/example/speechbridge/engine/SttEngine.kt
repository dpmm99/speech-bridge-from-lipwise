package com.example.speechbridge.engine

/**
 * Plug-in point #1: the actual speech-to-text library (Whisper, Parakeet, ...).
 *
 * The session feeds 16 kHz mono PCM16 via [acceptAudio]. The engine owns endpointing (VAD):
 * it reports [SttListener.onSpeechStart] when speech begins and [SttListener.onUtteranceFinal] when
 * an utterance ends. Several utterances can occur within one recognition session.
 */
interface SttEngine {
    val sampleRate: Int

    /** Called once per session before any audio. */
    fun start(config: SttConfig, listener: SttListener)

    /** Called on the mic thread, ~100 ms chunks. [pcm] is reused: copy it if you keep it. */
    fun acceptAudio(pcm: ShortArray, length: Int)

    /** Mic has stopped. Flush: emit [SttListener.onUtteranceFinal] for any pending speech, then return. */
    suspend fun finish()

    /** Abort immediately, drop pending audio, release resources. */
    fun cancel()
}

interface SttListener {
    fun onSpeechStart()

    /** Hypothesis for the utterance in progress (replaces the previous partial). */
    fun onPartial(text: String)

    fun onUtteranceFinal(text: String)

    fun onError(t: Throwable)
}

/**
 * @param languageTag BCP-47 tag requested by the caller, or null.
 * @param contextHint text already in the field (tail), usable as a Whisper-style initial prompt.
 */
class SttConfig(val languageTag: String?, val contextHint: String)
