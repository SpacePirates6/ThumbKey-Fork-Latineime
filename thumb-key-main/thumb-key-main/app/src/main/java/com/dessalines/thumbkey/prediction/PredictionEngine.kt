package com.dessalines.thumbkey.prediction

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.dessalines.thumbkey.IMEService
import com.dessalines.thumbkey.utils.KeyboardC
import com.dessalines.thumbkey.utils.TAG
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

// ── Suggestion metadata ─────────────────────────────────────────────

enum class SuggestionKind {
    TYPED,
    CORRECTION,
    COMPLETION,
    PREDICTION,
    USER_DICT,
    CONTACTS,
    HISTORY,
    EMOJI,
}

data class Suggestion(
    val word: String,
    val kind: SuggestionKind,
    val score: Int = 0,
    val agreedByMultipleSources: Boolean = false,
)

// ── URL / email / caps heuristics ───────────────────────────────────

private val URL_PATTERN = Regex(
    """^(https?://|www\.|[a-zA-Z0-9\-]+\.(com|org|net|io|dev|co|app|me|edu|gov|uk|de|fr|jp|au)(/|\b))""",
    RegexOption.IGNORE_CASE,
)
private val EMAIL_PATTERN = Regex("""^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$""")
private val WHITESPACE_SPLITTER = Regex("\\s+")

private fun looksLikeUrlOrEmail(word: String): Boolean {
    val before = word.trim()
    if (before.contains("://") || before.contains("www.")) return true
    if (before.contains('@') && EMAIL_PATTERN.matches(before)) return true
    if (URL_PATTERN.containsMatchIn(before)) return true
    return false
}

private fun isMostlyCaps(word: String): Boolean {
    if (word.length < 3) return false
    val upper = word.count { it.isUpperCase() }
    return upper.toFloat() / word.length > 0.6f
}

/**
 * Non-intrusive prediction and autocorrect engine for ThumbKey.
 *
 * Characters are committed exactly as before — the engine passively
 * observes what was typed and provides suggestions.
 *
 * ### Architecture
 * The main thread only reads InputConnection text and posts the final
 * suggestion list. ALL heavy work (LLM inference, dictionary lookups,
 * scoring, merging) runs on a dedicated single-thread executor so the
 * UI never blocks on prediction.
 *
 * ### Prediction Sources
 * 1. **Android SpellChecker** — system-level suggestions
 * 2. **FUTO LanguageModel (LLM)** — native transformer predictions
 * 3. **ThumbKey Proximity Model** — keyboard-layout-aware confusion matrix
 * 4. **User History Dictionary** — real-time frequency boosting
 * 5. **Contacts Dictionary** — names from device contacts
 * 6. **User Dictionary** — Android system personal dictionary
 *
 * ### Autocorrect
 * When the LLM returns "autocorrect" mode, the first suggestion is bolded.
 * Pressing space/punctuation replaces the word automatically.
 * Pressing backspace after autocorrect reverts it.
 * Autocorrect is suppressed for URLs, emails, and mostly-caps words.
 */
class PredictionEngine(private val context: Context) :
    SpellCheckerSession.SpellCheckerSessionListener {

    // ── Public observable state ──────────────────────────────────────

    /** Suggestions for the suggestion bar. Index 0 = typed word. */
    val suggestions = mutableStateListOf<Suggestion>()

    /** Whether the top suggestion should be auto-applied on space. */
    val shouldAutocorrect = mutableStateOf(false)

    /** Whether prediction is enabled. */
    var predictionsEnabled: Boolean = true

    /** Set by IMEService when the current field is a password or TYPE_TEXT_FLAG_NO_SUGGESTIONS. */
    var suppressPredictions: Boolean = false

    /** Set by IMEService for password fields — prevents learning from typed text. */
    var suppressLearning: Boolean = false

    // ── Autocorrect settings ────────────────────────────────────────

    var autocorrectEnabled: Boolean = true
    var autocorrectThreshold: Float = 4.0f
    var transformerWeight: Float = 3.4f

    // ── Phantom space state ─────────────────────────────────────────

    private var phantomSpacePending = false

    // ── Double-space-to-period state ────────────────────────────────

    private var lastSpaceTimestamp = 0L
    private val doubleSpaceTimeoutMs = 400L
    private var lastDoubleSpacePeriod = false

    // ── Composing text state ────────────────────────────────────────

    var composingEnabled: Boolean = true
    private var isComposing: Boolean = false

    // ── Shift state ─────────────────────────────────────────────────

    var currentShiftState: ShiftState = ShiftState.UNSHIFTED

    enum class ShiftState { UNSHIFTED, SHIFTED, CAPS_LOCK }

    // ── External components ─────────────────────────────────────────

    var predictionBridge: PredictionBridge? = null
    var proximityModel: ThumbKeyProximityModel? = null
    var userDictionary: UserDictionaryObserver? = null
    var suggestionBlacklist: SuggestionBlacklist? = null
    var trainingLog: TrainingLog? = null
    var personalizationTrainer: PersonalizationTrainer? = null
    var userHistoryDictionary: UserHistoryDictionary? = null
    var contactsDictionary: ContactsDictionaryProvider? = null
    var binaryDictionary: BinaryDictionaryBridge? = null

    // ── Per-character composing state ───────────────────────────────

    val wordComposer = WordComposer()

    // ── Gesture/swipe typing ────────────────────────────────────────

    val gestureDecoder = GestureDecoder()
    var gestureResultPending = false
        private set
    @Volatile private var gestureCancelled = false

    // ── Recorrection state ──────────────────────────────────────────

    private var recorrectionWord: String? = null
    private var recorrectionStart: Int = -1
    private var recorrectionEnd: Int = -1

    val isInRecorrectionMode: Boolean get() = recorrectionWord != null

    // ── Undo autocorrect state ──────────────────────────────────────

    private var lastAutocorrect: AutocorrectRecord? = null

    data class AutocorrectRecord(
        val originalWord: String,
        val correctedWord: String,
        val cursorPosition: Int,
    )

    // ── Internal state ───────────────────────────────────────────────

    private var spellCheckerSession: SpellCheckerSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Executor for dictionary lookups, merging, and coordinating the LLM call. */
    private val dictExecutor = Executors.newSingleThreadExecutor()

    /** Dedicated executor for native LLM inference (mirrors FUTO's LanguageModelScope). */
    private val lmExecutor = Executors.newSingleThreadExecutor()

    private var lastQueriedWord: String = ""
    private var pendingQuery: Runnable? = null

    /**
     * Adaptive debounce: starts at 40ms and adjusts based on how fast
     * prediction actually completes, matching FUTO's approach. Range: 16-60ms.
     */
    private var queryDelayMs = 40L
    private var avgPredictionTimeMs = 40L

    /** Hard timeout for the LLM native call. Falls back to dict-only if exceeded. */
    private val lmTimeoutMs = 350L

    /** Sequence counter for stale-query cancellation (like FUTO's sequenceId). */
    private val queryGeneration = AtomicInteger(0)

    /** Consecutive LLM timeouts — auto-disables transformer after threshold. */
    private var consecutiveTimeouts = 0
    private var transformerTempDisabled = false
    private val maxConsecutiveTimeouts = 5

    private var lastLMMode: String = "clueless"
    private var lastAutoCommitConfidence: Float = 0f
    @Volatile private var spellCheckerResults = listOf<String>()
    @Volatile private var llmResults = listOf<Pair<String, Float>>()
    @Volatile private var llmResultsPending = false
    @Volatile private var lastTextBeforeCursor = ""

    // ── Suggestion cache ─────────────────────────────────────────────

    private val suggestionCache = object : LinkedHashMap<String, CachedResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResult>?): Boolean {
            return size > 20
        }
    }

    private data class CachedResult(
        val suggestions: List<Suggestion>,
        val shouldAutocorrect: Boolean,
        val timestamp: Long,
    )

    private val cacheMaxAgeMs = 10_000L

    // ── Touch coordinate buffer ─────────────────────────────────────

    private val touchXBuffer = mutableListOf<Int>()
    private val touchYBuffer = mutableListOf<Int>()

    fun recordTouchCoordinate(x: Int, y: Int) {
        touchXBuffer.add(x)
        touchYBuffer.add(y)
    }

    private fun clearTouchBuffer() {
        touchXBuffer.clear()
        touchYBuffer.clear()
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    fun open() {
        try {
            if (spellCheckerSession != null) return
            val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE)
                as? TextServicesManager ?: return
            spellCheckerSession = tsm.newSpellCheckerSession(
                null, Locale.getDefault(), this, false,
            )
            if (spellCheckerSession == null) {
                Log.w(TAG, "PredictionEngine: SpellCheckerSession unavailable")
            }
        } catch (e: Exception) {
            Log.w(TAG, "PredictionEngine: Failed to open SpellCheckerSession", e)
        }
    }

    fun close() {
        cancelPending()
        queryGeneration.incrementAndGet()
        suggestions.clear()
        shouldAutocorrect.value = false
        lastQueriedWord = ""
        lastAutocorrect = null
        spellCheckerSession?.close()
        spellCheckerSession = null
        userDictionary?.unregister()
        nativeProximityInfo?.release()
        nativeProximityInfo = null
    }

    fun clearSuggestions() {
        cancelPending()
        queryGeneration.incrementAndGet()
        suggestions.clear()
        shouldAutocorrect.value = false
        lastQueriedWord = ""
        lastLMMode = "clueless"
        lastAutoCommitConfidence = 0f
        spellCheckerResults = emptyList()
        llmResults = emptyList()
        llmResultsPending = false
        clearTouchBuffer()
        wordComposer.reset()
        exitRecorrection()
        // Always mark composing as ended — the composing span on the Android/IC side is
        // gone after any clearSuggestions() call path. Leaving isComposing=true would
        // cause updateComposingText to re-insert a stale wordComposer string as a brand-new
        // composing span on the next keystroke, duplicating already-committed text.
        isComposing = false
    }

    /**
     * Full reset for a new input context (field focus change, IME restart, etc.).
     * Clears all transient typing state that must not bleed across input fields.
     */
    fun resetForNewInput() {
        clearSuggestions()
        lastAutocorrect = null
        phantomSpacePending = false
        lastSpaceTimestamp = 0L
        lastDoubleSpacePeriod = false
    }

    private var nativeProximityInfo: NativeProximityInfo? = null

    fun updateProximityModel(keyboard: KeyboardC) {
        val existing = proximityModel
        if (existing != null) {
            existing.rebuild(keyboard)
        } else {
            proximityModel = ThumbKeyProximityModel(keyboard)
        }

        nativeProximityInfo?.release()
        try {
            val npi = NativeProximityInfo.fromKeyboard(keyboard)
            nativeProximityInfo = npi
            predictionBridge?.setProximityInfoHandle(npi.getHandle())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build NativeProximityInfo", e)
        }
    }

    fun reloadSettings(context: Context) {
        val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        predictionsEnabled = prefs.getBoolean("ai_enabled", true)
        autocorrectEnabled = prefs.getBoolean("autocorrect_enabled", true)
        autocorrectThreshold = prefs.getFloat("autocorrect_threshold", 4.0f)
        transformerWeight = prefs.getFloat("transformer_weight", 3.4f)
        OffensiveWordFilter.enabled = prefs.getBoolean("offensive_filter_enabled", true)
    }

    // ── Called after every key action ────────────────────────────────

    fun onKeyAction(ime: IMEService) {
        if (!predictionsEnabled || suppressPredictions) {
            if (suggestions.isNotEmpty()) suggestions.clear()
            return
        }
        cancelPending()
        queryGeneration.incrementAndGet()
        pendingQuery = Runnable { queryCurrentWord(ime) }
        mainHandler.postDelayed(pendingQuery!!, queryDelayMs)
    }

    // ── Word separator / autocorrect ────────────────────────────────

    fun onWordSeparator(ime: IMEService, separator: String): Boolean {
        val ic = ime.currentInputConnection

        // Finish composing FIRST so the IC reflects the actual committed text
        if (isComposing) {
            ic?.finishComposingText()
            isComposing = false
            wordComposer.reset()
        }

        val originalWord = if (ic != null) currentWordBeforeCursor(ic) else ""

        // Double-space-to-period
        if (separator == " ") {
            val now = System.currentTimeMillis()
            if (now - lastSpaceTimestamp < doubleSpaceTimeoutMs && originalWord.isEmpty()) {
                val textBefore = ic?.getTextBeforeCursor(2, 0)?.toString() ?: ""
                if (textBefore.endsWith(" ") || textBefore.endsWith("  ")) {
                    ic?.beginBatchEdit()
                    ic?.deleteSurroundingText(1, 0)
                    ic?.commitText(". ", 1)
                    ic?.endBatchEdit()
                    lastSpaceTimestamp = 0L
                    lastDoubleSpacePeriod = true
                    clearSuggestions()
                    return true
                }
            }
            lastSpaceTimestamp = now
        } else {
            lastSpaceTimestamp = 0L
        }

        // Never auto-replace on space — the user accepts suggestions via
        // the spacebar slide-up gesture (AcceptSuggestion) instead.
        if (separator == " " && originalWord.isNotBlank()) {
            phantomSpacePending = true
        }

        // Record the typed word for learning (background)
        if (originalWord.isNotBlank() && !suppressLearning) {
            val wordCopy = originalWord
            val contextSnapshot = ic?.getTextBeforeCursor(150, 0)?.toString() ?: ""
            dictExecutor.submit {
                val prevWord = extractPreviousWord(contextSnapshot)
                userHistoryDictionary?.recordWord(wordCopy, prevWord)
                val priorContext = contextSnapshot
                    .dropLast(wordCopy.length.coerceAtMost(contextSnapshot.length))
                    .trimEnd().takeLast(100)
                personalizationTrainer?.addTrainingExample(priorContext, wordCopy)
            }
        }

        clearSuggestions()
        return false
    }

    fun onBackspace(ime: IMEService): Boolean {
        val ic = ime.currentInputConnection ?: return false

        if (lastDoubleSpacePeriod) {
            val textBefore = ic.getTextBeforeCursor(3, 0)?.toString() ?: ""
            if (textBefore.endsWith(". ")) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(2, 0)
                ic.commitText("  ", 1)
                ic.endBatchEdit()
                lastDoubleSpacePeriod = false
                return true
            }
            lastDoubleSpacePeriod = false
        }

        phantomSpacePending = false

        val record = lastAutocorrect ?: return false
        val textBefore = ic.getTextBeforeCursor(500, 0)?.toString() ?: return false

        val expected = record.correctedWord
        val expectedWithSpace = expected + " "
        if (textBefore.length < expectedWithSpace.length) {
            lastAutocorrect = null
            return false
        }

        val endPart = textBefore.takeLast(expectedWithSpace.length)
        if (!endPart.equals(expectedWithSpace, ignoreCase = true)) {
            lastAutocorrect = null
            return false
        }

        ic.beginBatchEdit()
        ic.deleteSurroundingText(expectedWithSpace.length, 0)
        ic.commitText(record.originalWord, 1)
        ic.endBatchEdit()

        val wordToUnlearn = record.correctedWord
        dictExecutor.submit { userHistoryDictionary?.unlearn(wordToUnlearn) }

        Log.d(TAG, "Undo autocorrect: \"${record.correctedWord}\" → \"${record.originalWord}\"")
        lastAutocorrect = null
        return true
    }

    fun onSuggestionSelected(index: Int, ime: IMEService) {
        if (index < 0 || index >= suggestions.size) return
        val selected = suggestions[index]
        val ic = ime.currentInputConnection ?: return

        // Finish composing FIRST so IC reflects actual committed text
        if (isComposing) {
            ic.finishComposingText()
            isComposing = false
        }

        // Use robust word detection — IC can return stale/incomplete text in WebView apps
        // (e.g. Twitch chat). Under-deletion causes duplication bugs like "h" + "hi" → "hhi".
        val word = currentWordBeforeCursorRobust(ic)
        if (word.isNotEmpty()) {
            val replacement = matchCapitalization(word, selected.word)

            // Sanity check: the last N chars before cursor must match our word.
            // Prevents duplication (e.g. "h"+"hi"→"hhi") and over-deletion into previous word.
            val actualBefore = ic.getTextBeforeCursor(word.length.coerceAtLeast(1), 0)
                ?.toString() ?: ""
            if (actualBefore.length != word.length || !actualBefore.equals(word, ignoreCase = true)) {
                Log.w(TAG, "Suggestion skip: buffer mismatch word=\"$word\" actual=\"$actualBefore\"")
                clearSuggestions()
                return
            }

            val deleteLen = word.length
            if (deleteLen <= 0) {
                clearSuggestions()
                return
            }

            ic.beginBatchEdit()
            ic.deleteSurroundingText(deleteLen, 0)
            ic.commitText(replacement, 1)
            ic.commitText(" ", 1)
            ic.endBatchEdit()

            // Only arm the undo-on-backspace token when the text actually changed.
            // If replacement == word (user confirmed their own typed word via AcceptSuggestion,
            // or capitalization matched already), there is nothing to undo. Recording the token
            // anyway causes onBackspace to fire later: it deletes "word " and re-inserts "word",
            // which the user perceives as backspace "inserting the word" on every correction cycle.
            val extractedText = ic.getTextBeforeCursor(500, 0)?.toString()
            lastAutocorrect = if (replacement != word) {
                AutocorrectRecord(
                    originalWord = word,
                    correctedWord = replacement,
                    cursorPosition = extractedText?.length ?: 0,
                )
            } else {
                null
            }

            // Move dictionary/training recording to background
            if (!suppressLearning) {
                val textBefore = extractedText ?: ""
                val origWord = word
                val replWord = replacement
                dictExecutor.submit {
                    val contextForTraining = textBefore
                        .dropLast((replWord.length + 1).coerceAtMost(textBefore.length))
                        .takeLast(100)
                    trainingLog?.addEntry(
                        originalWord = origWord,
                        committedWord = replWord,
                        priorContext = contextForTraining,
                        importance = 1,
                    )
                    val prevWord = extractPreviousWord(textBefore)
                    userHistoryDictionary?.recordWord(replWord, prevWord)
                }
            }
        } else {
            lastAutocorrect = null
        }

        clearSuggestions()
    }

    // ══════════════════════════════════════════════════════════════════
    //  CORE QUERY PIPELINE — parallel LLM + dictionary with timeout
    // ══════════════════════════════════════════════════════════════════

    /**
     * Entry point called after the debounce timer fires.
     * Reads the minimum needed from the InputConnection on the main
     * thread, then submits parallel LLM + dictionary work to background
     * executors with a hard timeout on the LLM.
     *
     * Architecture (mirrors FUTO LatinIME):
     * - dictExecutor runs dictionary lookups immediately
     * - lmExecutor runs the native LLM call as a Future
     * - dictExecutor waits on the LLM Future with a hard 350ms timeout
     * - if LLM times out, we fall back to dictionary-only results
     * - consecutive timeouts auto-disable the transformer temporarily
     */
    private fun queryCurrentWord(ime: IMEService) {
        try {
            val ic = ime.currentInputConnection ?: return
            val word = currentWordBeforeCursorRobust(ic)

            // KILL THE DESYNC LOOP: If the text in the editor does not match our internal
            // state, the editor has dropped the composing span. We MUST abort composing here
            // rather than adopting the duplicated text (which would fuel exponential growth).
            if (isComposing && word != wordComposer.typedWord) {
                isComposing = false
                wordComposer.reset()
                ic.finishComposingText()
            }

            val isPostSpace = word.isEmpty()

            if (!isPostSpace && word.length < 2) {
                if (suggestions.isNotEmpty()) {
                    suggestions.clear()
                    shouldAutocorrect.value = false
                }
                return
            }

            val queryKey = if (isPostSpace) "\u0000next" else word
            if (queryKey == lastQueriedWord) return
            lastQueriedWord = queryKey

            val cached = suggestionCache[queryKey]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < cacheMaxAgeMs) {
                suggestions.clear()
                suggestions.addAll(cached.suggestions)
                shouldAutocorrect.value = cached.shouldAutocorrect
                return
            }

            val textBefore = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
            lastTextBeforeCursor = textBefore

            val touchX = if (!isPostSpace && touchXBuffer.size == word.length && touchXBuffer.isNotEmpty()) {
                touchXBuffer.toIntArray()
            } else null
            val touchY = if (touchX != null) touchYBuffer.toIntArray() else null

            spellCheckerResults = emptyList()
            llmResults = emptyList()
            lastLMMode = "clueless"
            lastAutoCommitConfidence = 0f
            llmResultsPending = true

            if (!isPostSpace) {
                try {
                    spellCheckerSession?.getSentenceSuggestions(
                        arrayOf(TextInfo(word)), 5,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "SpellChecker query failed", e)
                }
            }

            val gen = queryGeneration.incrementAndGet()
            val bridge = predictionBridge
            val blacklistArr = suggestionBlacklist?.bannedWordsArray ?: emptyArray()
            val proxModel = proximityModel
            val shiftState = currentShiftState
            val acEnabled = autocorrectEnabled
            val tWeight = transformerWeight
            val acThreshold = autocorrectThreshold
            val lmDisabled = transformerTempDisabled
            val startTime = System.nanoTime()

            dictExecutor.submit {
                try {
                    if (gen != queryGeneration.get()) return@submit

                    // ── 1. Submit LLM to its own executor as a Future ─
                    val lmFuture: Future<LmResult>? = if (
                        !lmDisabled && bridge != null && bridge.isReady()
                    ) {
                        lmExecutor.submit<LmResult> {
                            if (gen != queryGeneration.get()) return@submit LmResult.EMPTY

                            val coords = touchX?.let { x ->
                                touchY?.let { y -> x to y }
                            } ?: if (!isPostSpace) proxModel?.getWordCoordinates(word) else null

                            val prevWordsList = if (textBefore.isNotBlank()) {
                                val allWords = textBefore.trim().split(WHITESPACE_SPLITTER)
                                    .filter { it.isNotEmpty() }
                                val withoutPartial = if (word.isNotEmpty()) {
                                    val trimmed = textBefore.trimEnd()
                                    if (trimmed.endsWith(word)) allWords.dropLast(1) else allWords
                                } else allWords
                                withoutPartial.takeLast(3)
                            } else emptyList()

                            if (gen != queryGeneration.get()) return@submit LmResult.EMPTY

                            val result = bridge.getAllPredictions(
                                textBeforeCursor = textBefore,
                                autocorrectThreshold = acThreshold,
                                bannedWords = blacklistArr,
                                composeX = coords?.first,
                                composeY = coords?.second,
                                previousWords = prevWordsList,
                            )

                            if (gen != queryGeneration.get()) return@submit LmResult.EMPTY

                            LmResult(
                                result.predictions,
                                result.mode,
                                result.autoCommitConfidence,
                            )
                        }
                    } else null

                    // ── 2. Dictionary lookups run immediately (parallel with LLM) ─
                    val dictSuggestions = if (!isPostSpace) {
                        gatherDictionarySuggestions(word, textBefore, blacklistArr)
                    } else emptyMap()

                    if (gen != queryGeneration.get()) {
                        lmFuture?.cancel(false)
                        return@submit
                    }

                    // ── 3. Wait for LLM with hard timeout ─────────────
                    var lmResult = LmResult.EMPTY
                    var timedOut = false

                    if (lmFuture != null) {
                        try {
                            lmResult = lmFuture.get(lmTimeoutMs, TimeUnit.MILLISECONDS)
                            consecutiveTimeouts = 0
                            if (transformerTempDisabled) {
                                transformerTempDisabled = false
                                Log.i(TAG, "LLM re-enabled after successful prediction")
                            }
                        } catch (_: TimeoutException) {
                            timedOut = true
                            lmFuture.cancel(false)
                            consecutiveTimeouts++
                            Log.w(TAG, "LLM timed out (${lmTimeoutMs}ms), " +
                                "consecutive=$consecutiveTimeouts — falling back to dict-only")
                            if (consecutiveTimeouts >= maxConsecutiveTimeouts) {
                                transformerTempDisabled = true
                                Log.w(TAG, "LLM auto-disabled after $maxConsecutiveTimeouts " +
                                    "consecutive timeouts")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "LLM Future failed", e)
                        }
                    }

                    if (gen != queryGeneration.get()) return@submit

                    llmResults = lmResult.predictions
                    lastLMMode = lmResult.mode
                    lastAutoCommitConfidence = lmResult.confidence
                    llmResultsPending = false

                    // ── 4. Merge all sources ─────────────────────────
                    val finalList: List<Suggestion>
                    val shouldAC: Boolean

                    if (isPostSpace) {
                        val pair = buildNextWordPredictions(
                            lmResult.predictions, tWeight, textBefore,
                        )
                        finalList = pair.first
                        shouldAC = false
                    } else {
                        val spellResults = spellCheckerResults
                        val pair = buildMergedSuggestionsWithDict(
                            word, textBefore, lmResult.predictions, lmResult.mode,
                            lmResult.confidence, spellResults, dictSuggestions,
                            blacklistArr, shiftState, acEnabled, tWeight,
                        )
                        finalList = pair.first
                        shouldAC = pair.second
                    }

                    if (gen != queryGeneration.get()) return@submit

                    // ── 5. Adaptive debounce adjustment ─────────────
                    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                    avgPredictionTimeMs = (avgPredictionTimeMs * 3 + elapsedMs) / 4
                    queryDelayMs = (avgPredictionTimeMs / 8).coerceIn(16, 60)

                    // ── 6. Post final result to main thread ─────────
                    mainHandler.post {
                        if (gen != queryGeneration.get()) return@post
                        suggestions.clear()
                        suggestions.addAll(finalList)
                        shouldAutocorrect.value = shouldAC

                        suggestionCache[queryKey] = CachedResult(
                            suggestions = finalList,
                            shouldAutocorrect = shouldAC,
                            timestamp = System.currentTimeMillis(),
                        )
                    }
                } catch (e: Throwable) {
                    llmResultsPending = false
                    Log.w(TAG, "Background prediction failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryCurrentWord failed", e)
        }
    }

    /** Container for LLM results so we can pass them through a Future. */
    private data class LmResult(
        val predictions: List<Pair<String, Float>>,
        val mode: String,
        val confidence: Float,
    ) {
        companion object {
            val EMPTY = LmResult(emptyList(), "clueless", 0f)
        }
    }

    /**
     * Gathers all non-LLM dictionary suggestions in one pass.
     * Runs on dictExecutor in parallel with the LLM Future.
     */
    private fun gatherDictionarySuggestions(
        typedWord: String,
        textBefore: String,
        blacklistArr: Array<String>,
    ): Map<String, ScoredSuggestion> {
        val blacklist = suggestionBlacklist
        val prevWord = extractPreviousWord(textBefore)
        val merged = HashMap<String, ScoredSuggestion>(32)

        val userWords = userDictionary?.getAllWords() ?: emptyList()
        for (w in userWords) {
            if (!w.startsWith(typedWord, ignoreCase = true)) continue
            if (w.equals(typedWord, ignoreCase = true)) continue
            if (blacklist != null && !blacklist.isSuggestionOk(w)) continue
            val key = w.lowercase()
            merged[key] = ScoredSuggestion(w, 500, Source.USER_DICT)
        }

        val histDict = userHistoryDictionary
        if (histDict != null) {
            val histCompletions = histDict.getCompletions(typedWord, 5)
            for ((word, freq) in histCompletions) {
                if (word.equals(typedWord, ignoreCase = true)) continue
                if (blacklist != null && !blacklist.isSuggestionOk(word)) continue
                if (OffensiveWordFilter.isOffensive(word)) continue
                val bigramBoost = if (prevWord != null) {
                    (histDict.getBigramBoost(prevWord, word) * 300).toInt()
                } else 0
                val histScore = (freq * 30).coerceAtMost(600) + bigramBoost
                val key = word.lowercase()
                val existing = merged[key]
                if (existing != null) existing.score += histScore
                else merged[key] = ScoredSuggestion(word, histScore, Source.HISTORY)
            }
        }

        val contactsDict = contactsDictionary
        if (contactsDict != null) {
            val contactCompletions = contactsDict.getCompletions(typedWord, 3)
            for (word in contactCompletions) {
                if (word.equals(typedWord, ignoreCase = true)) continue
                if (blacklist != null && !blacklist.isSuggestionOk(word)) continue
                val key = word.lowercase()
                val existing = merged[key]
                if (existing != null) existing.score += 400
                else merged[key] = ScoredSuggestion(word, 400, Source.CONTACTS)
            }
        }

        val proxModel = proximityModel
        if (proxModel != null) {
            val alternatives = proxModel.generateAlternativeWords(typedWord, 3)
            for (alt in alternatives) {
                if (blacklist != null && !blacklist.isSuggestionOk(alt)) continue
                if (OffensiveWordFilter.isOffensive(alt)) continue
                val key = alt.lowercase()
                if (key !in merged) {
                    merged[key] = ScoredSuggestion(alt, 50, Source.PROXIMITY)
                }
            }
        }

        val binDict = binaryDictionary
        if (binDict != null && binDict.isLoaded()) {
            val completions = binDict.getCompletions(typedWord, 5)
            for ((word, freq) in completions) {
                if (word.equals(typedWord, ignoreCase = true)) continue
                if (blacklist != null && !blacklist.isSuggestionOk(word)) continue
                if (OffensiveWordFilter.isOffensive(word)) continue
                val score = 200 + (freq / 5).coerceAtMost(100)
                val key = word.lowercase()
                val existing = merged[key]
                if (existing != null) existing.score += score
                else merged[key] = ScoredSuggestion(word, score, Source.BINARY_DICT)
            }
        }

        val emojis = EmojiSuggestions.getEmojiForWord(typedWord)
        for (emoji in emojis) {
            merged[emoji] = ScoredSuggestion(emoji, 200, Source.EMOJI)
        }

        val clipText = getClipboardSuggestion()
        if (clipText != null && clipText.startsWith(typedWord, ignoreCase = true) &&
            !clipText.equals(typedWord, ignoreCase = true)
        ) {
            val key = clipText.lowercase()
            if (key !in merged) {
                merged[key] = ScoredSuggestion(clipText, 350, Source.CLIPBOARD)
            }
        }

        return merged
    }

    // ── Background merge: next-word predictions ─────────────────────

    private fun buildNextWordPredictions(
        lmPredictions: List<Pair<String, Float>>,
        tWeight: Float,
        textBefore: String,
    ): Pair<List<Suggestion>, Boolean> {
        val blacklist = suggestionBlacklist
        val prevWord = extractPreviousWord(textBefore)
        val merged = HashMap<String, ScoredSuggestion>(16)

        for ((word, score) in lmPredictions) {
            if (blacklist != null && !blacklist.isSuggestionOk(word)) continue
            if (OffensiveWordFilter.isOffensive(word)) continue
            val key = word.lowercase()
            merged[key] = ScoredSuggestion(word, (score * 1000 * tWeight).toInt(), Source.LLM)
        }

        val histDict = userHistoryDictionary
        if (histDict != null && prevWord != null) {
            val bigramCompletions = histDict.getCompletions("", 10)
            for ((word, freq) in bigramCompletions) {
                if (blacklist != null && !blacklist.isSuggestionOk(word)) continue
                if (OffensiveWordFilter.isOffensive(word)) continue
                val bigramBoost = (histDict.getBigramBoost(prevWord, word) * 500).toInt()
                if (bigramBoost <= 0) continue
                val histScore = (freq * 20).coerceAtMost(400) + bigramBoost
                val key = word.lowercase()
                val existing = merged[key]
                if (existing != null) {
                    existing.score += histScore
                } else {
                    merged[key] = ScoredSuggestion(word, histScore, Source.HISTORY)
                }
            }
        }

        val sorted = merged.values.sortedByDescending { it.score }
        val newList = mutableListOf<Suggestion>()
        for (s in sorted.take(5)) {
            if (!newList.any { it.word.equals(s.word, ignoreCase = true) }) {
                newList.add(Suggestion(s.word, SuggestionKind.PREDICTION, s.score))
            }
        }
        return newList to false
    }

    // ── Background merge: full suggestion merge (used by spell checker callback) ─

    private fun buildMergedSuggestions(
        typedWord: String,
        textBefore: String,
        lmPredictions: List<Pair<String, Float>>,
        lmMode: String,
        lmConfidence: Float,
        spellResults: List<String>,
        blacklistArr: Array<String>,
        shiftState: ShiftState,
        acEnabled: Boolean,
        tWeight: Float,
    ): Pair<List<Suggestion>, Boolean> {
        val dictResults = gatherDictionarySuggestions(typedWord, textBefore, blacklistArr)
        return buildMergedSuggestionsWithDict(
            typedWord, textBefore, lmPredictions, lmMode, lmConfidence,
            spellResults, dictResults, blacklistArr, shiftState, acEnabled, tWeight,
        )
    }

    /**
     * Merge pre-gathered dictionary results with LLM + spell checker results.
     * This is the main merge path used by the timeout-aware pipeline.
     */
    private fun buildMergedSuggestionsWithDict(
        typedWord: String,
        textBefore: String,
        lmPredictions: List<Pair<String, Float>>,
        lmMode: String,
        lmConfidence: Float,
        spellResults: List<String>,
        dictSuggestions: Map<String, ScoredSuggestion>,
        blacklistArr: Array<String>,
        shiftState: ShiftState,
        acEnabled: Boolean,
        tWeight: Float,
    ): Pair<List<Suggestion>, Boolean> {
        val blacklist = suggestionBlacklist
        val merged = HashMap<String, ScoredSuggestion>(32)

        // Start with pre-gathered dictionary results
        for ((key, s) in dictSuggestions) {
            merged[key] = ScoredSuggestion(s.word, s.score, s.source, s.agreedByBoth)
        }

        // Layer in LLM results
        for ((word, score) in lmPredictions) {
            if (blacklist != null && !blacklist.isSuggestionOk(word)) continue
            if (OffensiveWordFilter.isOffensive(word)) continue
            if (word.equals(typedWord, ignoreCase = true)) continue
            val key = word.lowercase()
            val lmScore = (score * 1000 * tWeight).toInt()
            val existing = merged[key]
            if (existing != null) {
                existing.score += lmScore
                existing.agreedByBoth = true
            } else {
                merged[key] = ScoredSuggestion(word, lmScore, Source.LLM)
            }
        }

        // Layer in spell checker results
        for (word in spellResults) {
            if (blacklist != null && !blacklist.isSuggestionOk(word)) continue
            if (OffensiveWordFilter.isOffensive(word)) continue
            if (word.equals(typedWord, ignoreCase = true)) continue
            val key = word.lowercase()
            val existing = merged[key]
            if (existing != null) {
                existing.score += 100
                existing.agreedByBoth = true
            } else {
                merged[key] = ScoredSuggestion(word, 100, Source.SPELLCHECK)
            }
        }

        // ── Score, rank, decide autocorrect ─────────────────────────

        val sorted = merged.values.sortedByDescending { it.score }
        val topSuggestion = sorted.firstOrNull()

        val bothAgree = topSuggestion?.agreedByBoth == true
        // LatinIME uses a much higher bar — 0.5 fires on almost every word.
        val strongConfidence = lmConfidence > 0.85f
        val topFromLlm = topSuggestion?.source == Source.LLM
        val binDict = binaryDictionary
        val topInBinaryDict = topSuggestion != null &&
            (binDict?.isValidWord(topSuggestion.word) == true)
        // If the binary dictionary hasn't loaded yet we can't verify the typed word,
        // so treat it as valid (conservative — skip autocorrect rather than fire blind).
        val typedWordIsValid = binDict == null || binDict.isValidWord(typedWord)
        // For short words (≤5 chars) require both LLM and dict to agree — llmSaysAutocorrect
        // alone has too many false positives on common words like "hi", "they", "that".
        val shortWord = typedWord.length <= 5
        val shouldAC = acEnabled &&
            topSuggestion != null &&
            !topSuggestion.word.equals(typedWord, ignoreCase = true) &&
            !looksLikeUrlOrEmail(typedWord) &&
            !isMostlyCaps(typedWord) &&
            !typedWordIsValid &&
            (topFromLlm || topInBinaryDict) &&
            (bothAgree || (!shortWord && strongConfidence))

        val capTransform: (String) -> String = when (shiftState) {
            ShiftState.CAPS_LOCK -> { w -> w.uppercase() }
            ShiftState.SHIFTED -> { w -> w.replaceFirstChar { it.uppercase() } }
            ShiftState.UNSHIFTED -> { w -> w }
        }

        val typedEntry = if (typedWord.isNotBlank()) {
            Suggestion(typedWord, SuggestionKind.TYPED, 0)
        } else null

        val predList = mutableListOf<Suggestion>()
        for (s in sorted.take(4)) {
            val cappedWord = capTransform(s.word)
            if (!cappedWord.equals(typedWord, ignoreCase = true)) {
                predList.add(Suggestion(
                    word = cappedWord,
                    kind = s.source.toSuggestionKind(),
                    score = s.score,
                    agreedByMultipleSources = s.agreedByBoth,
                ))
            }
        }

        // Put the most actionable word in the CENTER slot (index 1) so AcceptSuggestion
        // always picks the right thing:
        //   Autocorrecting → [typed | correction | alt]  (center = correction)
        //   Not correcting  → [pred1 | typed | pred2]    (center = typed word confirmed)
        val newList = mutableListOf<Suggestion>()
        if (shouldAC) {
            typedEntry?.let { newList.add(it) }
            newList.addAll(predList)
        } else {
            val pred1 = predList.getOrNull(0)
            val pred2 = predList.getOrNull(1)
            if (pred1 != null) newList.add(pred1)
            typedEntry?.let { newList.add(it) }
            if (pred2 != null) newList.add(pred2)
        }

        if (newList.size > 5) {
            return newList.subList(0, 5).toList() to shouldAC
        }
        return newList to shouldAC
    }

    private fun extractPreviousWord(textBefore: String): String? {
        if (textBefore.isBlank()) return null
        val words = textBefore.trim().split(WHITESPACE_SPLITTER)
        return if (words.size >= 2) words[words.size - 2] else null
    }

    private fun previousWordBeforeCursor(ic: InputConnection?): String? {
        val text = ic?.getTextBeforeCursor(100, 0)?.toString() ?: return null
        val trimmed = text.trimEnd()
        val withoutCurrent = trimmed.dropLastWhile { it.isLetter() || it == '\'' }.trimEnd()
        return withoutCurrent.takeLastWhile { it.isLetter() || it == '\'' }.takeIf { it.isNotBlank() }
    }

    private data class ScoredSuggestion(
        val word: String,
        var score: Int,
        val source: Source,
        var agreedByBoth: Boolean = false,
    )

    private enum class Source {
        LLM, SPELLCHECK, USER_DICT, HISTORY, CONTACTS, PROXIMITY, BINARY_DICT, EMOJI, CLIPBOARD;

        fun toSuggestionKind(): SuggestionKind = when (this) {
            LLM -> SuggestionKind.CORRECTION
            SPELLCHECK -> SuggestionKind.CORRECTION
            USER_DICT -> SuggestionKind.USER_DICT
            HISTORY -> SuggestionKind.HISTORY
            CONTACTS -> SuggestionKind.CONTACTS
            PROXIMITY -> SuggestionKind.CORRECTION
            BINARY_DICT -> SuggestionKind.COMPLETION
            EMOJI -> SuggestionKind.EMOJI
            CLIPBOARD -> SuggestionKind.COMPLETION
        }
    }

    // ── SpellCheckerSessionListener ─────────────────────────────────

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}

    /**
     * Spell checker results arrive asynchronously. We store them and
     * either show them as placeholders (if LLM is still running) or
     * trigger a lightweight background re-merge (if LLM already finished).
     * We NEVER fire another LLM call from here.
     */
    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        try {
            val newSuggestions = mutableListOf<String>()
            val queriedWord = lastQueriedWord

            if (!results.isNullOrEmpty()) {
                for (sentenceInfo in results) {
                    for (i in 0 until sentenceInfo.suggestionsCount) {
                        val info = sentenceInfo.getSuggestionsInfoAt(i)
                        for (j in 0 until info.suggestionsCount) {
                            val s = info.getSuggestionAt(j)
                            if (s.isNotBlank() && !s.equals(queriedWord, ignoreCase = true)) {
                                newSuggestions.add(s)
                            }
                        }
                    }
                }
            }

            val distinctResults = newSuggestions.distinct()
            spellCheckerResults = distinctResults

            if (llmResultsPending) {
                // LLM still working — show spell checker results as placeholder
                mainHandler.post {
                    val blacklist = suggestionBlacklist
                    val newList = mutableListOf<Suggestion>()
                    if (queriedWord.isNotBlank() && queriedWord != "\u0000next") {
                        newList.add(Suggestion(queriedWord, SuggestionKind.TYPED))
                    }
                    for (s in distinctResults.take(4)) {
                        if (blacklist != null && !blacklist.isSuggestionOk(s)) continue
                        if (OffensiveWordFilter.isOffensive(s)) continue
                        if (!newList.any { it.word.equals(s, ignoreCase = true) }) {
                            newList.add(Suggestion(s, SuggestionKind.CORRECTION))
                        }
                    }
                    suggestions.clear()
                    suggestions.addAll(newList)
                    shouldAutocorrect.value = false
                }
            } else if (llmResults.isNotEmpty() && distinctResults.isNotEmpty()) {
                val gen = queryGeneration.get()
                val lmPreds = llmResults
                val lmMode = lastLMMode
                val lmConf = lastAutoCommitConfidence
                val textBefore = lastTextBeforeCursor
                val blacklistArr = suggestionBlacklist?.bannedWordsArray ?: emptyArray()
                val shiftState = currentShiftState
                val acEnabled = autocorrectEnabled
                val tWeight = transformerWeight

                dictExecutor.submit {
                    if (gen != queryGeneration.get()) return@submit
                    val pair = buildMergedSuggestions(
                        queriedWord, textBefore, lmPreds, lmMode, lmConf,
                        distinctResults, blacklistArr, shiftState, acEnabled, tWeight,
                    )
                    if (gen != queryGeneration.get()) return@submit
                    mainHandler.post {
                        if (gen != queryGeneration.get()) return@post
                        suggestions.clear()
                        suggestions.addAll(pair.first)
                        shouldAutocorrect.value = pair.second
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onGetSentenceSuggestions failed", e)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun currentWordBeforeCursor(ic: InputConnection): String {
        val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: return ""
        return before.takeLastWhile { it.isLetter() || it == '\'' }
    }

    /**
     * Gets the current word before cursor. We trust the editor — no wordComposer fallback.
     * Falling back to wordComposer when the editor is out of sync is exactly how ghost
     * spans get resurrected and exponential duplication occurs.
     */
    private fun currentWordBeforeCursorRobust(ic: InputConnection): String {
        return currentWordBeforeCursor(ic)
    }

    private fun matchCapitalization(original: String, replacement: String): String {
        if (original.isEmpty() || replacement.isEmpty()) return replacement
        return when {
            original.all { it.isUpperCase() } -> replacement.uppercase()
            original[0].isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
            else -> replacement.lowercase()
        }
    }

    private fun cancelPending() {
        pendingQuery?.let { mainHandler.removeCallbacks(it) }
        pendingQuery = null
    }

    // ── Recorrection ──────────────────────────────────────────────────

    fun onCursorMovedTo(ic: InputConnection, selStart: Int, selEnd: Int) {
        // Any external cursor movement guarantees the composing span is broken.
        // We MUST terminate our internal composing state immediately.
        if (isComposing) {
            isComposing = false
            wordComposer.reset()
        }

        if (selStart != selEnd) {
            exitRecorrection()
            return
        }
        if (!predictionsEnabled) return

        val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(50, 0)?.toString() ?: ""
        if (before.isEmpty()) {
            exitRecorrection()
            return
        }

        val wordBeforeCursor = before.takeLastWhile { it.isLetter() || it == '\'' }
        val wordAfterCursor = after.takeWhile { it.isLetter() || it == '\'' }
        val fullWord = wordBeforeCursor + wordAfterCursor

        if (fullWord.length < 2) {
            exitRecorrection()
            return
        }

        val wordStart = selStart - wordBeforeCursor.length
        val wordEnd = selStart + wordAfterCursor.length

        if (fullWord == recorrectionWord && wordStart == recorrectionStart) return

        recorrectionWord = fullWord
        recorrectionStart = wordStart
        recorrectionEnd = wordEnd
        lastQueriedWord = ""

        queryRecorrection(ic, fullWord)
    }

    private fun queryRecorrection(ic: InputConnection, word: String) {
        // Do NOT touch wordComposer here. Recorrection operates on committed text
        // (isComposing = false). If we set wordComposer = the recorrection word and the
        // user then types a character instead of tapping a suggestion, onCharCommitted
        // appends to the stale word and updateComposingText inserts the full compound
        // string at the cursor — duplicating the committed text. applyRecorrection uses
        // recorrectionWord/Start/End directly, not wordComposer, so the set was vestigial.

        spellCheckerResults = emptyList()
        llmResults = emptyList()
        lastLMMode = "clueless"
        lastAutoCommitConfidence = 0f
        llmResultsPending = true

        try {
            spellCheckerSession?.getSentenceSuggestions(
                arrayOf(TextInfo(word)), 5,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Recorrection spell check failed", e)
        }

        val textBefore = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
        lastTextBeforeCursor = textBefore

        val gen = queryGeneration.incrementAndGet()
        val bridge = predictionBridge
        val blacklistArr = suggestionBlacklist?.bannedWordsArray ?: emptyArray()
        val shiftState = currentShiftState
        val acEnabled = autocorrectEnabled
        val tWeight = transformerWeight
        val acThreshold = autocorrectThreshold

        if (bridge != null && bridge.isReady() && !transformerTempDisabled) {
            dictExecutor.submit {
                try {
                    if (gen != queryGeneration.get()) return@submit

                    val lmFuture = lmExecutor.submit<LmResult> {
                        if (gen != queryGeneration.get()) return@submit LmResult.EMPTY

                        val prevWordsList = if (textBefore.isNotBlank()) {
                            val allWords = textBefore.trim().split(WHITESPACE_SPLITTER)
                                .filter { it.isNotEmpty() }
                            val withoutPartial = if (word.isNotEmpty()) {
                                val trimmed = textBefore.trimEnd()
                                if (trimmed.endsWith(word)) allWords.dropLast(1) else allWords
                            } else allWords
                            withoutPartial.takeLast(3)
                        } else emptyList()

                        if (gen != queryGeneration.get()) return@submit LmResult.EMPTY

                        val result = bridge.getAllPredictions(
                            textBeforeCursor = textBefore,
                            autocorrectThreshold = acThreshold,
                            bannedWords = blacklistArr,
                            previousWords = prevWordsList,
                        )

                        if (gen != queryGeneration.get()) return@submit LmResult.EMPTY
                        LmResult(result.predictions, result.mode, result.autoCommitConfidence)
                    }

                    val dictResults = gatherDictionarySuggestions(word, textBefore, blacklistArr)

                    if (gen != queryGeneration.get()) {
                        lmFuture.cancel(false)
                        return@submit
                    }

                    var lmResult = LmResult.EMPTY
                    try {
                        lmResult = lmFuture.get(lmTimeoutMs, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        lmFuture.cancel(false)
                        Log.w(TAG, "Recorrection LLM timed out")
                    } catch (e: Exception) {
                        Log.w(TAG, "Recorrection LLM failed", e)
                    }

                    if (gen != queryGeneration.get()) return@submit

                    llmResults = lmResult.predictions
                    lastLMMode = lmResult.mode
                    lastAutoCommitConfidence = lmResult.confidence
                    llmResultsPending = false

                    val spellResults = spellCheckerResults
                    val pair = buildMergedSuggestionsWithDict(
                        word, textBefore, lmResult.predictions, lmResult.mode,
                        lmResult.confidence, spellResults, dictResults, blacklistArr,
                        shiftState, acEnabled, tWeight,
                    )

                    if (gen != queryGeneration.get()) return@submit

                    mainHandler.post {
                        if (gen != queryGeneration.get()) return@post
                        suggestions.clear()
                        suggestions.addAll(pair.first)
                        shouldAutocorrect.value = pair.second
                    }
                } catch (e: Throwable) {
                    llmResultsPending = false
                    Log.w(TAG, "Recorrection query failed", e)
                }
            }
        } else {
            llmResultsPending = false
            dictExecutor.submit {
                if (gen != queryGeneration.get()) return@submit
                val pair = buildMergedSuggestions(
                    word, textBefore, emptyList(), "clueless", 0f,
                    emptyList(), blacklistArr, shiftState, acEnabled, tWeight,
                )
                if (gen != queryGeneration.get()) return@submit
                mainHandler.post {
                    if (gen != queryGeneration.get()) return@post
                    suggestions.clear()
                    suggestions.addAll(pair.first)
                    shouldAutocorrect.value = pair.second
                }
            }
        }
    }

    fun applyRecorrection(index: Int, ime: IMEService) {
        if (!isInRecorrectionMode) return
        if (index < 0 || index >= suggestions.size) return

        val selected = suggestions[index]
        val ic = ime.currentInputConnection ?: return
        val original = recorrectionWord ?: return

        ic.beginBatchEdit()
        ic.setSelection(recorrectionStart, recorrectionEnd)
        val replacement = matchCapitalization(original, selected.word)
        ic.commitText(replacement, 1)
        ic.endBatchEdit()

        val prevWord = wordComposer.previousWord
        userHistoryDictionary?.recordWord(replacement, prevWord)

        Log.d(TAG, "Recorrection: \"$original\" → \"$replacement\"")
        exitRecorrection()
        clearSuggestions()
    }

    fun exitRecorrection() {
        recorrectionWord = null
        recorrectionStart = -1
        recorrectionEnd = -1
    }

    // ── Gesture typing ──────────────────────────────────────────────

    fun onGestureComplete(ime: IMEService, result: GestureResult) {
        val ic = ime.currentInputConnection ?: return
        if (result.decodedWord.length < 2) return

        gestureResultPending = true
        val textBefore = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
        lastTextBeforeCursor = textBefore

        suggestions.clear()
        suggestions.add(Suggestion(result.decodedWord, SuggestionKind.TYPED, 0))
        shouldAutocorrect.value = false

        val bridge = predictionBridge
        if (bridge != null && bridge.isReady()) {
            val blacklist = suggestionBlacklist?.bannedWordsArray ?: emptyArray()
            val swipeText = "$textBefore${result.decodedWord}"

            lmExecutor.submit {
                try {
                    val llmResult = bridge.getAllPredictions(
                        textBeforeCursor = swipeText,
                        autocorrectThreshold = autocorrectThreshold,
                        bannedWords = blacklist,
                        composeX = result.pathXCoordinates,
                        composeY = result.pathYCoordinates,
                        inputMode = 1,
                    )
                    mainHandler.post {
                        try {
                            gestureResultPending = false
                            val cancelled = gestureCancelled
                            gestureCancelled = false
                            if (cancelled) return@post

                            val newList = mutableListOf<Suggestion>()

                            for ((word, score) in llmResult.predictions.take(5)) {
                                if (OffensiveWordFilter.isOffensive(word)) continue
                                newList.add(Suggestion(
                                    word = word,
                                    kind = SuggestionKind.COMPLETION,
                                    score = (score * 1000).toInt(),
                                ))
                            }

                            if (newList.isNotEmpty()) {
                                val topWord = newList[0].word
                                ic.commitText(topWord, 1)
                                ic.commitText(" ", 1)
                                ime.ignoreNextCursorMove()

                                val prevWord = previousWordBeforeCursor(ic)
                                userHistoryDictionary?.recordWord(topWord, prevWord)
                            }

                            suggestions.clear()
                            suggestions.addAll(newList)
                            shouldAutocorrect.value = false
                        } catch (e: Exception) {
                            Log.w(TAG, "Gesture result merge failed", e)
                        }
                    }
                } catch (e: Throwable) {
                    gestureResultPending = false
                    gestureCancelled = false
                    Log.w(TAG, "Gesture LLM prediction failed", e)
                    mainHandler.post {
                        ic.commitText(result.decodedWord, 1)
                        ic.commitText(" ", 1)
                    }
                }
            }
        } else {
            gestureResultPending = false
            gestureCancelled = false
            ic.commitText(result.decodedWord, 1)
            ic.commitText(" ", 1)
        }
    }

    fun onGestureSuggestionSelected(index: Int, ime: IMEService) {
        if (index < 0 || index >= suggestions.size) return
        val selected = suggestions[index]
        val ic = ime.currentInputConnection ?: return

        val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: return
        val wordAndSpace = before.takeLastWhile { it != ' ' }.let { w ->
            if (before.length > w.length && before[before.length - w.length - 1] == ' ') {
                w
            } else w
        }

        val toDelete = before.trimEnd().takeLastWhile { it.isLetter() || it == '\'' }
        if (toDelete.isNotEmpty()) {
            ic.beginBatchEdit()
            val deleteLen = toDelete.length + if (before.endsWith(" ")) 1 else 0
            ic.deleteSurroundingText(deleteLen, 0)
            ic.commitText(selected.word, 1)
            ic.commitText(" ", 1)
            ic.endBatchEdit()
            ime.ignoreNextCursorMove()

            val prevWord = previousWordBeforeCursor(ic)
            userHistoryDictionary?.recordWord(selected.word, prevWord)
        }

        clearSuggestions()
    }

    // ── Public API for external char commits ────────────────────────

    fun onCharCommitted(char: Char, ime: IMEService? = null, x: Int = -1, y: Int = -1) {
        if (gestureResultPending) gestureCancelled = true
        exitRecorrection()
        lastDoubleSpacePeriod = false

        if (PredictionEngine.isWordSeparator(char)) {
            wordComposer.reset()
            phantomSpacePending = false
            if (isComposing) {
                ime?.currentInputConnection?.finishComposingText()
                isComposing = false
            }
        } else if (char.isLetter()) {
            wordComposer.addChar(char, x, y)
            // Close the undo-autocorrect window the moment the user begins the next word.
            lastAutocorrect = null
        } else {
            // Non-letter, non-separator (apostrophe, digit, @, #, etc.):
            // The CommitText path already called finishComposing() before reaching here,
            // so the composing span is gone and isComposing=false. If we addChar() here,
            // wordComposer accumulates e.g. "don'" and the next letter 't' then calls
            // updateComposingText("don't") — which inserts "don't" as a new composing span
            // right on top of the already-committed "don'", duplicating it as "don'don't".
            // Resetting ensures the next letter always starts a fresh composing span of length 1.
            wordComposer.reset()
            lastAutocorrect = null
            if (isComposing) {
                ime?.currentInputConnection?.finishComposingText()
                isComposing = false
            }
        }
    }

    fun isComposingActive(): Boolean = isComposing && composingEnabled

    fun updateComposingText(ic: InputConnection) {
        if (!composingEnabled || suppressPredictions) {
            if (isComposing) {
                ic.finishComposingText()
                isComposing = false
            }
            return
        }

        val word = wordComposer.typedWord
        if (word.isEmpty()) {
            if (isComposing) {
                ic.finishComposingText()
                isComposing = false
            }
            return
        }

        ic.setComposingText(word, 1)
        isComposing = true
    }

    fun finishComposing(ic: InputConnection?) {
        if (isComposing) {
            ic?.finishComposingText()
            isComposing = false
            wordComposer.reset()
        }
    }

    fun consumePhantomSpace(): Boolean {
        if (phantomSpacePending) {
            phantomSpacePending = false
            return true
        }
        return false
    }

    fun getClipboardSuggestion(): String? {
        if (suppressPredictions) return null
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val text = clip.getItemAt(0).text?.toString() ?: return null
            val trimmed = text.trim()
            if (trimmed.length in 1..50 && !trimmed.contains('\n')) {
                return trimmed
            }
        } catch (_: Exception) {}
        return null
    }

    fun onCharDeleted() {
        if (gestureResultPending) gestureCancelled = true
        exitRecorrection()
        wordComposer.deleteChar()
    }

    /**
     * LatinIME-style backspace: if composing is active, shrink the composing span directly
     * instead of committing and re-inserting. Returns true if handled (caller should skip
     * finishComposing + KEYCODE_DEL).
     */
    fun handleBackspaceWhileComposing(ic: InputConnection): Boolean {
        if (!isComposing || !composingEnabled || suppressPredictions) return false

        // FAIL FAST ON DESYNC: Ensure we are perfectly synced before modifying the span.
        val currentEditorWord = currentWordBeforeCursorRobust(ic)
        if (currentEditorWord != wordComposer.typedWord) {
            ic.finishComposingText()
            isComposing = false
            wordComposer.reset()
            return false
        }

        if (gestureResultPending) gestureCancelled = true
        exitRecorrection()

        val deleted = wordComposer.deleteChar() ?: return false
        phantomSpacePending = false
        if (wordComposer.isEmpty) {
            ic.setComposingText("", 1)
            isComposing = false
        } else {
            ic.setComposingText(wordComposer.typedWord, 1)
        }
        return true
    }

    fun clearAutocorrectHistory() {
        lastAutocorrect = null
    }

    companion object {
        val WORD_SEPARATORS = setOf(
            ' ', '.', ',', '!', '?', ';', ':', '\n', '\t', ')', ']', '}',
        )

        fun isWordSeparator(c: Char): Boolean = c in WORD_SEPARATORS
    }
}
