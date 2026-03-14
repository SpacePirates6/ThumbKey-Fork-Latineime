package org.futo.inputmethod.latin.xlm

import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import java.io.File

/**
 * Dedicated single-thread context for all native model operations.
 * The native llama code is NOT thread-safe, so all calls must be serialized.
 */
@OptIn(DelicateCoroutinesApi::class)
val LanguageModelScope = newSingleThreadContext("LanguageModel")

/**
 * Result from the LLM containing all predictions with scores and a confidence mode.
 */
data class LMResult(
    /** All valid predictions with their probability scores, sorted by score descending. */
    val predictions: List<Pair<String, Float>>,
    /** Confidence mode: "autocorrect", "uncertain", or "clueless". */
    val mode: String,
    val autoCommitConfidence: Float = 0f,
)

/**
 * Kotlin bridge to the native LanguageModel C++ implementation.
 * This class matches the JNI interface defined in org_futo_inputmethod_latin_xlm_LanguageModel.cpp
 */
class LanguageModel private constructor() {

    companion object {
        private const val TAG = "LanguageModel"

        @Volatile
        private var instance: LanguageModel? = null

        @Volatile
        private var nativeLibraryLoaded = false

        @JvmStatic
        fun getInstance(): LanguageModel {
            return instance ?: synchronized(this) {
                instance ?: LanguageModel().also { instance = it }
            }
        }

        /**
         * Load the native library ONCE on the main thread.
         * CRITICAL: This MUST be called on the Main thread before any native operations.
         */
        @JvmStatic
        fun loadNativeLibrary() {
            if (nativeLibraryLoaded) return

            synchronized(this) {
                if (nativeLibraryLoaded) return

                try {
                    System.loadLibrary("jni_latinime")
                    nativeLibraryLoaded = true
                    Log.i(TAG, "Native library jni_latinime loaded successfully")
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Could not load jni_latinime library", e)
                    throw e
                }
            }
        }
    }

    private val nativeLock = Any()
    private var refCount = 0
    private var modelPtr: Long = 0L
    private var isLoaded = false
    var proximityInfoHandle: Long = 0L

    // Native method declarations
    private external fun openNative(modelPath: String): Long
    private external fun closeNative(ptr: Long)

    private external fun getSuggestionsNative(
        ptr: Long,
        proximityInfoHandle: Long,
        context: String?,
        partialWord: String?,
        inputMode: Int,
        inComposeX: IntArray?,
        inComposeY: IntArray?,
        autocorrectThreshold: Float,
        bannedWords: Array<String>?,
        outPredictions: Array<String?>,
        outProbabilities: FloatArray,
    )

    private external fun rescoreSuggestionsNative(
        ptr: Long,
        context: String?,
        inWords: Array<String>,
        inScores: IntArray,
        outScores: IntArray,
    )

    // ── Loading / closing ────────────────────────────────────────────

    fun load(modelPath: String, cacheDir: String, useGpu: Boolean): Boolean {
        synchronized(nativeLock) {
            if (isLoaded) {
                Log.d(TAG, "Model already loaded, incrementing ref count (${refCount + 1})")
                refCount++
                return true
            }

            try {
                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    Log.e(TAG, "Model file does not exist: $modelPath")
                    return false
                }

                modelPtr = openNative(modelPath)

                if (modelPtr != 0L) {
                    isLoaded = true
                    refCount = 1
                    Log.i(TAG, "Language model loaded successfully from $modelPath")
                    return true
                } else {
                    Log.e(TAG, "Failed to open model (returned null pointer)")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading language model", e)
                return false
            }
        }
    }

    // ── Context / partial word safeguarding ───────────────────────────

    private var prevSafeguardContextResult = ""

    private fun safeguardContext(ctx: String): String {
        var context = ctx.trim()

        val matchStart = findLongestMatch(prevSafeguardContextResult, context)
        if (matchStart != -1) {
            context = context.substring(matchStart)
        }

        val stillNeedTrimming = { c: String -> c.length > 70 || c.count { it == ' ' } > 16 }
        if (stillNeedTrimming(context)) {
            val v = context.indexOfLast { it == '.' || it == '?' || it == '!' }
            if (v != -1) {
                context = context.substring(v + 1).trim()
            }
        }
        while (stillNeedTrimming(context) && context.contains(",")) {
            context = context.substring(context.indexOf(",") + 1).trim()
        }
        if (stillNeedTrimming(context) && context.contains(" ")) {
            context = context.split(' ').takeLast(5).joinToString(separator = " ")
        }
        if (context.length > 144) {
            context = ""
        }

        prevSafeguardContextResult = context
        return context
    }

    private fun findLongestMatch(needle: String, haystack: String): Int {
        if (needle.isEmpty()) return -1
        var result = -1 to 0
        var ni = 0
        for (i in 0 until haystack.length + 1) {
            if (ni < needle.length && i < haystack.length && haystack[i] == needle[ni]) {
                ni++
            } else {
                if (ni >= result.second && ni > 0) {
                    result = i - ni to ni
                }
                ni = 0
            }
        }
        return result.first
    }

    private fun safeguardPartialWord(partialWord: String): String {
        var word = partialWord.trim()
        if (word.length > 40) {
            word = word.substring(0, 40)
        }
        return word
    }

    // ── Generate: return first prediction (backwards compat) ─────────

    fun generate(context: String, partialWord: String = ""): String {
        val result = generateAll(context, partialWord)
        return result.predictions.firstOrNull()?.first ?: ""
    }

    // ── GenerateAll: return ALL predictions with scores + mode ────────

    /**
     * Generate ALL predictions for the given context and partial word.
     *
     * @param context Text before cursor (excluding the partial word)
     * @param partialWord Current word being typed (can be empty for next-word prediction)
     * @param composeX Optional X coordinates for each character in partialWord
     * @param composeY Optional Y coordinates for each character in partialWord
     * @param autocorrectThreshold Threshold for autocorrect confidence (default 1.5)
     * @param bannedWords Words to exclude from suggestions
     * @return [LMResult] containing all predictions with scores and confidence mode
     */
    fun generateAll(
        context: String,
        partialWord: String = "",
        composeX: IntArray? = null,
        composeY: IntArray? = null,
        autocorrectThreshold: Float = 1.5f,
        bannedWords: Array<String> = emptyArray(),
        inputMode: Int = 0,
    ): LMResult {
        synchronized(nativeLock) {
            if (!isLoaded || modelPtr == 0L) {
                Log.w(TAG, "Model not loaded")
                return LMResult(emptyList(), "clueless")
            }

            try {
                val safeguardedContext = safeguardContext(context)
                val safeguardedPartialWord = safeguardPartialWord(partialWord)

                val maxPredictions = 128
                val outPredictions = arrayOfNulls<String>(maxPredictions)
                val outProbabilities = FloatArray(maxPredictions)

                // Build coordinate arrays
                val wordLen = safeguardedPartialWord.length
                val cx = composeX ?: IntArray(wordLen) { -1 }
                val cy = composeY ?: IntArray(wordLen) { -1 }

                // Ensure coordinate arrays match partial word length
                val finalX = if (cx.size == wordLen) cx else IntArray(wordLen) { if (it < cx.size) cx[it] else -1 }
                val finalY = if (cy.size == wordLen) cy else IntArray(wordLen) { if (it < cy.size) cy[it] else -1 }

                getSuggestionsNative(
                    ptr = modelPtr,
                    proximityInfoHandle = proximityInfoHandle,
                    context = safeguardedContext,
                    partialWord = if (safeguardedPartialWord.isNotEmpty()) safeguardedPartialWord else null,
                    inputMode = inputMode,
                    inComposeX = finalX,
                    inComposeY = finalY,
                    autocorrectThreshold = autocorrectThreshold,
                    bannedWords = bannedWords,
                    outPredictions = outPredictions,
                    outProbabilities = outProbabilities,
                )

                // The C++ code puts a mode indicator in the LAST element:
                //   "autocorrect" — high confidence, safe to auto-replace
                //   "uncertain"   — moderate confidence, show as suggestion
                //   "clueless"    — low confidence
                val mode = outPredictions.lastOrNull()?.takeIf {
                    it == "autocorrect" || it == "uncertain" || it == "clueless"
                } ?: "clueless"

                // Collect all valid predictions (skip nulls, blanks, and the mode string)
                val predictions = mutableListOf<Pair<String, Float>>()
                for (i in 0 until outPredictions.size - 1) {
                    val prediction = outPredictions[i]
                    if (prediction != null && prediction.isNotEmpty() &&
                        prediction != "autocorrect" &&
                        prediction != "uncertain" &&
                        prediction != "clueless"
                    ) {
                        predictions.add(prediction.trim() to outProbabilities[i])
                    }
                }

                // Sort by probability descending
                predictions.sortByDescending { it.second }

                val autoCommitConfidence = when {
                    predictions.isEmpty() -> 0f
                    predictions.size == 1 -> predictions[0].second
                    else -> (predictions[0].second - predictions[1].second).coerceAtLeast(0f)
                }
                return LMResult(predictions, mode, autoCommitConfidence)
            } catch (e: Exception) {
                Log.e(TAG, "Error generating predictions", e)
                return LMResult(emptyList(), "clueless")
            }
        }
    }

    // ── Rescore: re-rank suggestions using LLM context ───────────────

    /**
     * Rescore a list of word suggestions using the LLM's understanding of context.
     * This takes existing suggestions (e.g., from spell checker or proximity model)
     * and assigns new scores based on how well they fit the context.
     *
     * @param context Text before the current word
     * @param words List of candidate words to rescore
     * @param scores Initial scores for each word (same length as words)
     * @return New scores for each word, or null if model is not available
     */
    fun rescore(context: String, words: List<String>, scores: List<Int>): IntArray? {
        synchronized(nativeLock) {
            if (!isLoaded || modelPtr == 0L) return null
            if (words.isEmpty()) return null

            try {
                val safeguardedContext = safeguardContext(context)
                val inWords = words.toTypedArray()
                val inScores = scores.toIntArray()
                val outScores = IntArray(words.size)

                rescoreSuggestionsNative(
                    ptr = modelPtr,
                    context = safeguardedContext,
                    inWords = inWords,
                    inScores = inScores,
                    outScores = outScores,
                )

                return outScores
            } catch (e: Exception) {
                Log.e(TAG, "Error rescoring suggestions", e)
                return null
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    fun close() {
        synchronized(nativeLock) {
            if (refCount > 0) {
                refCount--
                Log.d(TAG, "Language model ref released (remaining: $refCount)")
            }
            if (refCount <= 0 && isLoaded && modelPtr != 0L) {
                try {
                    closeNative(modelPtr)
                    modelPtr = 0L
                    isLoaded = false
                    refCount = 0
                    Log.i(TAG, "Language model closed (last reference released)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing model", e)
                }
            }
        }
    }

    fun forceClose() {
        synchronized(nativeLock) {
            if (isLoaded && modelPtr != 0L) {
                try {
                    closeNative(modelPtr)
                    modelPtr = 0L
                    isLoaded = false
                    refCount = 0
                    Log.i(TAG, "Language model force-closed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error force-closing model", e)
                }
            }
        }
    }

    fun isLoaded(): Boolean = synchronized(nativeLock) { isLoaded }
}
