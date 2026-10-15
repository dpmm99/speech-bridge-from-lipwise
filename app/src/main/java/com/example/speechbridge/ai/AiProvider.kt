package com.example.speechbridge.ai

/**
 * Mirrors Lipwise's providers, plus a "local" one that runs llama.cpp in-process through JNI
 * (the desktop app spawns llama-server, which is not available on Android).
 * On Android, point OLLAMA/CUSTOM at an OpenAI-compatible server (Ollama, LM Studio, llama-server).
 */
enum class AiProvider(val id: String, val label: String, val defaultBaseUrl: String, val anthropicStyle: Boolean) {
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com", true),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", false),
    OLLAMA("ollama", "Ollama / LM Studio", "http://localhost:11434/v1", false),
    CUSTOM("custom", "Custom (OpenAI-compatible)", "", false),
    LOCAL("local", "Local (llama.cpp)", "", false);

    /** Lipwise sends temperature 0.2 and disables "thinking" for these. */
    val isSelfHosted: Boolean get() = this == OLLAMA || this == CUSTOM
}

class ProviderConfig(val baseUrl: String = "", val model: String = "", val apiKey: String = "") {
    fun resolvedBase(provider: AiProvider): String =
        baseUrl.ifBlank { provider.defaultBaseUrl }.trimEnd('/')
}

/** Provider stored in settings; unknown or missing ids fall back to the local llama.cpp engine. */
fun aiProviderFromId(id: String?): AiProvider =
    AiProvider.entries.firstOrNull { it.id == id } ?: AiProvider.LOCAL
