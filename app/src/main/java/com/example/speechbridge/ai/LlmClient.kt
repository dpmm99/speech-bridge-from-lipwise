package com.example.speechbridge.ai

import com.example.speechbridge.appConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** Blocking HTTP client for the two wire formats Lipwise supports. Call from Dispatchers.IO. */
class LlmClient(private val completionTimeoutMs: Int = 45_000) {

    fun complete(
        provider: AiProvider,
        cfg: ProviderConfig,
        system: String,
        user: String,
        maxTokens: Int = appConfig.llm.maxReplyTokens,
    ): String {
        if (cfg.model.isBlank()) throw IOException("No model set for ${provider.label}")
        return if (provider.anthropicStyle) anthropic(provider, cfg, system, user, maxTokens)
        else openAiCompatible(provider, cfg, system, user, maxTokens)
    }

    fun listModels(provider: AiProvider, cfg: ProviderConfig): List<String> {
        val base = cfg.resolvedBase(provider)
        return if (provider.anthropicStyle) {
            val body = http("GET", "$base/v1/models?limit=100", anthropicHeaders(cfg), null, LIST_TIMEOUT_MS)
            idsOf(JSONObject(body).getJSONArray("data"))
        } else {
            val body = http("GET", "$base/models", openAiHeaders(cfg), null, LIST_TIMEOUT_MS)
            idsOf(JSONObject(body).getJSONArray("data")).sorted()
        }
    }

    // --- Anthropic Messages API ---------------------------------------------------------------

    private fun anthropic(
        provider: AiProvider,
        cfg: ProviderConfig,
        system: String,
        user: String,
        maxTokens: Int,
    ): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val resp = JSONObject(
            http("POST", "${cfg.resolvedBase(provider)}/v1/messages", anthropicHeaders(cfg), body.toString(), completionTimeoutMs),
        )
        if (resp.optString("stop_reason", "") == "refusal") throw IOException("The model refused the request")
        val content = resp.optJSONArray("content") ?: throw IOException("Response has no content")
        return buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type", "") == "text") append(block.optString("text", ""))
            }
        }
    }

    private fun anthropicHeaders(cfg: ProviderConfig): Map<String, String> = mapOf(
        "x-api-key" to cfg.apiKey,
        "anthropic-version" to "2023-06-01",
    )

    // --- OpenAI-compatible chat completions -----------------------------------------------------

    private fun openAiCompatible(
        provider: AiProvider,
        cfg: ProviderConfig,
        system: String,
        user: String,
        maxTokens: Int,
    ): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", maxTokens)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        if (provider.isSelfHosted) {
            body.put("temperature", 0.2)
            body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        } else if (provider == AiProvider.LOCAL) {
            // llama-server reads the chat template from the GGUF, so only sampling is set here.
            // It rejects chat_template_kwargs when the template has no enable_thinking variable.
            body.put("temperature", appConfig.llm.temperature)
        }
        val resp = JSONObject(
            http("POST", "${cfg.resolvedBase(provider)}/chat/completions", openAiHeaders(cfg), body.toString(), completionTimeoutMs),
        )
        val choices = resp.optJSONArray("choices") ?: throw IOException("Response has no choices")
        if (choices.length() == 0) throw IOException("Response has no choices")
        return choices.getJSONObject(0).getJSONObject("message").optString("content", "")
    }

    private fun openAiHeaders(cfg: ProviderConfig): Map<String, String> =
        if (cfg.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${cfg.apiKey}")

    // --- plumbing -----------------------------------------------------------------------------

    private fun idsOf(data: JSONArray): List<String> =
        List(data.length()) { i -> data.getJSONObject(i).getString("id") }

    private fun http(method: String, url: String, headers: Map<String, String>, body: String?, readTimeoutMs: Int): String {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "SpeechBridge/0.1")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.getOutputStream().use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.getResponseCode()
            val stream = if (code in 200..299) conn.getInputStream() else conn.getErrorStream()
            val text = stream.use { it.bufferedReader().readText() }
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(300)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}

private const val LIST_TIMEOUT_MS = 15_000
