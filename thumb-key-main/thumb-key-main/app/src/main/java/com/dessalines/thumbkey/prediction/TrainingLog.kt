package com.dessalines.thumbkey.prediction

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Logs user typing history for on-device model fine-tuning (adapter/LoRA training).
 *
 * Each entry captures:
 *  - The prior text context (for the LLM to learn from)
 *  - The word the user actually committed
 *  - Whether it was an autocorrect or manual choice
 *  - The misspelled word (if applicable)
 *
 * The log is persisted as JSON in the app's cache directory.
 *
 * Adapted from FutoBoard's TrainingDataLog.
 */
class TrainingLog(private val context: Context) {

    companion object {
        private const val TAG = "TrainingLog"
        private const val LOG_FILE = "training_history.json"
        private const val MAX_ENTRIES = 5000
    }

    data class Entry(
        /** Key for de-duplication (context + word). */
        val key: String,
        /** Text before the word (sentence context). */
        val priorContext: String,
        /** The n-gram context (last few words). */
        val ngramContext: String,
        /** The misspelled word (null if user chose a prediction). */
        val misspelledWord: String?,
        /** The word actually committed. */
        val committedWord: String,
        /** 0 = autocorrected, 1 = manually selected, 2+ = less confident. */
        val importance: Int,
        /** Locale code (e.g., "en"). */
        val locale: String,
        /** Timestamp in seconds. */
        val timestamp: Long,
    )

    private val entries = mutableListOf<Entry>()
    private val lock = Any()

    init {
        loadFromDisk()
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Record that the user committed a word (either via autocorrect or by tapping a suggestion).
     *
     * @param originalWord The word as typed before correction (null if prediction, not correction)
     * @param committedWord The final word that was committed
     * @param priorContext Text before the word (max ~100 chars)
     * @param importance 0 = autocorrected, 1 = manually selected suggestion
     * @param locale The current locale code
     */
    fun addEntry(
        originalWord: String?,
        committedWord: String,
        priorContext: String,
        importance: Int,
        locale: String = "en",
    ) {
        val trimmedContext = priorContext.takeLast(100).trim()
        val ngramCtx = trimmedContext.split(" ").takeLast(3).joinToString(" ")
        val key = "$ngramCtx $committedWord".trim()

        val entry = Entry(
            key = key,
            priorContext = trimmedContext,
            ngramContext = ngramCtx,
            misspelledWord = originalWord?.takeIf { it != committedWord },
            committedWord = committedWord,
            importance = importance,
            locale = locale,
            timestamp = System.currentTimeMillis() / 1000,
        )

        synchronized(lock) {
            entries.add(entry)
            // Keep the log from growing unbounded
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
        }

        Log.d(TAG, "Logged: ${if (entry.misspelledWord != null) "${entry.misspelledWord} → " else ""}${entry.committedWord}")
    }

    /**
     * Remove a word from training history (e.g., user undid an autocorrect).
     */
    fun unlearnWord(committedWord: String, ngramContext: String) {
        val keyToSearch = "$ngramContext $committedWord".trim()
        synchronized(lock) {
            val idx = entries.indexOfLast { it.key.startsWith(keyToSearch) || it.key == keyToSearch }
            if (idx != -1) {
                entries.removeAt(idx)
                Log.d(TAG, "Unlearned: $committedWord")
            }
        }
    }

    /**
     * Get all training examples as strings suitable for adapter training.
     * Each example is a context + committed word pair.
     */
    fun getTrainingExamples(): List<String> {
        return synchronized(lock) {
            entries.map { entry ->
                val ctx = entry.priorContext.ifBlank { entry.ngramContext }
                "$ctx ${entry.committedWord} "
            }
        }
    }

    /**
     * Get entries for a specific locale.
     */
    fun getEntriesForLocale(locale: String): List<Entry> {
        return synchronized(lock) {
            entries.filter { it.locale == locale }.toList()
        }
    }

    /** Total number of entries. */
    val size: Int get() = synchronized(lock) { entries.size }

    /** Clear all training data. */
    fun clear() {
        synchronized(lock) { entries.clear() }
        saveToDisk()
        Log.d(TAG, "Training log cleared")
    }

    // ── Persistence ─────────────────────────────────────────────────

    fun saveToDisk() {
        try {
            val jsonArray = JSONArray()
            synchronized(lock) {
                for (entry in entries) {
                    val obj = JSONObject()
                    obj.put("key", entry.key)
                    obj.put("priorContext", entry.priorContext)
                    obj.put("ngramContext", entry.ngramContext)
                    obj.put("misspelledWord", entry.misspelledWord ?: JSONObject.NULL)
                    obj.put("committedWord", entry.committedWord)
                    obj.put("importance", entry.importance)
                    obj.put("locale", entry.locale)
                    obj.put("timestamp", entry.timestamp)
                    jsonArray.put(obj)
                }
            }
            val file = File(context.cacheDir, LOG_FILE)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save training log", e)
        }
    }

    private fun loadFromDisk() {
        try {
            val file = File(context.cacheDir, LOG_FILE)
            if (!file.exists()) return

            val json = file.readText()
            val jsonArray = JSONArray(json)

            synchronized(lock) {
                entries.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    entries.add(
                        Entry(
                            key = obj.getString("key"),
                            priorContext = obj.getString("priorContext"),
                            ngramContext = obj.getString("ngramContext"),
                            misspelledWord = if (obj.isNull("misspelledWord")) null else obj.getString("misspelledWord"),
                            committedWord = obj.getString("committedWord"),
                            importance = obj.getInt("importance"),
                            locale = obj.optString("locale", "en"),
                            timestamp = obj.getLong("timestamp"),
                        ),
                    )
                }
            }
            Log.d(TAG, "Loaded ${entries.size} training entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load training log", e)
        }
    }
}
