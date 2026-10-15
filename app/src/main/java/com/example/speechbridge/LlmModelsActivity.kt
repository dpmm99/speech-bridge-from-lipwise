package com.example.speechbridge

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.speechbridge.Settings
import com.example.speechbridge.ai.AiProvider
import com.example.speechbridge.ai.ProviderConfig
import com.example.speechbridge.ai.llmServer
import com.example.speechbridge.models.ModelCatalog
import com.example.speechbridge.models.CatalogModel
import com.example.speechbridge.models.ModelDownloader
import com.example.speechbridge.models.ModelStore
import com.example.speechbridge.models.loadModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Same as [ModelsActivity] but for the local LLM catalog (llm-catalog.json). The selected id is
 * stored as the "local" provider's model, which is what [LlmTransformEngine] loads through JNI.
 */
class LlmModelsActivity : Activity() {

    private lateinit var settings: Settings
    private var catalog = ModelCatalog(emptyList<String>(), emptyList<CatalogModel>())
    private lateinit var store: ModelStore
    private lateinit var downloader: ModelDownloader
    private lateinit var status: TextView
    private var cancelRequested = false
    private lateinit var prefs: SharedPreferences
    private var page = 0
    private var downloading = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = this.getSharedPreferences("speech_bridge", android.content.Context.MODE_PRIVATE)
        settings = Settings(prefs)
        page = prefs.getLong("llm_page", 0L).toInt()
        catalog = loadModelCatalog(assetText("llm-catalog.json"))
        store = ModelStore(this.filesDir.getAbsolutePath(), "llm")
        downloader = ModelDownloader(catalog.mirrors, store)

        val scroller = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(90), dp(6), dp(110))
        }
        status = TextView(this).apply {
            setTextSize(24f)
            setTextColor(primaryTextColor(this@LlmModelsActivity))
        }.also { column.addView(it) }
        refreshStatus()
        if (downloading) {
            column.addView(button("Cancel download") { cancelModelDownload(this) })
        }

        val ordered = orderedForUi(catalog.models)
        val visible = ordered.drop(page * LLM_PAGE).take(LLM_PAGE)
        for ((i, m) in visible.withIndex()) {
            val first = page * LLM_PAGE + i == 0
            column.addView(button(if (first) "Suggested: ${modelLabel(m)}" else modelLabel(m)) { selectAndDownload(m) })
        }
        if (ordered.size > (page + 1) * LLM_PAGE) {
            column.addView(button("More models") {
                page += LLM_PAGE
                prefs.edit().putLong("llm_page", page.toLong()).apply()
                startActivity(Intent(this.getBaseContext(), LlmModelsActivity::class.java))
            })
        }

        scroller.addView(column)
        setContentView(scroller)
        scroller.setFillViewport(true)
        scroller.requestDisallowInterceptTouchEvent(true)
        scroller.fullScroll(1) // android.view.View.FOCUS_TOP
        scroller.post { scroller.fullScroll(1) }
    }

    override fun onResume() {
        super.onResume()
        // A download may have finished in the background while this screen was away.
        downloading = false
        if (this::status.isInitialized) refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun selectAndDownload(model: CatalogModel) {
        val current = settings.providerConfig(AiProvider.LOCAL)
        settings.setProviderConfig(
            AiProvider.LOCAL,
            ProviderConfig(baseUrl = current.baseUrl, model = model.id, apiKey = current.apiKey),
        )
        cancelRequested = false
        refreshStatus()
        if (store.isDownloaded(model)) {
            // Switching to a model that is already on disk: load it now.
            val server = llmServer
            if (server != null) server.preloadAsync() else Unit
            status.post { refreshStatus() }
            return
        }

        // The download runs in a foreground service: it keeps going when this screen is closed
        // and its progress and Cancel live in the notification.
        requestNotificationPermission()
        startModelDownload(this, KIND_LLM, model.id)
        downloading = true
        status.text = "${model.id}: downloading - progress is in the notification"
    }

    /** Android 13+ needs this before an app may post a notification at all. */
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun refreshStatus() {
        val selected = settings.providerConfig(AiProvider.LOCAL).model
        val model = catalog.find(selected)
        val server = llmServer
        status.text = buildString {
            appendLine("Local AI model: ${selected.ifBlank { "none" }}")
            if (model != null) {
                appendLine(if (store.isDownloaded(model)) "Downloaded: yes" else "Downloaded: not yet")
            }
            appendLine("Engine: " + (if (server == null) "unavailable" else server.statusText()))
            val why = if (server == null) null else server.lastError
            if (why != null) appendLine("Note: " + why)
        }
    }

    /** The suggested model first, then the catalog's own order. */
    private fun orderedForUi(models: List<CatalogModel>): List<CatalogModel> {
        val suggested = appConfig.llm.defaultModel
        return models.filter { it.id == suggested } + models.filter { it.id != suggested }
    }

    private fun modelLabel(model: CatalogModel): String {
        val spec = model.defaultFile()
        val size = if (spec == null) "?" else "${spec.sizeBytes / (1024 * 1024)} MB"
        val short = model.id.removePrefix("unsloth/").removePrefix("LiquidAI/").removeSuffix("-GGUF")
        val done = if (store.isDownloaded(model)) " (ok)" else ""
        return "$short\n$size$done"
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextSize(26f)
            setAllCaps(false)
            // A long model name plus its size can need three lines, and the default cap of two
            // ellipsized the tail - which silently dropped the file size. Let it wrap instead.
            maxLines = 4
            ellipsize = null
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

    private fun assetText(name: String): String? = try {
        assets.open(name).use { it.bufferedReader().readText() }
    } catch (e: Throwable) {
        android.util.Log.w("LlmModelsActivity", "Missing asset $name", e)
        null
    }
}

/** Models shown per screen; "More models" pages through the rest. */
private const val LLM_PAGE = 6

/** Request code for the POST_NOTIFICATIONS prompt. */
private const val REQ_NOTIFICATIONS = 2

/** Crude dp -> px conversion. */
private fun dp(n: Int): Int = n * 4
