package com.dessalines.thumbkey.prediction

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.xlm.AdapterTrainer
import org.futo.inputmethod.latin.xlm.InadequateDataException
import java.io.File

/**
 * Higher-level helper for running adapter training from the ThumbKey training log.
 *
 * The actual JNI-bound [AdapterTrainer] class lives in
 * `org.futo.inputmethod.latin.xlm` to match the native JNI registration path.
 */
object AdapterTrainerHelper {

    private const val TAG = "AdapterTrainer"

    /**
     * Run a full training cycle:
     *  1. Collect training examples from the log (filtered by locale, augmented with
     *     synthetic misspelling variants via TrainingDataGenerator)
     *  2. Create an adapter trainer
     *  3. Train the adapter
     *  4. Save the fine-tuned model
     *
     * @param locale If non-null, only train on entries matching this locale code.
     *               Defaults to the current device locale.
     * @return true if training completed successfully
     */
    suspend fun trainFromLog(
        context: Context,
        trainingLog: TrainingLog,
        progressFlow: MutableSharedFlow<Float>? = null,
        lossFlow: MutableSharedFlow<Float>? = null,
        locale: String? = java.util.Locale.getDefault().language,
    ): Boolean = withContext(Dispatchers.Default) {
        val examples = trainingLog.getAugmentedTrainingExamples(locale)
        if (examples.size < 10) {
            val rawCount = trainingLog.getTrainingExamples(locale).size
            Log.w(TAG, "Not enough training data for locale=$locale " +
                "(${rawCount} raw entries, ${examples.size} augmented, need ≥10)")
            return@withContext false
        }

        Log.i(TAG, "Training with ${examples.size} augmented examples (locale=$locale)")

        val modelDir = ModelPaths.getModelDirectory(context)
        val baseModel = if (locale != null) {
            ModelPaths.getModelForLanguage(context, locale)
                ?: ModelPaths.getDefaultModelPath(context)
        } else {
            ModelPaths.getDefaultModelPath(context)
        } ?: return@withContext false

        val checkpointDir = File(context.cacheDir, "adapter_checkpoint")
        if (!checkpointDir.exists()) checkpointDir.mkdirs()

        val outputModel = File(modelDir, "finetuned_${baseModel.name}")

        var trainer: AdapterTrainer? = null
        try {
            trainer = AdapterTrainer(
                baseModelPath = baseModel.absolutePath,
                checkpointCachePath = checkpointDir.absolutePath,
                outputModelPath = outputModel.absolutePath,
                weight = 1.0f,
                examples = examples,
                lossFlow = lossFlow,
                progressFlow = progressFlow,
            )

            trainer.train()

            Log.i(TAG, "Training completed. Output: ${outputModel.absolutePath}")

            ModelPaths.modelOptionsUpdated.tryEmit(Unit)

            true
        } catch (e: InadequateDataException) {
            Log.w(TAG, "Inadequate training data", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Training failed", e)
            false
        } finally {
            trainer?.close()
        }
    }
}
