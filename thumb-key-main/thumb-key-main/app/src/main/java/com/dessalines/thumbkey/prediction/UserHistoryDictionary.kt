package com.dessalines.thumbkey.prediction

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class UserHistoryDictionary(context: Context) {

    companion object {
        private const val TAG = "UserHistoryDict"
        private const val FILE_NAME = "user_history_dict.json"
        private const val MAX_ENTRIES = 10000
        private const val BIGRAM_BOOST_SCALE = 5
        private const val HALF_LIFE_MS = 604_800_000L
        private const val MIN_EFFECTIVE_FREQUENCY = 0.1f
        private const val SAVE_INTERVAL_MS = 60_000L
    }

    data class HistoryEntry(
        val word: String,
        var frequency: Int,
        var lastUsed: Long,
        val bigrams: MutableMap<String, Int>,
    )

    private val context = context.applicationContext
    private val entries = mutableMapOf<String, HistoryEntry>()
    private val lock = Any()
    @Volatile private var dirty = false
    private var lastSaveTime = 0L
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun decay(ageMs: Long): Float = 1f / (1f + ageMs.toFloat() / HALF_LIFE_MS)

    private fun effectiveFrequencyOf(entry: HistoryEntry): Float {
        val ageMs = System.currentTimeMillis() - entry.lastUsed
        return entry.frequency * decay(ageMs)
    }

    init {
        loadFromDisk()
    }

    private fun scheduleSaveIfNeeded() {
        if (!dirty) return
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < SAVE_INTERVAL_MS) return
        dirty = false
        lastSaveTime = now
        saveExecutor.submit {
            try { saveToDisk() } catch (e: Exception) {
                Log.e(TAG, "Scheduled save failed", e)
            }
        }
    }

    fun recordWord(word: String, previousWord: String?) {
        val key = word.lowercase().trim()
        if (key.isEmpty()) return

        synchronized(lock) {
            val now = System.currentTimeMillis()
            val entry = entries[key]

            if (entry != null) {
                entry.frequency++
                entry.lastUsed = now
                if (previousWord != null) {
                    val prev = previousWord.lowercase().trim()
                    if (prev.isNotEmpty()) {
                        entry.bigrams[prev] = (entry.bigrams[prev] ?: 0) + 1
                    }
                }
            } else {
                maybeEvictLru()
                val bigrams = mutableMapOf<String, Int>()
                if (previousWord != null) {
                    val prev = previousWord.lowercase().trim()
                    if (prev.isNotEmpty()) {
                        bigrams[prev] = 1
                    }
                }
                entries[key] = HistoryEntry(
                    word = key,
                    frequency = 1,
                    lastUsed = now,
                    bigrams = bigrams,
                )
            }
            dirty = true
        }
        scheduleSaveIfNeeded()
    }

    fun unlearn(word: String) {
        val key = word.lowercase().trim()
        synchronized(lock) {
            entries.remove(key)
        }
    }

    fun getFrequency(word: String): Int {
        val key = word.lowercase().trim()
        synchronized(lock) {
            return entries[key]?.frequency ?: 0
        }
    }

    fun getEffectiveFrequency(word: String): Float {
        val key = word.lowercase().trim()
        synchronized(lock) {
            val entry = entries[key] ?: return 0f
            return effectiveFrequencyOf(entry)
        }
    }

    fun getCompletions(prefix: String, limit: Int = 10): List<Pair<String, Int>> {
        val p = prefix.lowercase().trim()
        synchronized(lock) {
            return entries.values
                .filter { it.word.startsWith(p) }
                .map { it to effectiveFrequencyOf(it) }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first.word to it.second.toInt() }
        }
    }

    fun getBigramBoost(previousWord: String, candidate: String): Float {
        val prev = previousWord.lowercase().trim()
        val cand = candidate.lowercase().trim()
        if (prev.isEmpty() || cand.isEmpty()) return 0f

        synchronized(lock) {
            val entry = entries[cand] ?: return 0f
            val count = entry.bigrams[prev] ?: return 0f
            val decayFactor = decay(System.currentTimeMillis() - entry.lastUsed)
            return (count.toFloat() / BIGRAM_BOOST_SCALE * decayFactor).coerceIn(0f, 1f)
        }
    }

    fun saveToDisk() {
        synchronized(lock) {
            try {
                val arr = JSONArray()
                for ((_, entry) in entries) {
                    val obj = JSONObject().apply {
                        put("word", entry.word)
                        put("frequency", entry.frequency)
                        put("lastUsed", entry.lastUsed)
                        val bigramArr = JSONArray()
                        for ((prev, count) in entry.bigrams) {
                            bigramArr.put(JSONObject().apply {
                                put("prev", prev)
                                put("count", count)
                            })
                        }
                        put("bigrams", bigramArr)
                    }
                    arr.put(obj)
                }
                File(context.cacheDir, FILE_NAME).writeText(arr.toString())
                Log.d(TAG, "Saved ${entries.size} entries")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save", e)
            }
        }
    }

    fun loadFromDisk() {
        synchronized(lock) {
            try {
                val file = File(context.cacheDir, FILE_NAME)
                if (!file.exists()) return

                val arr = JSONArray(file.readText())
                entries.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val word = obj.getString("word")
                    val frequency = obj.getInt("frequency")
                    val lastUsed = obj.getLong("lastUsed")
                    val bigrams = mutableMapOf<String, Int>()
                    val bigramArr = obj.getJSONArray("bigrams")
                    for (j in 0 until bigramArr.length()) {
                        val b = bigramArr.getJSONObject(j)
                        bigrams[b.getString("prev")] = b.getInt("count")
                    }
                    entries[word] = HistoryEntry(word, frequency, lastUsed, bigrams)
                }
                Log.d(TAG, "Loaded ${entries.size} entries")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load", e)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    val size: Int
        get() = synchronized(lock) { entries.size }

    private fun maybeEvictLru() {
        entries.filter { effectiveFrequencyOf(it.value) < MIN_EFFECTIVE_FREQUENCY }.keys.toList()
            .forEach { entries.remove(it) }
        if (entries.size >= MAX_ENTRIES) {
            val oldest = entries.values.minByOrNull { it.lastUsed } ?: return
            entries.remove(oldest.word)
        }
    }
}
