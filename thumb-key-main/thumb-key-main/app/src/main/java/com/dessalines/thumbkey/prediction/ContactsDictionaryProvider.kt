package com.dessalines.thumbkey.prediction

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.ContactsContract.Contacts
import android.util.Log
import java.util.Locale

class ContactsDictionaryProvider(private val context: Context) {

    private val contentResolver = context.applicationContext.contentResolver
    private var wordsList = mutableListOf<String>()
    private val lock = Any()

    fun refresh() {
        val newWords = mutableSetOf<String>()
        val projection = if (Build.VERSION.SDK_INT >= 26) {
            arrayOf(Contacts.DISPLAY_NAME_PRIMARY)
        } else {
            arrayOf(Contacts.DISPLAY_NAME)
        }
        try {
            val cursor: Cursor? = contentResolver.query(
                Contacts.CONTENT_URI,
                projection,
                null,
                null,
                projection[0]
            ) ?: return

            cursor?.use {
                val nameCol = it.getColumnIndex(projection[0])
                if (nameCol < 0) return
                while (it.moveToNext()) {
                    val name = it.getString(nameCol) ?: continue
                    for (word in name.split(Regex("\\s+"))) {
                        if (word.length >= 2) {
                            newWords.add(word)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w("ContactsDict", "Missing READ_CONTACTS permission", e)
            synchronized(lock) {
                wordsList.clear()
            }
            return
        }

        val sorted = newWords.toList().sorted()
        val capped = sorted.take(500)

        synchronized(lock) {
            wordsList.clear()
            wordsList.addAll(capped)
        }
        Log.d("ContactsDict", "Loaded ${capped.size} contact words")
    }

    fun getCompletions(prefix: String, limit: Int = 5): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase(Locale.getDefault())
        return synchronized(lock) {
            wordsList.filter { it.lowercase(Locale.getDefault()).startsWith(lower) }
                .take(limit)
        }
    }

    fun contains(word: String): Boolean {
        return synchronized(lock) {
            wordsList.any { it.equals(word, ignoreCase = true) }
        }
    }

    fun getAllWords(): List<String> {
        return synchronized(lock) { wordsList.toList() }
    }

    val size: Int
        get() = synchronized(lock) { wordsList.size }
}
