package com.example.speechbridge

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.speechbridge.Settings
import com.example.speechbridge.field.bridgeAccessibilityInstance
import com.example.speechbridge.recognition.BridgeRecognitionService
import com.example.speechbridge.recognition.EXTRA_HEARD
import com.example.speechbridge.recognition.EXTRA_PROCESSING

/** Setup + test screen. Built in code to avoid layout/theme dependencies. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    public lateinit var output: TextView
    private lateinit var testButton: Button

    /** Only ever used to ask for the microphone, so it becomes a disabled confirmation once granted. */
    private lateinit var micButton: Button
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val ourComponent by lazy {
        ComponentName(this, RECOGNITION_SERVICE_CLASS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this.getSharedPreferences("speech_bridge", android.content.Context.MODE_PRIVATE))

        val scroller = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(90), dp(6), dp(110))
        }
        output = TextView(this).apply {
            setTextSize(24f)
            setTextColor(primaryTextColor(this@MainActivity))
        }.also { column.addView(it) }

        column.addView(hint("Start here: grant the microphone, pick a speech model, then tap test dictation."))
        micButton = button("Grant microphone permission") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        column.addView(micButton)
        column.addView(button("Speech models") {
            this.getSharedPreferences("speech_bridge", android.content.Context.MODE_PRIVATE).edit().putLong("models_page", 0L).apply()
            startActivity(Intent(this.getBaseContext(), ModelsActivity::class.java))
        })
        column.addView(button("Custom words") { startActivity(Intent(this.getBaseContext(), CustomWordsActivity::class.java)) })

        column.addView(hint("The AI editor rewrites your dictation before it is sent anywhere. Download a local model first, then open the editor settings."))
        column.addView(button("Local AI models") {
            this.getSharedPreferences("speech_bridge", android.content.Context.MODE_PRIVATE).edit().putLong("llm_page", 0L).apply()
            startActivity(Intent(this.getBaseContext(), LlmModelsActivity::class.java))
        })
        column.addView(button("AI editor settings") { startActivity(Intent(this.getBaseContext(), AiSettingsActivity::class.java)) })

        testButton = button("Test dictation") { toggleTest() }
        column.addView(testButton)

        column.addView(hint("These three hand the app to Android itself. The field bridge is an accessibility service: it lets other apps read what you dictated, and can read it back to you."))
        column.addView(button("Choose as voice input / assistant app") { openVoiceInputSettings() })
        column.addView(button("Enable field bridge (Accessibility)") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(button("Set as system recognizer") { setSystemRecognizer() })

        column.addView(toggle("Read the message back", settings.readBack) { settings.readBack = it })
        column.addView(toggle("Use field bridge when enabled", settings.useFieldBridge) { settings.useFieldBridge = it })
        column.addView(toggle("Add a space when continuing after text", settings.prependSpace) { settings.prependSpace = it })

        // Maintenance, so it sits after the things a new user has to do.
        column.addView(hint("Housekeeping."))
        column.addView(button("Free up space") { startActivity(Intent(this.getBaseContext(), SpaceActivity::class.java)) })

        scroller.addView(column)
        setContentView(scroller)
        scroller.setFillViewport(true)
        scroller.requestDisallowInterceptTouchEvent(true)
        scroller.fullScroll(1) // android.view.View.FOCUS_TOP
        scroller.post { scroller.fullScroll(1) }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        // The button is the status. Whether the microphone is granted, which model is selected and
        // whether the editor is on were three lines of grey that pushed the actual task down the
        // screen; the microphone is the only one of them the user has to act on, and it now says
        // so where they would look for it.
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (mic) {
            micButton.text = "Mic permission granted"
            micButton.isEnabled = false
        } else {
            micButton.text = "Grant microphone permission"
            micButton.isEnabled = true
        }
    }

    private fun openVoiceInputSettings() {
        val actions = listOf(
            AndroidSettings.ACTION_VOICE_INPUT_SETTINGS,
            AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            AndroidSettings.ACTION_SETTINGS,
        )
        for (action in actions) {
            try {
                startActivity(Intent(action))
                return
            } catch (e: Throwable) {
            }
        }
    }

    private fun setSystemRecognizer() {
        val granted = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            output.text = buildString {
                appendLine("Android did not let this app write that setting.")
                appendLine("Reinstalling with scripts/install.sh grants it.")
                append("Current system recognizer: " + currentRecognizer())
            }
            return
        }
        val written = try {
            android.provider.Settings.Secure.putString(
                this.getContentResolver(), VOICE_RECOGNIZER_KEY, OUR_RECOGNIZER,
            )
        } catch (e: Throwable) {
            false
        }
        output.text = if (written) {
            "This app is now the system speech recognizer:\n" + currentRecognizer() + "\n\n" +
                "Apps and keyboards that use the default recogniser will now go through it. " +
                "Samsung Keyboard keeps its own two-entry list, so its mic button will not use it."
        } else {
            "Android refused the change. Current value:\n" + currentRecognizer()
        }
    }

    private fun currentRecognizer(): String = try {
        android.provider.Settings.Secure.getString(this.getContentResolver(), VOICE_RECOGNIZER_KEY)
            ?: "(none)"
    } catch (e: Throwable) {
        "(unreadable)"
    }

    private fun toggleTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (recognizer == null) {
            val created = SpeechRecognizer.createSpeechRecognizer(this, ourComponent)
            created.setRecognitionListener(MainActivityListener(this))
            recognizer = created
        }
        val r = recognizer ?: return
        if (listening) {
            r.stopListening()
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            output.text = ""
            r.startListening(intent)
            listening = true
            testButton.text = "Stop"
        }
    }

    fun resetTestButton() {
        listening = false
        testButton.text = "Test dictation"
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
            setTextColor(primaryTextColor(this@MainActivity))
        }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label
            setTextSize(26f)
            setChecked(initial)
            setPadding(0, dp(14), 0, dp(14))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
}

private const val REQ_MIC = 1
private const val RECOGNITION_SERVICE_CLASS = "com.example.speechbridge.recognition.BridgeRecognitionService"

/** Secure settings key that names the default speech recognizer. */
private const val VOICE_RECOGNIZER_KEY = "voice_recognition_service"

/** The flattened component the system stores for this app's recognizer. */
private const val OUR_RECOGNIZER =
    "com.example.speechbridge/com.example.speechbridge.recognition.BridgeRecognitionService"
/** Crude dp -> px conversion; the SDK's own extension lives in android.util.Extensions. */
private fun dp(n: Int): Int = n * 4

/** Listener for the built-in test button. */
class MainActivityListener(private val activity: MainActivity) : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) { activity.output.text = "Listening..." }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {
        activity.output.text = "Error code $error"
        activity.resetTestButton()
    }

    override fun onResults(results: Bundle?) {
        activity.output.text = transcript(results)
        activity.resetTestButton()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        activity.output.text = transcript(partialResults)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}

private fun first(b: Bundle?): String =
    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

/**
 * The test screen shows both halves of the pipeline: the verbatim transcript the
 * recognizer produced, and the text the editor turned it into. The edited text is the
 * ordinary recognition result; the verbatim one rides along in [EXTRA_HEARD].
 */
private fun transcript(b: Bundle?): String {
    val heard = b?.getString(EXTRA_HEARD) ?: ""
    val edited = first(b)
    val processing = b?.getBoolean(EXTRA_PROCESSING, false) ?: false
    return buildString {
        appendLine("You said: " + (if (heard.isBlank()) "(nothing)" else heard))
        if (processing) {
            append("Processing...")
        } else {
            append("Edited to: " + (if (edited.isBlank()) "(nothing)" else edited))
        }
    }
}
