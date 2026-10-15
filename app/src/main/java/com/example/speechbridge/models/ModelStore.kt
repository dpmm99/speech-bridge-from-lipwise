package com.example.speechbridge.models

import java.io.File

/**
 * Where downloaded models live on disk: <files dir>/<subdir>/<model id with '/' -> '__'>/<filename>.
 * One instance for speech models ("models"), one for local LLMs ("llm").
 */
class ModelStore(private val filesDir: String, private val subdir: String) {

    val root: java.io.File = java.io.File(filesDir, subdir)

    fun dir(model: CatalogModel): java.io.File = java.io.File(root, model.id.replace("/", "__"))

    fun file(model: CatalogModel): java.io.File {
        val spec = model.defaultFile() ?: return dir(model)
        return java.io.File(dir(model), spec.filename)
    }

    /** A model counts as downloaded only when the bytes are all there. */
    fun isDownloaded(model: CatalogModel): Boolean {
        val spec = model.defaultFile() ?: return false
        val f = file(model)
        return f.exists() && (spec.sizeBytes <= 0L || f.length() == spec.sizeBytes)
    }

    fun partsOf(model: CatalogModel): List<CatalogFile> = model.files

    fun delete(model: CatalogModel) {
        dir(model).deleteRecursively()
    }
}
