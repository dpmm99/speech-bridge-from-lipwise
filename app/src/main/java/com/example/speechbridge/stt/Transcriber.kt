package com.example.speechbridge.stt

import android.util.Log

/**
 * Kotlin mirror of Lipwise's `Transcriber` (transcribe.cpp / ggml, GGUF models).
 *
 * Input contract: 16 kHz, mono, float32, roughly -1.0..1.0 (16-bit samples divided by 32767).
 * Only one model is held in memory at a time. [language] may be "auto"; the native side must drop
 * the hint if the model's capability list doesn't contain it. [vocabulary] goes in as a vocabulary
 * list if the model supports it, else joined with ", " as a Whisper initial prompt, else ignored.
 */
interface Transcriber {
    /** Loads the GGUF at [path]; no-op if [id] is already active (frees the previous model first). */
    fun ensureLoaded(id: String, path: String)

    fun loadedId(): String?

    /** Blocking. Returns trimmed text. */
    fun transcribe(pcm: FloatArray, language: String, vocabulary: List<String>): String

    fun unload()
}

/**
 * JNI bridge to `libtranscribe_jni.so`, built from transcribe.cpp (see app/src/main/cpp).
 *
 * The C++ side implements, on class [NativeTranscriber]:
 *   nativeInitBackends(): Boolean
 *   nativeLoad(String): Int                 // 0 = ok
 *   nativeTranscribe(FloatArray, String, String): String  // vocabulary joined with '\n'
 *   nativeUnload(): Unit
 */
class NativeTranscriber : Transcriber {
    private var loaded: String? = null

    override fun ensureLoaded(id: String, path: String) {
        if (loaded == id) return
        if (loaded != null) {
            nativeUnload()
            loaded = null
        }
        val code = nativeLoad(path)
        if (code != 0) throw IllegalStateException("transcribe.cpp could not load $path (code $code)")
        loaded = id
    }

    override fun loadedId(): String? = loaded

    override fun transcribe(pcm: FloatArray, language: String, vocabulary: List<String>): String =
        nativeTranscribe(pcm, language, vocabulary.joinToString("\n")).trim()

    override fun unload() {
        if (loaded != null) nativeUnload()
        loaded = null
    }

    /** True when libtranscribe_jni.so was built with transcribe.cpp linked in. */
    fun init(): Boolean = nativeInitBackends()

    private external fun nativeInitBackends(): Boolean
    private external fun nativeLoad(path: String): Int
    private external fun nativeTranscribe(pcm: FloatArray, language: String, vocabulary: String): String
    private external fun nativeUnload(): Unit
}

/**
 * Loads libtranscribe_jni.so from the app's own native library directory.
 *
 * System.loadLibrary resolves the bare soname, and this device's JVM does not have the
 * app's extracted lib directory on its dlopen search path, so an absolute path is used.
 */
fun createNativeTranscriber(nativeLibraryDir: String): NativeTranscriber? = try {
    val so = java.io.File(nativeLibraryDir, "libtranscribe_jni.so").getAbsolutePath()
    java.lang.System.load(so)
    val t = NativeTranscriber()
    if (t.init()) t
    else {
        Log.w("NativeTranscriber", "libtranscribe_jni.so carries no transcribe.cpp build")
        null
    }
} catch (e: Throwable) {
    Log.w("NativeTranscriber", "libtranscribe_jni.so not available", e)
    null
}

private var installedTranscriber: Transcriber? = null

/** The one transcriber the app runs, or null when transcribe.cpp is not linked in. */
val transcriberInstance: Transcriber? get() = installedTranscriber

/** Called from SpeechBridgeApp.onCreate once the native library directory is known. */
fun installTranscriber(nativeLibraryDir: String) {
    if (installedTranscriber == null) {
        installedTranscriber = createNativeTranscriber(nativeLibraryDir)
    }
}
