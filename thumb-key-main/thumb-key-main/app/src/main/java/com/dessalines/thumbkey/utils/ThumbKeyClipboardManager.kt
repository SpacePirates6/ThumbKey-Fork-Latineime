package com.dessalines.thumbkey.utils

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.dessalines.thumbkey.db.ClipboardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ThumbKeyClipboardManager(
    private val context: Context,
    private val clipboardRepository: ClipboardRepository,
) {
    private val systemClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // We hold onto the root Job so `stopListening()` can cancel it; previously
    // this scope was never cancelled, so repository coroutines could keep
    // running after the IME was torn down (retaining the Context + repository
    // references indefinitely).
    private var job: Job = SupervisorJob()
    private var scope: CoroutineScope = CoroutineScope(job + Dispatchers.IO)
    private var isListening = false
    private var lastClipText: String? = null

    private val clipboardListener =
        ClipboardManager.OnPrimaryClipChangedListener {
            val clip = systemClipboardManager.primaryClip
            if (clip == null || clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0).coerceToText(context).toString()
            if (text.isBlank() || text == lastClipText) return@OnPrimaryClipChangedListener
            lastClipText = text
            Log.d(TAG, "Adding clipboard item: $text")
            scope.launch {
                clipboardRepository.addItem(text)
            }
        }

    fun startListening() {
        if (!isListening) {
            // Rebuild the scope in case stopListening() cancelled the previous one.
            if (!job.isActive) {
                job = SupervisorJob()
                scope = CoroutineScope(job + Dispatchers.IO)
            }
            systemClipboardManager.addPrimaryClipChangedListener(clipboardListener)
            isListening = true
        }
    }

    fun stopListening() {
        if (isListening) {
            systemClipboardManager.removePrimaryClipChangedListener(clipboardListener)
            isListening = false
        }
        // Cancel any in-flight writes (retention decisions, expiry sweeps) so
        // the scope doesn't outlive the IME instance.
        job.cancel()
    }

    fun clearExpired() {
        scope.launch {
            clipboardRepository.clearExpired()
        }
    }
}
