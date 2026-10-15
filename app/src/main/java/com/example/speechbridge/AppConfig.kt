package com.example.speechbridge

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Setup that lives outside the code: the speech and LLM process parameters and every word
 * of the prompt. Shipped as the asset [CONFIG_FILE_NAME] and copied to the app's own files
 * directory on first run, so it can be edited (or adb-pushed) and picked up on the next
 * start without rebuilding anything.
 */
class LlmSetup(
    val defaultModel: String,
    val grammar: String,
    val maxContextTokens: Int,
    val threads: Int,
    val maxReplyTokens: Int,
    val temperature: Double,
    val reasoning: Boolean,
    val idleStopSeconds: Long,
)

class SpeechSetup(
    /** Model-name fragments whose architecture lets the recogniser be biased towards a word list. */
    val biasFamilies: List<String>,
    val defaultModel: String,
    val customWords: List<String>,
    val language: String,
    val silenceTimeoutMs: Long,
    val endOfUtteranceSilenceMs: Int,
    val preRollMs: Int,
    val minClipMs: Int,
    val maxSegmentMs: Int,
    val speechThresholdRms: Double,
    val silentPeak: Double,
    val noSpeechTimeoutMs: Long,
)

class PromptSetup(
    /** Optional gate: when set, only utterances starting with it count as edit instructions. */
    val commandWord: String,
    val system: String,
    val userTemplate: String,
    val commandWordNote: String,
    val vocabularyNote: String,
    val customNote: String,
    val committedMaxChars: Int,
)

class AppConfig(val llm: LlmSetup, val speech: SpeechSetup, val prompts: PromptSetup)

const val CONFIG_FILE_NAME = "speechbridge.config.json"

private const val TAG = "AppConfig"

/**
 * What the app uses when the file is missing or unreadable. Deliberately the same shape as
 * the shipped asset so behaviour is predictable, just not as good as the real prompt.
 */
fun defaultConfig(): AppConfig = AppConfig(
    LlmSetup(
        defaultModel = "unsloth/gemma-4-E4B-it-qat-GGUF",
        grammar = "root ::= \"<session>\" body \"</session>\" rest\nbody ::= [^<]*\nrest ::= .*\n",
        maxContextTokens = 4096,
        threads = 4,
        maxReplyTokens = 512,
        temperature = 0.2,
        reasoning = false,
        idleStopSeconds = 600L,
    ),
    SpeechSetup(
        biasFamilies = listOf("whisper", "funasr", "granite", "moss", "qwen3"),
        defaultModel = "handy-computer/parakeet-tdt-0.6b-v3-gguf",
        customWords = emptyList<String>(),
        language = "en",
        silenceTimeoutMs = 5_000L,
        endOfUtteranceSilenceMs = 1_200,
        preRollMs = 1_500,
        minClipMs = 300,
        maxSegmentMs = 28_000,
        speechThresholdRms = 0.012,
        silentPeak = 0.015,
        noSpeechTimeoutMs = 8_000L,
    ),
    PromptSetup(
        commandWord = "",
        system = "You are the editing engine inside a voice keyboard. Rewrite the dictated " +
            "utterance into clean written text, keeping the user's wording and meaning. " +
            "Answer with the rewritten text only.",
        userTemplate = "<committed>\n{committed}\n</committed>\n\n<session>\n{session}\n</session>\n\n" +
            "<utterance>\n{utterance}\n</utterance>",
        commandWordNote = "Treat the utterance as an edit instruction only when it starts with " +
            "the word \"{command_word}\". Otherwise it is always new dictation.",
        vocabularyNote = "Spell these terms exactly like this (the recognizer may have misheard them):",
        customNote = "The user's own preferences: {instructions}",
        committedMaxChars = 1_500,
    ),
)

private fun optString(parent: JSONObject?, key: String, fallback: String): String {
    if (parent == null) return fallback
    val value = parent.optString(key, "")
    return if (value.isBlank()) fallback else value
}

/** A JSON array of strings; entries are trimmed and blanks dropped. */
private fun optStringList(parent: JSONObject?, key: String, fallback: List<String>): List<String> {
    if (parent == null) return fallback
    val array = parent.optJSONArray(key)
    if (array == null) return fallback
    val out = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val value = array.optString(i, "").trim()
        if (value.isNotEmpty()) out.add(value)
    }
    return out
}

/** Like [optString], but a key that is present and empty means "empty" rather than "unset". */
private fun optStringAllowEmpty(parent: JSONObject?, key: String, fallback: String): String {
    if (parent == null) return fallback
    if (!parent.has(key)) return fallback
    return parent.optString(key, fallback)
}

private fun optInt(parent: JSONObject?, key: String, fallback: Int): Int {
    if (parent == null) return fallback
    val value = parent.optInt(key, 0)
    return if (value <= 0) fallback else value
}

private fun optDouble(parent: JSONObject?, key: String, fallback: Double): Double {
    if (parent == null) return fallback
    return parent.optDouble(key, fallback)
}

private fun optBoolean(parent: JSONObject?, key: String, fallback: Boolean): Boolean {
    if (parent == null) return fallback
    return parent.optBoolean(key, fallback)
}

/** Missing keys fall back one by one, so a partial file is still usable. */
fun parseAppConfig(text: String): AppConfig {
    val base = defaultConfig()
    val root = JSONObject(text)
    val llm = root.optJSONObject("llm")
    val speech = root.optJSONObject("speech")
    val prompt = root.optJSONObject("prompt")
    return AppConfig(
        LlmSetup(
            defaultModel = optString(llm, "default_model", base.llm.defaultModel),
            grammar = optStringAllowEmpty(llm, "grammar", base.llm.grammar),
            maxContextTokens = optInt(llm, "max_context_tokens", base.llm.maxContextTokens),
            threads = optInt(llm, "threads", base.llm.threads),
            maxReplyTokens = optInt(llm, "max_reply_tokens", base.llm.maxReplyTokens),
            temperature = optDouble(llm, "temperature", base.llm.temperature),
            reasoning = optBoolean(llm, "reasoning", base.llm.reasoning),
            idleStopSeconds = optInt(llm, "idle_stop_seconds", base.llm.idleStopSeconds.toInt()).toLong(),
        ),
        SpeechSetup(
            biasFamilies = optStringList(speech, "bias_families", base.speech.biasFamilies),
            defaultModel = optString(speech, "default_model", base.speech.defaultModel),
            customWords = optStringList(speech, "custom_words", base.speech.customWords),
            language = optString(speech, "language", base.speech.language),
            silenceTimeoutMs = optInt(speech, "silence_timeout_ms", base.speech.silenceTimeoutMs.toInt()).toLong(),
            endOfUtteranceSilenceMs = optInt(speech, "end_of_utterance_silence_ms", base.speech.endOfUtteranceSilenceMs),
            preRollMs = optInt(speech, "pre_roll_ms", base.speech.preRollMs),
            minClipMs = optInt(speech, "min_clip_ms", base.speech.minClipMs),
            maxSegmentMs = optInt(speech, "max_segment_ms", base.speech.maxSegmentMs),
            speechThresholdRms = optDouble(speech, "speech_threshold_rms", base.speech.speechThresholdRms),
            silentPeak = optDouble(speech, "silent_peak", base.speech.silentPeak),
            noSpeechTimeoutMs = optInt(speech, "no_speech_timeout_ms", base.speech.noSpeechTimeoutMs.toInt()).toLong(),
        ),
        PromptSetup(
            commandWord = optStringAllowEmpty(prompt, "command_word", base.prompts.commandWord),
            system = optString(prompt, "system", base.prompts.system),
            userTemplate = optString(prompt, "user", base.prompts.userTemplate),
            commandWordNote = optString(prompt, "command_word_note", base.prompts.commandWordNote),
            vocabularyNote = optString(prompt, "vocabulary_note", base.prompts.vocabularyNote),
            customNote = optString(prompt, "custom_note", base.prompts.customNote),
            committedMaxChars = optInt(prompt, "committed_max_chars", base.prompts.committedMaxChars),
        ),
    )
}

/** The live config file, seeding it from the asset the first time. */
fun loadAppConfig(context: Context): AppConfig {
    val target = java.io.File(java.io.File(context.filesDir.getAbsolutePath()), CONFIG_FILE_NAME)
    if (!target.exists()) {
        try {
            val src = context.assets.open(CONFIG_FILE_NAME)
            val out = java.io.FileOutputStream(target.getAbsolutePath())
            try {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            } finally {
                out.close()
                src.close()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "no bundled " + CONFIG_FILE_NAME + "; using built-in defaults", e)
        }
    }
    Log.i(TAG, "config file: " + target.getAbsolutePath())
    if (!target.exists()) {
        return defaultConfig()
    }
    return try {
        parseAppConfig(java.io.FileInputStream(target.getAbsolutePath()).use { it.bufferedReader().readText() })
    } catch (e: Throwable) {
        Log.w(TAG, "config unreadable; using built-in defaults", e)
        defaultConfig()
    }
}

private var installed: AppConfig = defaultConfig()

/** Always readable: falls back to the built-in defaults until the app installs the file. */
val appConfig: AppConfig get() = installed

/**
 * The theme's primary text colour, for text that used to be deliberately dim.
 *
 * Grey secondary text is hard to read for older eyes. Resolving the theme's primary colour keeps
 * the contrast high in dark *and* light mode, which a hard-coded light grey would not - it would
 * be invisible on a light background.
 */
fun primaryTextColor(activity: android.app.Activity): Int {
    val value = android.util.TypedValue()
    val resolved = activity.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)
    if (!resolved) return 0xFFEEEEEE.toInt()
    if (value.resourceId != 0) {
        return activity.getResources().getColor(value.resourceId, activity.getTheme())
    }
    return value.data
}

/**
 * Whether a model's architecture can be biased towards a word list *while you speak* (phrase
 * boosting), rather than only being corrected afterwards by the editor.
 *
 * Keyed on the architecture name because that is what transcribe.cpp declares the capability
 * per. The native layer still runs its own transcribe_model_supports() check before applying
 * anything, so a model that matches here but cannot truly be biased is simply ignored - the
 * words fall back to reaching the editor, exactly as they do today.
 */
fun biasSupported(modelId: String?, families: List<String>): Boolean {
    if (modelId == null) return false
    val lower = modelId.lowercase()
    for (family in families) {
        val f = family.lowercase()
        if (f.isNotEmpty() && lower.contains(f)) return true
    }
    return false
}

fun installAppConfig(config: AppConfig) {
    installed = config
}
