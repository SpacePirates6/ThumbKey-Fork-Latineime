package org.futo.inputmethod.latin.xlm

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

/**
 * Dedicated single-thread context for adapter training.
 * Training is CPU-intensive and must not run on the LLM inference thread.
 */
@OptIn(DelicateCoroutinesApi::class)
val TrainingContext = newSingleThreadContext("AdapterTrainingContext")

/**
 * JNI-bound adapter (LoRA) trainer for the language model.
 *
 * IMPORTANT: This class MUST live in `org.futo.inputmethod.latin.xlm` because the
 * native JNI registration in `org_futo_inputmethod_latin_xlm_AdapterTrainer.cpp`
 * looks up native methods via `org/futo/inputmethod/latin/xlm/AdapterTrainer`.
 *
 * Higher-level training logic lives in
 * `com.dessalines.thumbkey.prediction.AdapterTrainerHelper`.
 */
class AdapterTrainer(
    baseModelPath: String,
    checkpointCachePath: String,
    outputModelPath: String,
    weight: Float,
    examples: List<String>,
    val lossFlow: MutableSharedFlow<Float>?,
    val progressFlow: MutableSharedFlow<Float>?,
) {
    // ── Native methods (match JNI signatures exactly) ────────────────
    private external fun openNative(
        baseModelPath: String,
        loraCachePath: String,
        outputModelPath: String,
        weight: Float,
    ): Long

    private external fun closeNative(handle: Long)
    private external fun addExample(handle: Long, example: String)
    private external fun train(handle: Long)

    private var handle: Long = 0L
    private fun isHandleValid() = handle != 0L

    /** Called from native code to report training progress. */
    @Suppress("unused")
    private fun emitProgress(progress: Float) {
        progressFlow?.tryEmit(progress)
    }

    /** Called from native code to report training loss. */
    @Suppress("unused")
    private fun emitLoss(loss: Float) {
        lossFlow?.tryEmit(loss)
    }

    init {
        LanguageModel.loadNativeLibrary()

        handle = openNative(baseModelPath, checkpointCachePath, outputModelPath, weight)
        if (!isHandleValid()) {
            throw IllegalArgumentException("Failed to initialize AdapterTrainer")
        }

        var numAdded = 0
        examples.forEach {
            if (it.isNotBlank()) {
                addExample(handle, it.trim() + " ")
                numAdded++
            }
        }

        if (numAdded == 0) {
            closeNative(handle)
            throw InadequateDataException()
        }
    }

    fun close() {
        if (isHandleValid()) {
            closeNative(handle)
            handle = 0
        }
    }

    suspend fun train() = withContext(TrainingContext) {
        if (!isHandleValid()) throw IllegalStateException("Training with null handle")
        train(handle)
    }
}

/** Thrown when there's insufficient training data. */
class InadequateDataException : Exception("Inadequate Training Data")
