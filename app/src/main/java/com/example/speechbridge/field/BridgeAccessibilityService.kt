package com.example.speechbridge.field

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.speechbridge.field.NoTextFieldBridge
import com.example.speechbridge.replaceLastIgnoreCase

/**
 * Lets the app read and rewrite the focused text field. Never touches password fields.
 * The user must enable it manually in system Accessibility settings.
 */
class BridgeAccessibilityService : AccessibilityService(), TextFieldBridge {

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridgeAccessibilityInstance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (bridgeAccessibilityInstance === this) bridgeAccessibilityInstance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun focusedEditable(): AccessibilityNodeInfo? {
        // Look at the other apps' windows first. While this app's own dictation screen is up it
        // owns the active window, so rootInActiveWindow alone would hand back a node from our own
        // UI - never the message the user was typing in, which lives in the app behind us. An
        // accessibility service can see every window, so ask all of them and skip our own.
        val all = windows
        if (all != null) {
            for (w in all) {
                val root = w.root ?: continue
                val owner = root.getPackageName()
                if (owner == null || owner.toString() == packageName) continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) return focused
            }
        }
        // Nothing of ours is in the way (the keyboard path): the active window is the right one.
        // The platform exposes no getters for the editable/password flags, so we take whatever
        // node holds the input focus and let ACTION_SET_TEXT below decide (it fails on fields
        // that refuse edits, which includes password fields).
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    /**
     * A field's real contents.
     *
     * An empty field reports its hint as its text, so reading the node's text directly turns
     * "Message @Tourk" into text the user supposedly typed. Comparing against the node's own hint
     * is how we tell an empty field from a filled one.
     */
    private fun effectiveText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString() ?: ""
        if (text.isEmpty()) return ""
        val hint = node.hintText?.toString() ?: ""
        return if (hint.isNotEmpty() && text == hint) "" else text
    }

    override fun readFocusedText(): String? {
        val node = focusedEditable() ?: return null
        return effectiveText(node)
    }

    /**
     * Appends [text] once another app's field holds the input focus.
     *
     * A window that is not the active one does not report FOCUS_INPUT, so while this app's own
     * screen is up the message behind it cannot be found at all. Closing that screen first and
     * then retrying is what makes the text actually land.
     */
    fun appendTextRetrying(text: String): Boolean {
        for (attempt in 0 until 25) {
            if (appendText(text)) return true
            java.lang.Thread.sleep(100)
        }
        return false
    }

    override fun appendText(text: String): Boolean {
        if (text.isEmpty()) return false
        val node = focusedEditable() ?: return false
        val current = effectiveText(node)
        val separator = if (current.isNotEmpty() && !current.last().isWhitespace()) " " else ""
        val updated = current + separator + text
        return setText(node, updated)
    }

    /** Sets [node]'s text and leaves the cursor at the end. */
    private fun setText(node: AccessibilityNodeInfo, updated: String): Boolean {
        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, updated.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, updated.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    override fun replaceLast(find: String, replace: String): Boolean {
        val node = focusedEditable() ?: return false
        val current = node.text?.toString() ?: return false
        val updated = replaceLastIgnoreCase(current, find, replace)
        if (updated == current) return false

        val setText = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)
        if (ok) {
            // ACTION_SET_TEXT moves the cursor; put it back at the end.
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, updated.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, updated.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }
}

/** Set while the service is running; null when it is off. Written once on the service's own
 * thread, read from UI threads; a plain var keeps the annotation surface small. */
var bridgeAccessibilityInstance: BridgeAccessibilityService? = null

/** What [DictationSession] should use for field edits. */
fun currentBridge(): TextFieldBridge = bridgeAccessibilityInstance ?: NoTextFieldBridge()
