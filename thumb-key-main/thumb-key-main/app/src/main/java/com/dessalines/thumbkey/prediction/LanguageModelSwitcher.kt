package com.dessalines.thumbkey.prediction

import android.content.Context
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LanguageModelSwitcher(private val context: Context) {

    private val lock = ReentrantLock()
    private val cache = object : LinkedHashMap<Locale, PredictionBridge>(3, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Locale, PredictionBridge>?): Boolean {
            return if (size > MAX_CACHED_MODELS && eldest != null) {
                eldest.value.close()
                true
            } else false
        }
    }

    @Volatile
    private var currentLocale: Locale = context.resources.configuration.locales[0]

    suspend fun switchToLocale(locale: Locale): PredictionBridge? {
        return withContext(Dispatchers.IO) {
            lock.withLock {
                cache[locale]?.let { bridge ->
                    currentLocale = locale
                    return@withContext bridge
                }
            }

            val bridge = PredictionBridge(context)
            val modelFile = ModelPaths.getModelForLanguage(context, locale.language)
                ?: ModelPaths.getDefaultModelPath(context)
            val success = bridge.initialize(modelFile)

            if (!success) {
                return@withContext null
            }

            lock.withLock {
                val existing = cache[locale]
                if (existing != null) {
                    bridge.close()
                    currentLocale = locale
                    return@withContext existing
                }
                cache[locale] = bridge
                currentLocale = locale
            }
            bridge
        }
    }

    fun getCurrentLocale(): Locale = currentLocale

    fun closeAll() {
        lock.withLock {
            cache.values.forEach { it.close() }
            cache.clear()
        }
    }

    companion object {
        private const val MAX_CACHED_MODELS = 2
    }
}
