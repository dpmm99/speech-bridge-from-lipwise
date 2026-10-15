package com.example.speechbridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.speechbridge.audio.MicCapture
import com.example.speechbridge.engine.engines
import com.example.speechbridge.field.NoTextFieldBridge
import com.example.speechbridge.field.bridgeAccessibilityInstance
import com.example.speechbridge.field.TextFieldBridge
import com.example.speechbridge.field.currentBridge
import com.example.speechbridge.session.DictationSession
import com.example.speechbridge.session.Sink
import com.example.speechbridge.tts.ReadBack

/**
 * Dictation with no keyboard involved: started from the Quick Settings tile, it listens, edits the
 * transcript through the same pipeline, writes the result into whatever field was focused, and gets
 * out of the way. Being an activity is deliberate - it is what gives the microphone a foreground
 * owner while it records, and it can finish itself the moment the text is delivered.
 *
 * The transcript accumulates. Pausing to think is normal, and showing only the newest utterance
 * made it look as though the earlier ones had been forgotten, so everything heard and everything
 * edited is rendered together, below the buttons.
 */
class DictateActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var notice: TextView
    private lateinit var state: TextView
    private lateinit var output: TextView
    private lateinit var stopButton: Button
    private lateinit var keepButton: Button

    private var session: DictationSession? = null
    private lateinit var readBack: ReadBack

    /** Text that still has to reach the field, when it could not be inserted while we were in front. */
    private var pendingInsert = ""
    private var bridge: TextFieldBridge = NoTextFieldBridge()
    private var committed = ""

    private val heardAll = StringBuilder()
    private var sessionSoFar = ""

    /** True once the last round's text made it into the field rather than the clipboard. */
    private var inserted = false

    /** True once the message is delivered or recognition has failed: the button then just closes. */
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this.getSharedPreferences("speech_bridge", Context.MODE_PRIVATE))
        readBack = ReadBack(this)

        val scroller = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(90), dp(6), dp(110))
        }

        // Stays put for the whole session: it is a warning, not a status line.
        notice = TextView(this).apply {
            setTextSize(24f)
            setTextColor(primaryTextColor(this@DictateActivity))
        }.also {
            it.setTextSize(24f)
            column.addView(it)
        }
        // Transient lines live here. They used to be written into the notice above, which wiped
        // the bridge warning - the one message that must stay put.
        state = TextView(this).also {
            it.setTextSize(24f)
            column.addView(it)
        }
        column.addView(hint("Speak. When you are done the text goes into the field you were typing in."))

        stopButton = button("Stop and insert") { onButtonPressed() }
        column.addView(stopButton)
        keepButton = button("Keep talking") { keepTalking() }
        column.addView(keepButton)
        column.addView(button("Cancel (discard)") { cancelDictation() })

        output = TextView(this).also {
            it.setTextSize(26f)
            column.addView(it)
        }

        scroller.addView(column)
        setContentView(scroller)
        scroller.setFillViewport(true)
        scroller.requestDisallowInterceptTouchEvent(true)
        scroller.fullScroll(1) // android.view.View.FOCUS_TOP
        scroller.post { scroller.fullScroll(1) }

        startDictation()
    }

    private fun startDictation() {
        startDictation("")
    }

    private fun startDictation(sessionBase: String) {
        // The setting says the user wants the bridge; the instance says whether the accessibility
        // service is actually running. Only the second decides whether text can be inserted, so the
        // notice keys off that, not off the setting.
        val real = if (settings.useFieldBridge) currentBridge() else NoTextFieldBridge()
        val available = real !is NoTextFieldBridge
        bridge = if (available) real else NoTextFieldBridge()
        committed = bridge.readFocusedText() ?: ""

        notice.text = if (available) {
            ""
        } else {
            "The field bridge is off, so the finished text is copied to the clipboard instead of " +
                "being typed into your message. Enable it under Accessibility to insert directly."
        }

        val stt = engines.sttFactory()
        val mic = MicCapture(stt.sampleRate)
        if (!mic.start()) {
            state.text = "Could not open the microphone."
            return
        }

        val created = DictationSession(
            stt = stt,
            transform = engines.transformFactory(),
            bridge = bridge,
            committedBefore = committed,
            languageTag = null,
            wantPartials = true,
            prependSpace = settings.prependSpace,
            silenceTimeoutMs = settings.silenceTimeoutMs,
            sink = FieldSink(),
            mic = mic,
            sessionBefore = sessionBase,
        )
        session = created
        created.start()
        render(true)
    }

    private fun onButtonPressed() {
        if (finished) {
            closeAndFinish()
            return
        }
        val running = session
        if (running == null) finish() else running.finish()
    }

    private fun cancelDictation() {
        session?.cancel()
        session = null
        finished = true
        finish()
    }

    /**
     * Writes the message into the field, or to the clipboard, and then stays open rather than
     * closing: the session may have stopped before the user was finished, so they get an explicit
     * way to carry on talking instead of having to start over.
     */
    private fun deliver(sessionText: String, fullMessage: String) {
        finished = true
        val text = sessionText.trim()
        if (text.isEmpty()) {
            state.text = "Nothing to insert."
            stopButton.text = "Close"
            return
        }

        // Read it back before deciding what to do with it, and never close while it is speaking:
        // hearing the text is what lets the user say "no, I said..." - closing underneath it would
        // both cut the speech off and remove the chance to correct it.
        if (settings.readBack) readBack.speak(text)

        if (bridge.appendText(text)) {
            inserted = true
            state.text = "Inserted. Keep talking to add more, or close."
        } else {
            // The field behind us cannot be focused while our screen is in front, so it cannot be
            // found from here. Keep the text safe and insert it once this screen closes.
            inserted = false
            pendingInsert = text
            copyToClipboard(text)
            state.text = "Copied to the clipboard - close to insert it into your message."
        }
        stopButton.text = "Close"
        render(false)
    }

    /** Closing: if the field never received the text, insert it now that we are getting out of the way. */
    private fun closeAndFinish() {
        val text = pendingInsert
        val service = bridgeAccessibilityInstance
        if (inserted || text.isEmpty() || service == null) {
            finish()
            return
        }
        pendingInsert = ""
        finish()
        java.lang.Thread({
            val ok = service.appendTextRetrying(text)
            android.util.Log.i("DictateActivity", "insert after closing: " + ok)
        }, "dictate-insert").start()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Speech Bridge", text))
    }

    /**
     * Continue dictating. Available at any time, including while the editor is still working, so
     * there is no dead moment where the only sensible thing to do is wait.
     *
     * The already-dictated text is carried into the next session rather than cleared: if the field
     * bridge put it in the message, the field now holds it and the new session starts from there;
     * if it only reached the clipboard, the session itself has to carry it or it would be lost.
     */
    private fun keepTalking() {
        if (!finished) {
            state.text = "Still listening - keep going."
            return
        }
        session?.cancel()
        session = null
        val carried = if (inserted) "" else sessionSoFar
        finished = false
        stopButton.text = "Stop and insert"
        if (inserted) {
            // The field already contains the previous round, so re-read it and start clean.
            committed = bridge.readFocusedText() ?: committed
            heardAll.setLength(0)
            sessionSoFar = ""
        } else {
            sessionSoFar = carried
        }
        startDictation(carried)
    }

    /** Shows everything heard and everything edited, so a pause cannot look like a restart. */
    private fun render(processing: Boolean) {
        val heard = heardAll.toString().trim()
        val edited = sessionSoFar.trim()
        output.text = buildString {
            appendLine("You said:")
            appendLine(if (heard.isEmpty()) "(nothing yet)" else heard)
            appendLine()
            if (processing) {
                append("Editing...")
            } else {
                appendLine("Edited to:")
                append(if (edited.isEmpty()) "(nothing yet)" else edited)
            }
        }
    }

    private inner class FieldSink : Sink {
        override fun onReady() {}

        override fun onSpeechStart() {}

        override fun onRms(db: Float) {}

        override fun onPartial(text: String) {
            sessionSoFar = text
            statusPost(false)
        }

        override fun onHeard(text: String) {
            if (text.isNotBlank()) {
                if (heardAll.isNotEmpty()) heardAll.append(' ')
                heardAll.append(text.trim())
            }
            statusPost(true)
        }

        override fun onEndOfSpeech() {}

        override fun onFinal(sessionText: String, fullMessage: String) {
            sessionSoFar = sessionText
            statusPost(false)
            statusPost { deliver(sessionText, fullMessage) }
        }

        override fun onError(code: Int) {
            finished = true
            statusPost {
                state.text = "Recognition failed (code $code)."
                stopButton.text = "Close"
                render(false)
            }
        }

        private fun statusPost(processing: Boolean) {
            statusPost { render(processing) }
        }

        private fun statusPost(block: () -> Unit) {
            output.post(block)
        }
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        if (this::readBack.isInitialized) readBack.shutdown()
        super.onDestroy()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextSize(26f)
            setAllCaps(false)
        // Spaced and tall enough to tap reliably: closely stacked buttons are easy to hit
        // by mistake.
        minimumHeight = dp(20)
        setPadding(dp(2), dp(7), dp(2), dp(7))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
        }
            setOnClickListener { onClick() }
        }

    private fun hint(body: String): TextView = TextView(this).apply {
        text = body
        setTextSize(24f)
        setTextColor(primaryTextColor(this@DictateActivity))
    }
}

private fun dp(n: Int): Int = n * 4
