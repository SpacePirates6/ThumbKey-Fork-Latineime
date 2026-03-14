package com.dessalines.thumbkey.prediction

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.Locale

class PersonalizationTrainer(
    private val context: Context,
    private val trainingLog: TrainingLog,
) {
    companion object {
        private const val TAG = "PersonalizationTrainer"
        private const val MIN_EXAMPLES_TO_TRAIN = 100
        private const val STUB_FILE = "personalization_examples.json"
    }

    @Volatile
    private var nativeAvailable: Boolean? = null

    @Volatile
    private var trainingInProgress = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun addTrainingExample(priorContext: String, word: String) {
        if (word.isBlank()) return
        val trimmedContext = priorContext.takeLast(100).trim()
        val locale = Locale.getDefault().language
        trainingLog.addEntry(
            originalWord = null,
            committedWord = word,
            priorContext = trimmedContext,
            importance = 2,
            locale = locale,
        )
    }

    fun shouldTrain(): Boolean {
        if (nativeAvailable == false) return false
        return trainingLog.size >= MIN_EXAMPLES_TO_TRAIN
    }

    fun trainAsync(onComplete: (Boolean) -> Unit) {
        if (nativeAvailable == false) {
            Log.w(TAG, "LoRA training not available in this build")
            onComplete(false)
            return
        }
        if (trainingInProgress) {
            onComplete(false)
            return
        }
        val examples = trainingLog.getTrainingExamples()
        if (examples.size < 10) {
            Log.w(TAG, "Not enough training data (${examples.size} examples)")
            onComplete(false)
            return
        }

        trainingInProgress = true
        scope.launch {
            try {
                val success = AdapterTrainerHelper.trainFromLog(
                    context = context,
                    trainingLog = trainingLog,
                    progressFlow = null,
                    lossFlow = null,
                )
                nativeAvailable = true
                onComplete(success)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "LoRA training not available in this build")
                nativeAvailable = false
                saveExamplesToStubFile(examples)
                onComplete(false)
            } catch (e: NoSuchMethodError) {
                Log.w(TAG, "LoRA training not available in this build")
                nativeAvailable = false
                saveExamplesToStubFile(examples)
                onComplete(false)
            } catch (e: Exception) {
                Log.e(TAG, "Training failed", e)
                nativeAvailable = true
                onComplete(false)
            } finally {
                trainingInProgress = false
            }
        }
    }

    fun getExampleCount(): Int = trainingLog.size

    fun isTraining(): Boolean = trainingInProgress

    private fun saveExamplesToStubFile(examples: List<String>) {
        try {
            val jsonArray = JSONArray()
            examples.forEach { jsonArray.put(it) }
            val file = File(context.cacheDir, STUB_FILE)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save examples to stub file", e)
        }
    }
}
