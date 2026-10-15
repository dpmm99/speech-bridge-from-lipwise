package com.example.speechbridge

/** Joins two pieces of dictated text with a single space, tolerating blanks. */
fun joinText(a: String, b: String): String = when {
    a.isBlank() -> b.trim()
    b.isBlank() -> a
    else -> a.trimEnd() + " " + b.trim()
}

/** Replaces the last (case-insensitive) occurrence of [find] in [text]; returns [text] if absent. */
fun replaceLastIgnoreCase(text: String, find: String, replace: String): String {
    if (find.isEmpty()) return text
    val i = text.lastIndexOf(find, ignoreCase = true)
    return if (i < 0) text else text.replaceRange(i, i + find.length, replace)
}
