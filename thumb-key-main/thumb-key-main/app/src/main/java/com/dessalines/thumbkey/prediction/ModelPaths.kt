package com.dessalines.thumbkey.prediction

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.dessalines.thumbkey.R
import com.dessalines.thumbkey.utils.TAG
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.io.FileOutputStream

/** Default model resource name (matches res/raw/ml4_q6_k.gguf). */
private const val BASE_MODEL_NAME = "ml4_q6_k"

/**
 * ModelPaths manages language model files for ThumbKey's LLM prediction.
 *
 * Supports:
 *  - Bundled default model from res/raw
 *  - Per-language models (e.g. `en.gguf`, `es.gguf`)
 *  - User model import/export with validation
 *  - Model hot-reload notifications via [modelOptionsUpdated]
 */
object ModelPaths {

    /**
     * Emits when model options change (import, delete, or locale switch).
     * The PredictionBridge can collect this to reload.
     */
    val modelOptionsUpdated = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    /**
     * Get the directory where models are stored.
     */
    fun getModelDirectory(context: Context): File {
        val modelDirectory = File(context.filesDir, "transformer-models")
        if (!modelDirectory.isDirectory) {
            modelDirectory.mkdirs()
        }
        return modelDirectory
    }

    /**
     * Get the raw resource ID for the bundled model.
     */
    private fun getBaseModelResourceId(): Int {
        return try {
            R.raw.ml4_q6_k
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Ensure the default bundled model exists in internal storage.
     */
    private fun ensureDefaultModelExists(context: Context): Boolean {
        val directory = getModelDirectory(context)
        val tgtFile = File(directory, "$BASE_MODEL_NAME.gguf")

        if (tgtFile.isFile && tgtFile.length() > 0) {
            return true
        }

        val resourceId = getBaseModelResourceId()
        if (resourceId != -1) {
            return try {
                context.resources.openRawResource(resourceId).use { inputStream ->
                    FileOutputStream(tgtFile).use { outputStream ->
                        val bytes = ByteArray(8192)
                        var read: Int
                        while (inputStream.read(bytes).also { read = it } != -1) {
                            outputStream.write(bytes, 0, read)
                        }
                    }
                }
                Log.i(TAG, "Default model copied to ${tgtFile.absolutePath} (${tgtFile.length() / 1024 / 1024}MB)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy default model from res/raw", e)
                ensureDefaultModelFromAssets(context)
            }
        }
        return ensureDefaultModelFromAssets(context)
    }

    private fun ensureDefaultModelFromAssets(context: Context): Boolean {
        val directory = getModelDirectory(context)
        return try {
            val assets = context.assets.list("models") ?: emptyArray()
            val modelFile = assets.firstOrNull {
                it.endsWith(".gguf", ignoreCase = true) || it.endsWith(".bin", ignoreCase = true)
            }
            if (modelFile != null) {
                val tgtFile = File(directory, modelFile)
                if (tgtFile.exists()) return true
                context.assets.open("models/$modelFile").use { inputStream ->
                    FileOutputStream(tgtFile).use { outputStream ->
                        val bytes = ByteArray(8192)
                        var read: Int
                        while (inputStream.read(bytes).also { read = it } != -1) {
                            outputStream.write(bytes, 0, read)
                        }
                    }
                }
                Log.i(TAG, "Model copied from assets: ${tgtFile.absolutePath}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "No assets/models/ or failed to copy: ${e.message}")
            false
        }
    }

    // ── Per-language model support ───────────────────────────────────

    /**
     * Get available model options per language.
     * Scans the model directory for files named like `en.gguf`, `es.gguf`, etc.
     * Also includes the default model as a fallback.
     *
     * @return Map of language code → model file
     */
    fun getModelOptions(context: Context): Map<String, File> {
        val directory = getModelDirectory(context)
        ensureDefaultModelExists(context)

        val result = mutableMapOf<String, File>()

        val models = directory.listFiles()
            ?.filter { it.isFile && isModelFile(it.name) }
            ?: emptyList()

        for (model in models) {
            val nameWithoutExt = model.nameWithoutExtension.lowercase()

            // Check if filename is a language code (e.g. "en", "es", "fr")
            if (nameWithoutExt.length == 2 && nameWithoutExt.all { it.isLetter() }) {
                result[nameWithoutExt] = model
            }
        }

        // Fallback: if no language-specific model, use the default model for "en"
        if (result.isEmpty()) {
            val defaultModel = models.maxByOrNull { it.lastModified() }
            if (defaultModel != null) {
                result["en"] = defaultModel
                // Also make it the fallback for any language
                result["default"] = defaultModel
            }
        }

        // Always ensure "default" key exists if there's any model
        if ("default" !in result && models.isNotEmpty()) {
            result["default"] = models.maxByOrNull { it.lastModified() }!!
        }

        return result
    }

    /**
     * Get the best model for a given language code.
     * Falls back to default model if no language-specific model exists.
     */
    fun getModelForLanguage(context: Context, languageCode: String): File? {
        val options = getModelOptions(context)
        return options[languageCode.lowercase()] ?: options["default"]
    }

    /**
     * Get the default model file path.
     */
    fun getDefaultModelPath(context: Context): File? {
        val directory = getModelDirectory(context)

        val existingModel = directory.listFiles()
            ?.filter { it.isFile && isModelFile(it.name) }
            ?.maxByOrNull { it.lastModified() }

        if (existingModel != null) return existingModel

        ensureDefaultModelExists(context)

        return directory.listFiles()
            ?.filter { it.isFile && isModelFile(it.name) }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * Get all available model files.
     */
    fun getModels(context: Context): List<File> {
        return getModelDirectory(context).listFiles()
            ?.filter { it.isFile && isModelFile(it.name) }
            ?: emptyList()
    }

    /**
     * Import a model from a URI (e.g., user file picker).
     */
    fun importModel(context: Context, uri: Uri): File {
        val modelDirectory = getModelDirectory(context)

        val fileName = context.contentResolver.query(uri, null, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val colIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (colIdx != -1) it.getString(colIdx) else null
            } else null
        } ?: throw IllegalArgumentException("Could not read file name")

        val file = File(modelDirectory, fileName)
        if (file.exists()) {
            throw IllegalArgumentException("Model \"${file.name}\" already exists!")
        }
        if (!isModelFile(file.name)) {
            throw IllegalArgumentException("Extension must be .gguf or .bin")
        }

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val bytes = ByteArray(1024)
            var read = inputStream.read(bytes)

            // Validate GGUF magic
            if (file.extension.lowercase() == "gguf") {
                if (read < 4 ||
                    bytes[0] != 'G'.code.toByte() ||
                    bytes[1] != 'G'.code.toByte() ||
                    bytes[2] != 'U'.code.toByte() ||
                    bytes[3] != 'F'.code.toByte()
                ) {
                    throw IllegalArgumentException("\"${file.name}\" is not a valid GGUF file")
                }
            }

            file.outputStream().use { outputStream ->
                outputStream.write(bytes, 0, read)
                while (inputStream.read(bytes).also { read = it } != -1) {
                    outputStream.write(bytes, 0, read)
                }
            }
        } ?: throw IllegalArgumentException("Could not open input stream")

        if (file.length() == 0L) {
            file.delete()
            throw IllegalArgumentException("Imported file is empty or corrupted")
        }

        Log.i(TAG, "Model imported: ${file.absolutePath} (${file.length() / 1024 / 1024}MB)")
        modelOptionsUpdated.tryEmit(Unit)
        return file
    }

    /**
     * Export a model to a URI.
     */
    fun exportModel(context: Context, uri: Uri, file: File) {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            file.inputStream().use { inputStream ->
                val bytes = ByteArray(8192)
                var read: Int
                while (inputStream.read(bytes).also { read = it } != -1) {
                    outputStream.write(bytes, 0, read)
                }
            }
        } ?: throw IllegalArgumentException("Could not open output stream")
    }

    /**
     * Delete a model file.
     */
    fun deleteModel(file: File): Boolean {
        val result = if (file.exists() && file.isFile) file.delete() else false
        if (result) modelOptionsUpdated.tryEmit(Unit)
        return result
    }

    private fun isModelFile(name: String): Boolean {
        return name.endsWith(".gguf", ignoreCase = true) ||
            name.endsWith(".bin", ignoreCase = true)
    }
}
