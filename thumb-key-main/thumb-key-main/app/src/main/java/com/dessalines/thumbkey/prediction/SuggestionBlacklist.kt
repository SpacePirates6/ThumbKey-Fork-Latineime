package com.dessalines.thumbkey.prediction

import android.content.Context
import android.util.Log

/**
 * Manages a user-configurable blacklist of words that should never
 * appear as suggestions or be autocorrected to.
 *
 * The blacklist is persisted in SharedPreferences as a comma-separated string.
 *
 * Adapted from FutoBoard's SuggestionBlacklist.
 */
class SuggestionBlacklist(context: Context) {

    companion object {
        private const val TAG = "SuggestionBlacklist"
        private const val PREFS_NAME = "suggestion_blacklist"
        private const val KEY_BLACKLIST = "blacklisted_words"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Current set of blacklisted words (lowercase). Thread-safe via synchronized. */
    private val blacklist = mutableSetOf<String>()

    init {
        loadFromPrefs()
    }

    // ── Public API ──────────────────────────────────────────────────

    /** Get a copy of the current blacklist. */
    val currentBlacklist: Set<String> get() = synchronized(blacklist) { blacklist.toSet() }

    /** Get as an array (for passing to native bannedWords parameter). */
    val bannedWordsArray: Array<String> get() = synchronized(blacklist) { blacklist.toTypedArray() }

    /** Check if a suggestion is OK (not blacklisted). */
    fun isSuggestionOk(word: String): Boolean {
        return synchronized(blacklist) { word.lowercase() !in blacklist }
    }

    /** Filter a list of suggestions, removing blacklisted words. */
    fun filterSuggestions(suggestions: List<String>): List<String> {
        return synchronized(blacklist) {
            suggestions.filter { it.lowercase() !in blacklist }
        }
    }

    /** Add a word to the blacklist. */
    fun addWord(word: String) {
        val lc = word.lowercase().trim()
        if (lc.isBlank()) return
        synchronized(blacklist) {
            blacklist.add(lc)
        }
        saveToPrefs()
        Log.d(TAG, "Blacklisted: $lc")
    }

    /** Remove a word from the blacklist. */
    fun removeWord(word: String) {
        val lc = word.lowercase().trim()
        synchronized(blacklist) {
            blacklist.remove(lc)
        }
        saveToPrefs()
        Log.d(TAG, "Un-blacklisted: $lc")
    }

    /** Clear the entire blacklist. */
    fun clear() {
        synchronized(blacklist) { blacklist.clear() }
        saveToPrefs()
        Log.d(TAG, "Blacklist cleared")
    }

    // ── Persistence ─────────────────────────────────────────────────

    private fun loadFromPrefs() {
        val raw = prefs.getString(KEY_BLACKLIST, "") ?: ""
        val words = raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        synchronized(blacklist) {
            blacklist.clear()
            blacklist.addAll(words)
        }
        Log.d(TAG, "Loaded ${blacklist.size} blacklisted words")
    }

    private fun saveToPrefs() {
        val csv = synchronized(blacklist) { blacklist.joinToString(",") }
        prefs.edit().putString(KEY_BLACKLIST, csv).apply()
    }
}
