package com.example.speechbridge.ai

import com.example.speechbridge.engine.CommittedEdit

/** Output post-processing and guards, pure Kotlin so it can be unit-tested. */
class ParsedReply(val sessionText: String, val fix: CommittedEdit?)

/**
 * Strips reasoning blocks and template special tokens, then trims.
 *
 * The special-token pass matters for models whose template wraps structured output,
 * e.g. LFM2.5 answers with `<|tool_call_start|>[<fix>...</fix>]<|tool_call_end|>`.
 * Those tokens are template plumbing and are never part of the answer.
 */
fun stripThinkBlocks(raw: String): String {
    var out = raw
    while (true) {
        val open = out.indexOf(THINK_OPEN)
        if (open < 0) break
        val close = out.indexOf(THINK_CLOSE, open)
        out = if (close < 0) out.take(open) else out.take(open) + out.drop(close + THINK_CLOSE.length)
    }

    val kept = StringBuilder()
    var i = 0
    while (i < out.length) {
        val open = out.indexOf("<|", i)
        if (open < 0) {
            kept.append(out, i, out.length)
            break
        }
        kept.append(out, i, open)
        val close = out.indexOf("|>", open + 2)
        if (close < 0) break
        i = close + 2
    }

    var cleaned = kept.toString().trim()
    // A tool-call wrapper leaves the payload inside a bare [ ... ] list.
    if (cleaned.startsWith("[") && cleaned.endsWith("]") &&
        (cleaned.contains("<session>") || cleaned.contains("<fix>"))
    ) {
        cleaned = cleaned.substring(1, cleaned.length - 1).trim()
    }
    return cleaned.trim()
}

/** Null when the model didn't follow the format, so callers can fall back to the raw utterance. */
fun parseModelReply(raw: String): ParsedReply? {
    val cleaned = stripThinkBlocks(raw)
    val session = extractTag(cleaned, "session") ?: return null
    val fixBlock = extractTag(cleaned, "fix")
    val fix = if (fixBlock == null) null else letFixFrom(fixBlock)
    return ParsedReply(session, fix)
}

private fun extractTag(text: String, tag: String): String? {
    val open = "<$tag>"
    val close = "</$tag>"
    val a = text.indexOf(open)
    if (a < 0) return null
    val b = text.indexOf(close, a + open.length)
    if (b < 0) return null
    return text.substring(a + open.length, b)
}

private fun letFixFrom(block: String?): CommittedEdit? {
    if (block == null) return null
    val find = extractTag(block, "find")?.trim() ?: return null
    if (find.isEmpty()) return null
    val replace = extractTag(block, "replace")?.trim() ?: ""
    return CommittedEdit(find, replace)
}

/**
 * A reply that carries a fix but no <session>. Small models do this when they read a
 * correction as targeting text that already exists. The caller decides how to apply it.
 *
 * Two spellings show up in practice, so both are accepted:
 *   <fix><find>old</find><replace>new</replace></fix>   (what the prompt asks for)
 *   fix(find='old', replace='new')                     (what LFM2.5 tends to emit)
 */
fun parseFixOnly(raw: String): CommittedEdit? {
    val cleaned = stripThinkBlocks(raw)
    if (cleaned.contains("<session>")) return null
    return letFixFrom(extractTag(cleaned, "fix")) ?: letFixFromCall(cleaned)
}

/** Reads the argument list of a `fix(find=..., replace=...)` style call. */
private fun letFixFromCall(text: String): CommittedEdit? {
    val call = text.indexOf("fix(")
    if (call < 0) return null
    val find = quotedArgument(text, call, "find")
    if (find == null || find.isEmpty()) return null
    return CommittedEdit(find, quotedArgument(text, call, "replace") ?: "")
}

/** The quoted value of `key=` at or after [from], or null when there isn't one. */
private fun quotedArgument(text: String, from: Int, key: String): String? {
    val at = text.indexOf(key, from)
    if (at < 0) return null
    var i = text.indexOf('=', at)
    if (i < 0) return null
    i += 1
    while (i < text.length && text[i] == ' ') i += 1
    if (i >= text.length) return null
    val quote = text[i]
    if (quote != '\'' && quote != '"') return null
    val end = text.indexOf(quote, i + 1)
    if (end < 0) return null
    return text.substring(i + 1, end)
}

private val CANCEL_PHRASES = listOf(
    "scratch that", "never mind", "nevermind", "forget it", "delete that",
    "cancel that", "clear that", "start over",
)

private fun normalizeWords(s: String): String {
    val sb = StringBuilder()
    for (c in s.lowercase()) sb.append(if (c.isLetter()) c else ' ')
    return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}

fun isCancellationPhrase(utterance: String): Boolean {
    val normalized = normalizeWords(utterance)
    return CANCEL_PHRASES.any { normalized.contains(it) }
}

private const val THINK_OPEN = "\u003cthink\u003e"
private const val THINK_CLOSE = "\u003c/think\u003e"
