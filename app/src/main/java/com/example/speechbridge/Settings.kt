package com.example.speechbridge

import android.content.SharedPreferences
import com.example.speechbridge.ai.AiProvider
import com.example.speechbridge.ai.ProviderConfig
import com.example.speechbridge.ai.aiProviderFromId
class Settings(private val prefs: android.content.SharedPreferences) {

    // --- dictation session ---

    /** Speak the whole message back after each dictation session. */
    var readBack: Boolean
        get() = prefs.getBoolean("readBack", true)
        set(value) = prefs.edit().putBoolean("readBack", value).apply()

    /** Use the accessibility bridge (if enabled) to read/replace text in the focused field. */
    var useFieldBridge: Boolean
        get() = prefs.getBoolean("useFieldBridge", true)
        set(value) = prefs.edit().putBoolean("useFieldBridge", value).apply()

    /** Prefix a space when continuing after existing text, since many keyboards won't add one. */
    var prependSpace: Boolean
        get() = prefs.getBoolean("prependSpace", true)
        set(value) = prefs.edit().putBoolean("prependSpace", value).apply()

    /** Silence after the last utterance before the session ends on its own. */
    var silenceTimeoutMs: Long
        get() = prefs.getLong("silenceTimeoutMs", appConfig.speech.silenceTimeoutMs)
        set(value) = prefs.edit().putLong("silenceTimeoutMs", value).apply()

    /** How long the previous message stays usable as context when no field bridge is available. */
    var contextTtlMs: Long
        get() = prefs.getLong("contextTtlMs", 120_000L)
        set(value) = prefs.edit().putLong("contextTtlMs", value).apply()

    // --- speech model (Lipwise: selected_model, language, vocabulary) ---

    /**
     * Catalog model id, e.g. "handy-computer/parakeet-tdt-0.6b-v3-gguf". The default comes
     * from speechbridge.config.json (speech.default_model), so the app has a working speech
     * model before anything is chosen on the models screen.
     */
    var selectedModel: String?
        get() = prefs.getString("selectedModel", null) ?: appConfig.speech.defaultModel
        set(value) = prefs.edit().putString("selectedModel", value).apply()

    /** Language code for the recognizer, or "auto". */
    var language: String
        get() = prefs.getString("language", null) ?: appConfig.speech.language
        set(value) = prefs.edit().putString("language", value).apply()

    /**
     * Preferred spellings (names, jargon), one per line in storage. Used by both STT and the LLM.
     *
     * Falls back to speech.custom_words in speechbridge.config.json, which is where these are
     * meant to be maintained: they bias the recogniser, so "Alana" wins over "Atlanta" or
     * "Elaina", and they are handed to the editor as spellings to reproduce exactly.
     */
    var vocabulary: List<String>
        get() {
            val stored = (prefs.getString("vocabulary", "") ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
            return if (stored.isNotEmpty()) stored else appConfig.speech.customWords
        }
        set(value) = prefs.edit().putString("vocabulary", value.joinToString("\n")).apply()

    // --- AI editor (Lipwise: ai_enabled, provider, providers, command_word, custom_instructions) ---

    var aiEnabled: Boolean
        get() = prefs.getBoolean("aiEnabled", true)
        set(value) = prefs.edit().putBoolean("aiEnabled", value).apply()

    var provider: AiProvider
        get() = aiProviderFromId(prefs.getString("provider", null))
        set(value) = prefs.edit().putString("provider", value.id).apply()

    /** Wake word for edit commands; empty = every utterance may be a command. */
    /** Wake word for edit commands; empty = every utterance may be a command. Defaults from
     * speechbridge.config.json (prompt.command_word) until the user sets one in the app. */
    var commandWord: String
        get() = prefs.getString("commandWord", null) ?: appConfig.prompts.commandWord
        set(value) = prefs.edit().putString("commandWord", value).apply()

    var customInstructions: String
        get() = prefs.getString("customInstructions", "") ?: ""
        set(value) = prefs.edit().putString("customInstructions", value).apply()

    // API keys are stored in plain app-private SharedPreferences. Move to Android Keystore-backed
    // storage before shipping to anyone else.
    fun providerConfig(p: AiProvider): ProviderConfig = ProviderConfig(
        baseUrl = prefs.getString("p_${p.id}_base", "") ?: "",
        model = prefs.getString("p_${p.id}_model", "") ?: "",
        apiKey = prefs.getString("p_${p.id}_key", "") ?: "",
    )

    fun setProviderConfig(p: AiProvider, c: ProviderConfig) {
        prefs.edit()
            .putString("p_${p.id}_base", c.baseUrl.trim())
            .putString("p_${p.id}_model", c.model.trim())
            .putString("p_${p.id}_key", c.apiKey.trim())
            .apply()
    }
}
