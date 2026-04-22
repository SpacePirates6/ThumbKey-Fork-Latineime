package com.dessalines.thumbkey.prediction

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

class BinaryDictionaryBridge(private val context: Context) {

    private val wordToFreq = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false

    // 2-char prefix → list of (word, freq) sorted desc by freq.
    // Looking up by prefix becomes O(list size) instead of O(|dict|).
    private val prefixIndex: MutableMap<String, List<Pair<String, Int>>> = HashMap()

    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            context.assets.open("dictionaries/en_wordlist.tsv").use { input ->
                input.bufferedReader().forEachLine { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 2) {
                        val word = parts[0].trim().lowercase()
                        val freq = parts[1].trim().toIntOrNull() ?: 0
                        if (word.isNotEmpty()) {
                            wordToFreq[word] = freq
                        }
                    }
                }
            }
            buildPrefixIndex()
            loaded = true
        }
    }

    private fun buildPrefixIndex() {
        val buckets = HashMap<String, MutableList<Pair<String, Int>>>()
        for ((word, freq) in wordToFreq) {
            if (word.length < 2) continue
            val key = word.substring(0, 2)
            buckets.getOrPut(key) { ArrayList() }.add(word to freq)
        }
        for ((key, list) in buckets) {
            list.sortByDescending { it.second }
            prefixIndex[key] = list
        }
    }

    fun isLoaded(): Boolean = loaded

    fun isValidWord(word: String): Boolean =
        wordToFreq.containsKey(word.lowercase())

    fun getWordFrequency(word: String): Int =
        wordToFreq[word.lowercase()] ?: 0

    fun getCompletions(prefix: String, limit: Int): List<Pair<String, Int>> {
        val p = prefix.lowercase()
        if (p.length < 2) {
            return wordToFreq.entries
                .asSequence()
                .filter { it.key.startsWith(p) }
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key to it.value }
                .toList()
        }
        val bucket = prefixIndex[p.substring(0, 2)] ?: return emptyList()
        val results = ArrayList<Pair<String, Int>>(limit)
        for ((word, freq) in bucket) {
            if (word.startsWith(p)) {
                results.add(word to freq)
                if (results.size >= limit) break
            }
        }
        return results
    }
}
