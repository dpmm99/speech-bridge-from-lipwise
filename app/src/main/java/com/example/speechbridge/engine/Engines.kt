package com.example.speechbridge.engine

/**
 * Where the two plug-ins are wired in. Replace the factories from
 * [com.example.speechbridge.SpeechBridgeApp.onCreate].
 */
class Engines {
    var sttFactory: () -> SttEngine = { FakeSttEngine() }
    var transformFactory: () -> TransformEngine = { PassthroughTransformEngine() }

    /**
     * Fired once when a dictation session starts, before the user speaks. The app
     * uses it to start loading the local model in the background, so the weights
     * are in RAM by the time the first utterance needs editing.
     */
    var onSessionStart: () -> Unit = {}
}

/** The one set of engines the app runs. */
val engines = Engines()
