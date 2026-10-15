package com.example.speechbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Editor for the spellings the recogniser keeps getting wrong: names, jargon, products.
 *
 * The list always ends with one empty box, and typing into it grows another. There is no "add"
 * button because the empty box *is* the add affordance - it is always there, always ready, and
 * the list never looks finished. Removing is a per-row button, since deletion is not something
 * the user can express by typing.
 */
class CustomWordsActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var rows: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var status: TextView
    private lateinit var commandWord: EditText

    private val boxes = mutableListOf<EditText>()
    private var growing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this.getSharedPreferences("speech_bridge", android.content.Context.MODE_PRIVATE))

        scroller = ScrollView(this)
        val scroller = this.scroller
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(90), dp(6), dp(280))
        }

        status = TextView(this).apply {
            setTextSize(24f)
            setTextColor(primaryTextColor(this@CustomWordsActivity))
        }.also { column.addView(it) }

        column.addView(heading("Command word"))
        commandWord = EditText(this).apply {
            setText(settings.commandWord)
            setTextSize(26f)
            hint = "e.g. Oops"
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    settings.commandWord = s?.toString()?.trim() ?: ""
                    refreshStatus()
                }
            })
        }
        column.addView(commandWord)
        column.addView(hint("Start an utterance with this word to tell the editor you are correcting something, not dictating. \"Oops, I said DeepSeek\". Only the editor is told about it - the recogniser hears it like any other word. Leave it empty and corrections are inferred from what you said instead."))

        column.addView(heading("Misheard words"))
        column.addView(hint("Words you use that get misheard. \"Alana\" here means the recogniser stops preferring \"Atlanta\" or \"Elaina\", and the editor is told to spell it this way."))

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rows)


        scroller.addView(column)
        setContentView(scroller)
        scroller.setFillViewport(true)
        scroller.requestDisallowInterceptTouchEvent(true)
        scroller.fullScroll(1) // android.view.View.FOCUS_TOP
        scroller.post { scroller.fullScroll(1) }

        val existing = settings.vocabulary
        if (existing.isEmpty()) {
            addBox("")
        } else {
            for (word in existing) addBox(word)
            addBox("") // the box that is always ready for the next one
        }
        refreshStatus()
    }

    /** One editable word, with a Remove button beside it. */
    private fun addBox(text: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val box = EditText(this).apply {
            setText(text)
            setTextSize(26f)
            hint = "another word"
            setSingleLine(true)
        }
        // With the keyboard up, the box being typed in can sit underneath it. Scrolling the row
        // to the top of the viewport puts it where the keyboard cannot reach, and the generous
        // bottom padding above leaves enough content below to make that scroll possible.
        box.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val target = rows.getTop() + row.getTop()
                scroller.postDelayed({ scroller.smoothScrollTo(0, maxOf(0, target - dp(2))) }, 300L)
            }
        }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onEdited()
        })
        row.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // The Remove button must size itself here: the standard button width is MATCH_PARENT,
        // which in a horizontal row would take the whole width and squeeze the box to nothing.
        val remove = button("Remove") { removeBox(box) }
        remove.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        row.addView(remove)
        rows.addView(row)
        boxes.add(box)
    }

    private fun removeBox(box: EditText) {
        val row = box.parent as? LinearLayout ?: return
        rows.removeView(row)
        boxes.remove(box)
        if (boxes.isEmpty()) addBox("")
        onEdited()
    }

    /**
     * Called on every keystroke: persist what is there, and keep exactly one empty box at the
     * end so there is always somewhere to type the next word.
     */
    private fun onEdited() {
        val words = mutableListOf<String>()
        for (b in boxes) {
            val t = b.text.toString().trim()
            if (t.isNotEmpty() && !words.contains(t)) words.add(t)
        }
        settings.vocabulary = words

        if (growing) return
        val last = if (boxes.isEmpty()) null else boxes[boxes.size - 1]
        if (last == null || last.text.toString().trim().isNotEmpty()) {
            growing = true
            addBox("")
            growing = false
            // Deliberately no requestFocus(): the extra box has to exist so there is always
            // somewhere to type the next word, but taking focus would send the rest of the
            // current word into it - one box per letter.
        }
        refreshStatus()
    }

    private fun heading(body: String): TextView =
        TextView(this).apply {
            text = body
            setTextSize(24f)
            setPadding(0, dp(6), 0, dp(2))
        }

    private fun refreshStatus() {
        // Only the line that changes what the user does. Echoing "Command word: ..." and the word
        // list back at them pushed this down and made it hard to find.
        status.setTextSize(24f)
        status.setTextColor(primaryTextColor(this))
        status.text = if (biasSupported(settings.selectedModel, appConfig.speech.biasFamilies)) {
            "The selected model feeds these to the recogniser while you speak."
        } else {
            "The selected model cannot be biased, so these only reach the editor afterwards. " +
                "A model marked \"(allows bias)\" on the speech models screen would catch " +
                "them as you speak."
        }
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

    private fun hint(body: String): TextView =
        TextView(this).apply {
            text = body
            setTextSize(24f)
            setTextColor(primaryTextColor(this@CustomWordsActivity))
        }
}

/** Crude dp -> px conversion; the SDK's own extension lives in android.util.Extensions. */
private fun dp(n: Int): Int = n * 4
