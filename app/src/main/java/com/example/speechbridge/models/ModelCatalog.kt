package com.example.speechbridge.models

import android.util.Log
import java.io.File
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The model catalog, read from a JSON asset so the APK stays small.
 *
 * The asset mirrors Lipwise's catalog.json (HF ids, revisions, per-file quantisations). Downloads
 * go to the mirror list when the file has a sha256 (blob.handy.computer serves the same bytes);
 * otherwise they go to huggingface.co.
 */
class CatalogFile(val filename: String, val quant: String, val sizeBytes: Long, val sha256: String?)

class CatalogModel(
    val id: String,
    val revision: String,
    val description: String,
    val homepageUrl: String,
    val files: List<CatalogFile>,
    val defaultQuant: String = "",
    val recommendedRank: Int? = null,
    val speedScore: Double = 0.0,
    val streaming: Boolean = false,
    val languages: List<String> = emptyList<String>(),
) {
    fun defaultFile(): CatalogFile? = files.firstOrNull { it.quant == defaultQuant } ?: files.firstOrNull()
}

class ModelCatalog(val mirrors: List<String>, val models: List<CatalogModel>) {
    fun find(id: String?): CatalogModel? = models.firstOrNull { it.id == id }
}

private const val TAG = "ModelCatalog"

fun parseModelCatalog(text: String): ModelCatalog {
    val root = JSONObject(text)
    return ModelCatalog(stringList(root.optJSONArray("mirrors")), objectList(root.getJSONArray("models")).map { parseModel(it) })
}

fun loadModelCatalog(assetText: String?): ModelCatalog = try {
    parseModelCatalog(assetText ?: "")
} catch (e: Throwable) {
    android.util.Log.w(TAG, "No usable catalog asset", e)
    ModelCatalog(emptyList<String>(), emptyList<CatalogModel>())
}

private fun parseModel(m: JSONObject): CatalogModel = CatalogModel(
    id = m.getString("id"),
    revision = m.optString("revision", "main"),
    description = m.optString("description", ""),
    homepageUrl = m.optString("homepage_url", m.optString("homepageUrl", "")),
    files = objectList(m.getJSONArray("files")).map { parseFile(it) },
    defaultQuant = m.optString("default_quant", m.optString("defaultQuant", "")),
    recommendedRank = if (m.has("recommended_rank") && !m.isNull("recommended_rank")) m.getInt("recommended_rank") else null,
    speedScore = m.optDouble("speed_score", m.optDouble("speedScore", 0.0)),
    streaming = run {
        val caps = m.optJSONObject("capabilities")
        if (caps == null) false else caps.optBoolean("streaming", false)
    },
    languages = stringList(m.optJSONArray("languages")),
)

private fun parseFile(f: JSONObject): CatalogFile = CatalogFile(
    filename = f.getString("filename"),
    quant = f.optString("quant", ""),
    sizeBytes = f.optLong("size_bytes", f.optLong("sizeBytes", 0L)),
    sha256 = if (f.has("sha256") && !f.isNull("sha256")) f.optString("sha256", "").ifBlank { null } else null,
)

private fun stringList(arr: JSONArray?): List<String> =
    if (arr == null) emptyList<String>() else List(arr.length()) { i -> arr.getString(i) }

private fun objectList(arr: JSONArray?): List<JSONObject> =
    if (arr == null) emptyList<JSONObject>() else List(arr.length()) { i -> arr.getJSONObject(i) }
