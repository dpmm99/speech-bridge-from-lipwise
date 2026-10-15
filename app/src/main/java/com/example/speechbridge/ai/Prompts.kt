package com.example.speechbridge.ai

import com.example.speechbridge.appConfig
import com.example.speechbridge.engine.TransformRequest

/**
 * The prompt sent to the editor, assembled from speechbridge.config.json.
 *
 * Nothing here is hard-coded prose: [appConfig] supplies the exact wording, with the
 * placeholders documented in that file. Keeping it outside the code means the prompt can
 * be tuned on a device without rebuilding the app.
 *
 *   {committed}      text already in the field (read-only context)
 *   {session}        what has been dictated so far this session
 *   {utterance}      the raw transcript of what the user just said
 *   {command_word}   the wake word for edit commands
 *   {instructions}   the user's own preferences
 */
fun systemPrompt(commandWord: String, vocabulary: List<String>, customInstructions: String): String {
    val prompts = appConfig.prompts
    return buildString {
        append(prompts.system)
        if (commandWord.isNotBlank()) {
            append("\n\n")
            append(prompts.commandWordNote.replace("{command_word}", commandWord.trim()))
        }
        if (vocabulary.isNotEmpty()) {
            append("\n\n")
            append(prompts.vocabularyNote)
            append('\n')
            vocabulary.forEach { append("- ").append(it).append('\n') }
        }
        if (customInstructions.isNotBlank()) {
            append("\n")
            append(prompts.customNote.replace("{instructions}", customInstructions.trim()))
        }
    }
}

fun userPrompt(req: TransformRequest): String {
    val prompts = appConfig.prompts
    return prompts.userTemplate
        .replace("{committed}", req.committedBefore.takeLast(prompts.committedMaxChars))
        .replace("{session}", req.sessionText)
        .replace("{utterance}", req.utterance)
}
