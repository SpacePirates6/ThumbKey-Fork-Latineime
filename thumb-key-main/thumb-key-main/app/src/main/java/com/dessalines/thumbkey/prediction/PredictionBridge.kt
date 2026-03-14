package com.dessalines.thumbkey.prediction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.xlm.LMResult
import org.futo.inputmethod.latin.xlm.LanguageModel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bridge between ThumbKey and the FUTO LanguageModel.
 *
 * Wraps the native LLM with:
 *  • Crash-guard mechanism to prevent crash loops
 *  • Multi-result predictions (not just the first)
 *  • Re-scoring of external suggestions against the LLM context
 *  • Banned-word filtering
 *  • Thread-safe lifecycle management
 */
class PredictionBridge(private val context: Context) {

    private lateinit var languageModel: LanguageModel
    private val isInitialized = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "ThumbKey"
        private const val PREFS_NAME = "ai_crash_guard"
        private const val KEY_LOADING_IN_PROGRESS = "model_loading_in_progress"
        private const val KEY_CRASH_COUNT = "model_crash_count"
        private const val MAX_CRASH_COUNT = 2

        fun isSafeToLoad(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wasLoading = prefs.getBoolean(KEY_LOADING_IN_PROGRESS, false)
            val crashCount = prefs.getInt(KEY_CRASH_COUNT, 0)

            if (wasLoading) {
                val newCrashCount = crashCount + 1
                prefs.edit()
                    .putBoolean(KEY_LOADING_IN_PROGRESS, false)
                    .putInt(KEY_CRASH_COUNT, newCrashCount)
                    .commit()
                Log.e(TAG, "CRASH GUARD: Detected crash during model loading (count: $newCrashCount/$MAX_CRASH_COUNT)")
                if (newCrashCount >= MAX_CRASH_COUNT) {
                    Log.e(TAG, "CRASH GUARD: Too many crashes. AI disabled.")
                    return false
                }
            }

            if (crashCount >= MAX_CRASH_COUNT) {
                Log.w(TAG, "CRASH GUARD: AI disabled due to previous crashes ($crashCount).")
                return false
            }
            return true
        }

        fun resetCrashGuard(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CRASH_COUNT, 0)
                .putBoolean(KEY_LOADING_IN_PROGRESS, false)
                .apply()
            Log.i(TAG, "CRASH GUARD: Reset by user")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun showToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        mainHandler.post { Toast.makeText(context, message, duration).show() }
    }

    private fun setCrashGuardLoading(loading: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOADING_IN_PROGRESS, loading)
            .commit()
    }

    private fun clearCrashCount() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CRASH_COUNT, 0)
            .putBoolean(KEY_LOADING_IN_PROGRESS, false)
            .apply()
    }

    // ── Initialization ──────────────────────────────────────────────

    suspend fun initialize(modelPath: File? = null): Boolean {
        if (isInitializing.getAndSet(true)) {
            kotlinx.coroutines.delay(100)
            if (isInitialized.get()) return true
            return false
        }

        if (isInitialized.get()) {
            isInitializing.set(false)
            return true
        }

        // Load native library on Main thread
        try {
            withContext(Dispatchers.Main) {
                LanguageModel.loadNativeLibrary()
                languageModel = LanguageModel.getInstance()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load native library", e)
            isInitializing.set(false)
            showToast("Failed to load native library: ${e.message}")
            return false
        }

        return try {
            withContext(Dispatchers.IO) {
                try {
                    val modelFile = modelPath ?: ModelPaths.getDefaultModelPath(context)

                    if (modelFile == null || !modelFile.exists()) {
                        Log.w(TAG, "No model file found.")
                        showToast("No model file found. Prediction disabled.")
                        isInitializing.set(false)
                        return@withContext false
                    }

                    if (modelFile.length() == 0L) {
                        Log.e(TAG, "Model file is empty: ${modelFile.absolutePath}")
                        showToast("Model file is empty or corrupted")
                        isInitializing.set(false)
                        return@withContext false
                    }

                    Log.i(TAG, "Loading model: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024}MB)")
                    setCrashGuardLoading(true)

                    val success = try {
                        languageModel.load(
                            modelPath = modelFile.absolutePath,
                            cacheDir = ModelPaths.getModelDirectory(context).absolutePath,
                            useGpu = false,
                        )
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Native library not loaded", e)
                        showToast("Native library not loaded.")
                        false
                    } catch (e: Exception) {
                        Log.e(TAG, "Native crash during model loading", e)
                        showToast("Native crash during model loading: ${e.message}")
                        false
                    }

                    if (success && languageModel.isLoaded()) {
                        isInitialized.set(true)
                        clearCrashCount()
                        Log.i(TAG, "LLM initialized: ${modelFile.name}")
                        showToast("Prediction model loaded", Toast.LENGTH_SHORT)
                    } else {
                        setCrashGuardLoading(false)
                        Log.e(TAG, "Failed to load LLM.")
                        showToast("Failed to load model. May be incompatible.")
                        isInitialized.set(false)
                    }

                    isInitializing.set(false)
                    success
                } catch (e: OutOfMemoryError) {
                    setCrashGuardLoading(false)
                    Log.e(TAG, "OOM loading model", e)
                    showToast("Out of memory: Model too large")
                    isInitializing.set(false)
                    false
                } catch (e: Exception) {
                    setCrashGuardLoading(false)
                    Log.e(TAG, "Failed to initialize", e)
                    showToast("Failed to initialize prediction: ${e.message}")
                    isInitializing.set(false)
                    false
                }
            }
        } catch (e: Exception) {
            setCrashGuardLoading(false)
            Log.e(TAG, "Unexpected error", e)
            showToast("Unexpected error: ${e.message}")
            isInitializing.set(false)
            false
        }
    }

    // ── Single prediction (backwards compat) ────────────────────────

    fun getPrediction(textBeforeCursor: String): String? {
        val result = getAllPredictions(textBeforeCursor)
        return result.predictions.firstOrNull()?.first
    }

    // ── Multi-prediction ────────────────────────────────────────────

    /**
     * Get ALL predictions from the LLM for the given input.
     *
     * @param textBeforeCursor Full text before the cursor
     * @param autocorrectThreshold Confidence threshold for autocorrect
     * @param bannedWords Words to exclude from results
     * @param composeX Optional X coordinates per char in the partial word
     * @param composeY Optional Y coordinates per char in the partial word
     * @return [LMResult] with all predictions, scores, and confidence mode
     */
    fun getAllPredictions(
        textBeforeCursor: String,
        autocorrectThreshold: Float = 1.5f,
        bannedWords: Array<String> = emptyArray(),
        composeX: IntArray? = null,
        composeY: IntArray? = null,
        inputMode: Int = 0,
        previousWords: List<String> = emptyList(),
    ): LMResult {
        if (!isReady()) return LMResult(emptyList(), "clueless")

        val trimmed = textBeforeCursor.takeLast(100)

        val words = trimmed.trim().split(Regex("\\s+"))
        val partialWord = if (words.isNotEmpty() && !trimmed.endsWith(" ")) {
            words.lastOrNull() ?: ""
        } else {
            ""
        }
        var ctx = if (partialWord.isNotEmpty() && trimmed.endsWith(partialWord)) {
            trimmed.dropLast(partialWord.length).trim()
        } else {
            trimmed.trim()
        }
        if (previousWords.isNotEmpty()) {
            ctx = "${previousWords.joinToString(" ")} $ctx"
        }

        return try {
            languageModel.generateAll(
                context = ctx,
                partialWord = partialWord,
                composeX = composeX,
                composeY = composeY,
                autocorrectThreshold = autocorrectThreshold,
                bannedWords = bannedWords,
                inputMode = inputMode,
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native error during prediction", e)
            LMResult(emptyList(), "clueless")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting predictions", e)
            LMResult(emptyList(), "clueless")
        }
    }

    // ── Re-scoring ──────────────────────────────────────────────────

    /**
     * Rescore a list of candidate words against the LLM's understanding
     * of the current context. Returns new scores, or null on failure.
     */
    fun rescoreSuggestions(
        textBeforeCursor: String,
        candidates: List<String>,
        initialScores: List<Int>,
    ): IntArray? {
        if (!isReady()) return null
        if (candidates.isEmpty()) return null

        val trimmed = textBeforeCursor.takeLast(100)

        // Context = everything before the last word
        val words = trimmed.trim().split(Regex("\\s+"))
        val partialWord = if (words.isNotEmpty() && !trimmed.endsWith(" ")) words.last() else ""
        val ctx = if (partialWord.isNotEmpty() && trimmed.endsWith(partialWord)) {
            trimmed.dropLast(partialWord.length).trim()
        } else {
            trimmed.trim()
        }

        return try {
            languageModel.rescore(ctx, candidates, initialScores)
        } catch (e: Exception) {
            Log.e(TAG, "Error rescoring", e)
            null
        }
    }

    fun setProximityInfoHandle(handle: Long) {
        if (::languageModel.isInitialized) {
            languageModel.proximityInfoHandle = handle
        }
    }

    fun isReady(): Boolean {
        return isInitialized.get() && !isInitializing.get() &&
            ::languageModel.isInitialized && languageModel.isLoaded()
    }

    fun close() {
        if (isInitialized.getAndSet(false)) {
            try {
                if (::languageModel.isInitialized) {
                    languageModel.close()
                    Log.d(TAG, "PredictionBridge closed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing PredictionBridge", e)
            }
        }
        isInitializing.set(false)
    }
}
