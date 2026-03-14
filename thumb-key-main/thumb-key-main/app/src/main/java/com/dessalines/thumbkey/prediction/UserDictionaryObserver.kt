package com.dessalines.thumbkey.prediction

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.UserDictionary
import android.util.Log
import java.util.Locale

/**
 * Observes Android's system user dictionary (Settings → Language → Personal dictionary).
 * Whenever the dictionary changes, [words] is refreshed automatically.
 *
 * The words list is capped at ~200 tokens to avoid blowing the LLM context window.
 *
 * Adapted from FutoBoard's UserDictionaryObserver.
 */
class UserDictionaryObserver(context: Context) {

    data class Word(val word: String, val frequency: Int, val locale: String?, val shortcut: String?)

    private val contentResolver = context.applicationContext.contentResolver
    private val uri: Uri = UserDictionary.Words.CONTENT_URI
    private val handler = Handler(Looper.getMainLooper())
    private var wordsList = mutableListOf<Word>()

    /** Current snapshot of user dictionary words. Thread-safe read via copy. */
    val words: List<Word> get() = synchronized(wordsList) { wordsList.toList() }

    private val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            updateWords()
        }
    }

    init {
        try {
            contentResolver.registerContentObserver(uri, true, contentObserver)
        } catch (_: Exception) { }
        updateWords()
    }

    /**
     * Get words matching any of the given locales.
     * Words with null locale match everything.
     */
    fun getWords(locales: List<Locale>): List<String> {
        return synchronized(wordsList) {
            wordsList.filter { w ->
                if (w.locale == null) {
                    true
                } else {
                    val wordLocale = Locale.forLanguageTag(w.locale)
                    locales.any { it.language == wordLocale.language }
                }
            }.map { it.word }
        }
    }

    /**
     * Get all words regardless of locale.
     */
    fun getAllWords(): List<String> {
        return synchronized(wordsList) { wordsList.map { it.word } }
    }

    internal fun updateWords() {
        val projection = arrayOf(
            UserDictionary.Words.WORD,
            UserDictionary.Words.FREQUENCY,
            UserDictionary.Words.LOCALE,
            UserDictionary.Words.SHORTCUT,
        )

        val cursor: Cursor? = try {
            contentResolver.query(uri, projection, null, null, null)
        } catch (e: Exception) {
            Log.w("UserDictionaryObserver", "Could not query user dictionary: ${e.message}")
            null
        }

        val newWords = mutableListOf<Word>()
        cursor?.use {
            val wordColumn = it.getColumnIndex(UserDictionary.Words.WORD)
            val frequencyColumn = it.getColumnIndex(UserDictionary.Words.FREQUENCY)
            val localeColumn = it.getColumnIndex(UserDictionary.Words.LOCALE)
            val shortcutColumn = it.getColumnIndex(UserDictionary.Words.SHORTCUT)

            while (it.moveToNext()) {
                val word = it.getString(wordColumn)
                val frequency = it.getInt(frequencyColumn)
                val locale = it.getString(localeColumn)
                val shortcut = it.getString(shortcutColumn)

                if (word.length < 64) {
                    newWords.add(Word(word, frequency, locale, shortcut))
                }
            }
        }

        newWords.sortByDescending { it.frequency }

        // Cap at ~200 tokens to avoid blowing the LLM context window
        var approxNumTokens = 0
        var cutoffIndex = -1
        for (index in newWords.indices) {
            approxNumTokens += (4 + newWords[index].word.length) / 4
            if (approxNumTokens > 200) {
                cutoffIndex = index
                break
            }
        }

        synchronized(wordsList) {
            wordsList.clear()
            if (cutoffIndex != -1) {
                wordsList.addAll(newWords.subList(0, cutoffIndex))
            } else {
                wordsList.addAll(newWords)
            }
        }

        Log.d("UserDictionaryObserver", "User dictionary updated: ${wordsList.size} words")
    }

    fun unregister() {
        try {
            contentResolver.unregisterContentObserver(contentObserver)
        } catch (_: Exception) { }
    }
}
