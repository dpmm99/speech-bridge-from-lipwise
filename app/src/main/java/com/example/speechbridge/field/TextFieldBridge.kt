package com.example.speechbridge.field

/** Optional access to the text field the user is dictating into. */
interface TextFieldBridge {
    /** Full text of the focused editable field, or null if unavailable. */
    fun readFocusedText(): String?

    /** Replaces the last occurrence of [find] with [replace] in the focused field. */
    fun replaceLast(find: String, replace: String): Boolean

    /**
     * Appends [text] to the end of the focused field. Separate from [replaceLast] because that one
     * has nothing to match when the field is empty, which is the common case for a new message.
     */
    fun appendText(text: String): Boolean
}

/** Used when the accessibility bridge is not enabled. */
class NoTextFieldBridge : TextFieldBridge {
    override fun readFocusedText(): String? = null

    override fun replaceLast(find: String, replace: String): Boolean = false

    override fun appendText(text: String): Boolean = false
}
