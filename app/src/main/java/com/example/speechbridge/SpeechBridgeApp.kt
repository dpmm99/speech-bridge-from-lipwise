package com.example.speechbridge

import android.app.Application
import android.util.Log
import android.content.Context
import com.example.speechbridge.ai.LlmTransformEngine
import com.example.speechbridge.ai.LlamaServer
import com.example.speechbridge.ai.installLlamaServer
import com.example.speechbridge.ai.loadProcessBridge
import com.example.speechbridge.engine.engines
import com.example.speechbridge.models.loadModelCatalog
import com.example.speechbridge.models.ModelStore
import com.example.speechbridge.models.ModelDownloader
import com.example.speechbridge.stt.ClipSttEngine
import com.example.speechbridge.stt.installTranscriber
import com.example.speechbridge.stt.transcriberInstance

class SpeechBridgeApp : Application() {

    val settings by lazy { Settings(this.getSharedPreferences("speech_bridge", Context.MODE_PRIVATE)) }

    /** Speech models come from catalog.json, local LLMs from llm-catalog.json. */
    val sttCatalog by lazy { loadModelCatalog(assetText("catalog.json")) }
    val llmCatalog by lazy { loadModelCatalog(assetText("llm-catalog.json")) }

    val sttStore by lazy { ModelStore(this.filesDir.getAbsolutePath(), "models") }
    val llmStore by lazy { ModelStore(this.filesDir.getAbsolutePath(), "llm") }

    private fun assetText(name: String): String? = try {
        assets.open(name).use { it.bufferedReader().readText() }
    } catch (e: Throwable) {
        null
    }

    val sttDownloader by lazy { ModelDownloader(sttCatalog.mirrors, sttStore) }
    val llmDownloader by lazy { ModelDownloader(llmCatalog.mirrors, llmStore) }

    /** The local llama.cpp server: one process, started on demand and kept warm. */
    val llmServer by lazy {
        LlamaServer(this, settings, llmCatalog, llmStore, this.getApplicationInfo().nativeLibraryDir)
    }

    override fun onCreate() {
        super.onCreate()

        // Setup and prompt text come from speechbridge.config.json, not from constants here.
        installAppConfig(loadAppConfig(this))

        // Load libproc_jni.so by absolute path from the app's native library directory so the
        // Kotlin `external fun` fork/exec bridge (which starts llama-server) binds correctly.
        loadProcessBridge(this.getApplicationInfo().nativeLibraryDir)
        installLlamaServer(llmServer)

        engines.transformFactory = { LlmTransformEngine(settings, llmCatalog, llmStore) }
        // A dictation session starting is the cue to load the model while the user speaks.
        engines.onSessionStart = { llmServer.preloadAsync() }

        // Without libtranscribe_jni.so the fake engine stays in place so the rest still runs.
        installTranscriber(this.getApplicationInfo().nativeLibraryDir)
        val transcriber = transcriberInstance
        if (transcriber != null) {
            engines.sttFactory = { ClipSttEngine(transcriber, settings, sttCatalog, sttStore) }
        } else {
            Log.w("SpeechBridgeApp", "Native transcriber missing; using FakeSttEngine")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The loaded model is the largest thing we own, so it goes first under pressure.
        llmServer.onTrimMemory(level)
    }
}
