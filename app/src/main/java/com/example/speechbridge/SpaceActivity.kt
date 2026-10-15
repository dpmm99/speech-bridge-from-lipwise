package com.example.speechbridge

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.speechbridge.models.CatalogModel
import com.example.speechbridge.models.ModelCatalog
import com.example.speechbridge.models.ModelStore
import com.example.speechbridge.models.loadModelCatalog
import java.io.File

/**
 * Space management: shows what the downloads actually occupy and offers a delete button per model,
 * including folders left on disk by models the catalogs no longer list.
 */
class SpaceActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var column: LinearLayout
    private lateinit var filesRoot: String
    private lateinit var speechStore: ModelStore
    private lateinit var llmStore: ModelStore
    private var speechCatalog = ModelCatalog(emptyList<String>(), emptyList<CatalogModel>())
    private var llmCatalog = ModelCatalog(emptyList<String>(), emptyList<CatalogModel>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filesRoot = this.filesDir.getAbsolutePath()
        android.util.Log.w("SpaceActivity", "root=$filesRoot")
        speechStore = ModelStore(filesRoot, "models")
        llmStore = ModelStore(filesRoot, "llm")
        speechCatalog = loadModelCatalog(assetText("catalog.json"))
        llmCatalog = loadModelCatalog(assetText("llm-catalog.json"))

        val scroller = ScrollView(this)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(90), dp(6), dp(110))
        }
        status = TextView(this).apply {
            setTextSize(24f)
            setTextColor(primaryTextColor(this@SpaceActivity))
        }.also { column.addView(it) }
        refreshStatus()

        addDeletes(speechStore, speechCatalog)
        addDeletes(llmStore, llmCatalog)
        addPartials(speechStore, speechCatalog)
        addPartials(llmStore, llmCatalog)
        addOrphans(speechStore, speechCatalog)
        addOrphans(llmStore, llmCatalog)

        scroller.addView(column)
        setContentView(scroller)
        scroller.setFillViewport(true)
        scroller.requestDisallowInterceptTouchEvent(true)
        scroller.fullScroll(1) // android.view.View.FOCUS_TOP
        scroller.post { scroller.fullScroll(1) }
    }

    /** One delete button per catalog model whose bytes are on disk. */
    private fun addDeletes(store: ModelStore, catalog: ModelCatalog) {
        for (m in catalog.models) {
            if (!store.isDownloaded(m)) continue
            val dir = store.dir(m)
            column.addView(button("Delete ${shortName(m.id)} (${human(bytesOf(dir))})") {
                store.delete(m)
                refreshStatus()
            })
        }
    }

    /**
     * A model directory that holds bytes but is not a complete download - the `.part` left behind
     * by a cancelled download, or by the phone being switched off mid-download. Without this pass
     * those sit on disk with no way to reclaim them: [addDeletes] skips anything incomplete and
     * [addOrphans] skips anything a catalog entry knows about.
     */
    private fun addPartials(store: ModelStore, catalog: ModelCatalog) {
        for (m in catalog.models) {
            if (store.isDownloaded(m)) continue
            val dir = store.dir(m)
            if (!dir.exists()) continue
            val used = bytesOf(dir)
            if (used <= 0L) continue
            val label = if (hasPartial(dir)) "Delete unfinished download" else "Delete leftover"
            column.addView(button("$label ${shortName(m.id)} (${human(used)})") {
                dir.deleteRecursively()
                refreshStatus()
            })
        }
    }

    /** True when [dir] holds a `.part` file, i.e. a download that never completed. */
    private fun hasPartial(dir: java.io.File): Boolean {
        val kids = dir.listFiles() ?: return false
        for (k in kids) if (k.getName().endsWith(".part")) return true
        return false
    }

    /** Folders on disk that no catalog entry knows about - leftovers from swapped-out models. */
    private fun addOrphans(store: ModelStore, catalog: ModelCatalog) {
        val known = catalog.models.map { store.dir(it).getAbsolutePath() }
        for (d in childDirs(store.root)) {
            if (known.contains(d.getAbsolutePath())) continue
            column.addView(button("Delete folder ${d.getName()} (${human(bytesOf(d))})") {
                d.deleteRecursively()
                refreshStatus()
            })
        }
    }

    private fun refreshStatus() {
        val used = bytesOf(speechStore.root) + bytesOf(llmStore.root)
        val free = java.io.File(filesRoot).getFreeSpace()
        status.text = buildString {
            appendLine("Used by models: ${human(used)}")
            appendLine("Free on device: ${human(free)}")
        }
    }

    private fun shortName(id: String): String =
        id.removePrefix("handy-computer/").removePrefix("unsloth/").removePrefix("LiquidAI/").take(28)

    private fun human(bytes: Long): String {
        val mb = bytes / (1024L * 1024L)
        if (mb < 1024L) return "${mb} MB"
        return "${bytes / (1024L * 1024L * 1024L / 10L) / 10.0} GB"
    }

    private fun bytesOf(f: java.io.File): Long {
        val kids = f.listFiles() ?: return 0L
        var total = 0L
        for (k in kids) total += if (k.isDirectory()) bytesOf(k) else k.length()
        return total
    }

    private fun childDirs(f: java.io.File): List<java.io.File> {
        val kids = f.listFiles() ?: return emptyList<java.io.File>()
        return kids.toList().filter { it.isDirectory() }
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

    private fun assetText(name: String): String? = try {
        assets.open(name).use { it.bufferedReader().readText() }
    } catch (e: Throwable) {
        android.util.Log.w("SpaceActivity", "Missing asset $name", e)
        null
    }
}

/** Crude dp -> px conversion. */
private fun dp(n: Int): Int = n * 4
