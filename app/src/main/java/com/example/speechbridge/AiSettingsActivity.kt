package com.example.speechbridge

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.speechbridge.Settings
import com.example.speechbridge.ai.AiProvider
import com.example.speechbridge.ai.LlmClient
import com.example.speechbridge.ai.ProviderConfig
import com.example.speechbridge.ai.LlmTransformEngine
import com.example.speechbridge.engine.TransformRequest
import com.example.speechbridge.engine.TransformResult
import com.example.speechbridge.models.ModelCatalog
import com.example.speechbridge.models.ModelStore
import com.example.speechbridge.models.loadModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** AI editor settings: provider, model list, keys, wake word, vocabulary, and a live test. */
class AiSettingsActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var llmCatalog: ModelCatalog
    private lateinit var llmStore: ModelStore
    private lateinit var status: TextView
    private lateinit var baseUrlField: EditText
    private lateinit var modelField: EditText
    private lateinit var keyField: EditText
    private lateinit var commandWordField: EditText
    private lateinit var vocabField: EditText

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this.getSharedPreferences("speech_bridge", android.content.Context.MODE_PRIVATE))
        llmCatalog = loadModelCatalog(assetText("llm-catalog.json"))
        llmStore = ModelStore(this.filesDir.getAbsolutePath(), "llm")

        val scroller = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(90), dp(6), dp(110))
        }
        status = TextView(this).apply {
            setTextSize(24f)
            setTextColor(primaryTextColor(this@AiSettingsActivity))
        }.also { column.addView(it) }
        refreshStatus()

        for (p in AiProvider.entries) {
            column.addView(button(p.label) { settings.provider = p })
        }

        val current = settings.providerConfig(settings.provider)
        baseUrlField = EditText(this).apply { setText(current.baseUrl) }
        modelField = EditText(this).apply { setText(current.model) }
        keyField = EditText(this).apply { setText(current.apiKey) }
        commandWordField = EditText(this).apply { setText(settings.commandWord) }
        vocabField = EditText(this).apply { setText(settings.vocabulary.joinToString("\n")) }
        column.addView(button("Save base URL / model / key / wake word / vocabulary") { saveFields() })

        column.addView(toggle("AI editor on", settings.aiEnabled) { settings.aiEnabled = it })
        column.addView(button("List models for this provider") { listModels() })
        column.addView(button("Test the editor") { runTest() })

        scroller.addView(column)
        setContentView(scroller)
        scroller.setFillViewport(true)
        scroller.requestDisallowInterceptTouchEvent(true)
        scroller.fullScroll(1) // android.view.View.FOCUS_TOP
        scroller.post { scroller.fullScroll(1) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun saveFields() {
        val p = settings.provider
        settings.setProviderConfig(
            p,
            ProviderConfig(
                baseUrl = baseUrlField.text.toString(),
                model = modelField.text.toString(),
                apiKey = keyField.text.toString(),
            ),
        )
        settings.commandWord = commandWordField.text.toString().trim()
        settings.vocabulary = vocabField.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        refreshStatus()
    }

    private fun listModels() {
        val provider = settings.provider
        val cfg = settings.providerConfig(provider)
        val client = LlmClient()
        scope.launch {
            try {
                val ids = client.listModels(provider, cfg)
                status.post { status.text = "Models (${provider.label}): ${ids.joinToString(", ")}" }
            } catch (e: Throwable) {
                android.util.Log.w("AiSettingsActivity", "Model list failed", e)
                status.post { status.text = "Model list failed: ${e.message}" }
            }
        }
    }

    private fun runTest() {
        val engine = LlmTransformEngine(settings, llmCatalog, llmStore)
        scope.launch {
            val result: TransformResult = engine.apply(
                TransformRequest("", "I told Elaina to call back", "no I said A L A N A"),
            )
            val why = engine.lastError
            status.post {
                status.text = "Session text: ${result.sessionText}" +
                    (if (why == null) "" else "\nFell back because: $why")
            }
        }
    }

    private fun refreshStatus() {
        val p = settings.provider
        val cfg = settings.providerConfig(p)
        status.text = buildString {
            appendLine("Provider: ${p.label} (${p.id})")
            appendLine("Base URL: ${cfg.resolvedBase(p).ifBlank { "not set" }}")
            appendLine("Model: ${cfg.model.ifBlank { "not set" }}")
            appendLine("Key: ${if (cfg.apiKey.isBlank()) "none" else "set"}")
            appendLine("AI editor: ${if (settings.aiEnabled) "on" else "off"}")
            appendLine("Wake word: ${settings.commandWord.ifBlank { "none" }}")
            appendLine("Vocabulary: ${settings.vocabulary.joinToString(", ")}")
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

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label
            setChecked(initial)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    private fun assetText(name: String): String? = try {
        assets.open(name).use { it.bufferedReader().readText() }
    } catch (e: Throwable) {
        android.util.Log.w("AiSettingsActivity", "Missing asset $name", e)
        null
    }
}

/** Crude dp -> px conversion. */
private fun dp(n: Int): Int = n * 4
