package com.example.speechbridge.ai

import android.util.Log
import com.example.speechbridge.Settings
import com.example.speechbridge.ai.AiProvider
import com.example.speechbridge.ai.LlmClient
import com.example.speechbridge.ai.ParsedReply
import com.example.speechbridge.ai.ProviderConfig
import com.example.speechbridge.models.ModelCatalog
import com.example.speechbridge.models.ModelStore
import com.example.speechbridge.ai.isCancellationPhrase
import com.example.speechbridge.ai.parseModelReply
import com.example.speechbridge.ai.systemPrompt
import com.example.speechbridge.ai.userPrompt
import com.example.speechbridge.engine.TransformEngine
import com.example.speechbridge.engine.TransformRequest
import com.example.speechbridge.engine.TransformResult
import com.example.speechbridge.joinText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The LLM editor. Failure policy copied from Lipwise: if the AI step fails or answers in an
 * unusable way, append the raw utterance and record why in [lastError]; never block the pipeline.
 */
class LlmTransformEngine(
    private val settings: Settings,
    private val llmCatalog: ModelCatalog,
    private val llmStore: ModelStore,
    private val client: LlmClient = LlmClient(),
) : TransformEngine {

    /** Reason the most recent call fell back to the raw utterance, or null if it didn't. */
    var lastError: String? = null

    override suspend fun apply(request: TransformRequest): TransformResult {
        lastError = null
        val fallback = TransformResult(joinText(request.sessionText, request.utterance))

        if (!settings.aiEnabled) return fallback

        val provider = settings.provider
        val raw = if (provider == AiProvider.LOCAL) {
            localTurn(request) ?: return fallback
        } else {
            val cfg = settings.providerConfig(provider)
            if (cfg.model.isBlank()) {
                lastError = "No AI model configured"
                return fallback
            }
            remoteTurn(provider, cfg, request) ?: return fallback
        }

        val parsed = parseModelReply(raw)
        if (parsed == null) {
            val fixOnly = fixOnlyResult(raw, request)
            if (fixOnly != null) return fixOnly
            lastError = "Model reply was not in the expected format"
            // Worth logging: a reply that does not parse is the difference between the
            // editor working and the raw utterance being passed through.
            Log.w(TAG, "unparsed reply (" + raw.length + " chars): " + raw.take(400))
            return fallback
        }
        // Guard: an empty result is only believable when the user asked to cancel.
        if (parsed.sessionText.isBlank() && !isCancellationPhrase(request.utterance)) {
            lastError = "Model returned empty text for a non-cancel utterance"
            return fallback
        }
        // Guard: only keep a fix whose target really is in the committed text.
        val fix = parsed.fix?.takeIf { request.committedBefore.contains(it.find, ignoreCase = true) }
        return TransformResult(parsed.sessionText, fix)
    }

    /**
     * A reply with a <fix> and no <session>, which small models produce when they read a
     * correction as editing text that already exists. A fix whose target is in the
     * committed text still lets the utterance be appended; a fix whose target is in the
     * utterance itself is a correction of what was just said, and its result becomes the
     * session text. Anything else is unusable and the caller falls back.
     */
    private fun fixOnlyResult(raw: String, request: TransformRequest): TransformResult? {
        val fix = parseFixOnly(raw) ?: return null
        if (request.committedBefore.contains(fix.find, ignoreCase = true)) {
            return TransformResult(joinText(request.sessionText, request.utterance), fix)
        }
        if (request.utterance.contains(fix.find, ignoreCase = true)) {
            val corrected = request.utterance.replace(fix.find, fix.replace, ignoreCase = true).trim()
            if (corrected.isNotEmpty()) {
                return TransformResult(joinText(request.sessionText, corrected))
            }
        }
        return null
    }

    private suspend fun remoteTurn(provider: AiProvider, cfg: ProviderConfig, request: TransformRequest): String? = try {
        val system = systemPrompt(settings.commandWord, settings.vocabulary, settings.customInstructions)
        withContext(Dispatchers.IO) { client.complete(provider, cfg, system, userPrompt(request)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "AI step failed", e)
        lastError = e.message ?: e.javaClass.simpleName
        null
    }

    /** One turn against the local llama-server, or null when it is unavailable/failed. */
    private suspend fun localTurn(request: TransformRequest): String? {
        val server = llmServer
        if (server == null) {
            lastError = "No local llama.cpp server"
            return null
        }
        val modelId = settings.providerConfig(AiProvider.LOCAL).model
        if (modelId.isBlank()) {
            lastError = "No local AI model selected"
            return null
        }
        val spec = llmCatalog.find(modelId)
        if (spec == null || !llmStore.isDownloaded(spec)) {
            lastError = "The local AI model has not been downloaded"
            return null
        }
        val system = systemPrompt(settings.commandWord, settings.vocabulary, settings.customInstructions)
        val user = userPrompt(request)
        return try {
            withContext(Dispatchers.IO) {
                // Usually already warm from the preload that starts with the session; otherwise
                // this blocks until the model finishes loading, then falls back to raw text.
                if (!server.ensureReady(TURN_READY_TIMEOUT_MS)) {
                    lastError = server.lastError ?: "The local model did not become ready"
                    return@withContext null
                }
                server.touch()
                val base = server.baseUrl()
                if (base == null) {
                    lastError = "The local model is not ready"
                    return@withContext null
                }
                val cfg = ProviderConfig(baseUrl = base, model = spec.id, apiKey = "")
                client.complete(AiProvider.LOCAL, cfg, system, user)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Local AI step failed", e)
            lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }
}

private const val TAG = "LlmTransformEngine"

/** Long enough for a cold model load, short enough that a turn still falls back rather than hangs. */
private const val TURN_READY_TIMEOUT_MS = 20_000L
