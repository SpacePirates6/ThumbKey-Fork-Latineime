package com.dessalines.thumbkey.prediction

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

class BinaryDictionaryBridge(private val context: Context) {

    private val wordToFreq = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false

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
            loaded = true
        }
    }

    fun isLoaded(): Boolean = loaded

    fun isValidWord(word: String): Boolean =
        wordToFreq.containsKey(word.lowercase())

    fun getWordFrequency(word: String): Int =
        wordToFreq[word.lowercase()] ?: 0

    fun getCompletions(prefix: String, limit: Int): List<Pair<String, Int>> {
        val p = prefix.lowercase()
        return wordToFreq.entries
            .asSequence()
            .filter { it.key.startsWith(p) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
            .toList()
    }
}
