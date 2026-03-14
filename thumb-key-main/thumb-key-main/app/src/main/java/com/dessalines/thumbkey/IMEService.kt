package com.dessalines.thumbkey

import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dessalines.thumbkey.db.DEFAULT_ANIMATION_SPEED
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_DAMPING
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_DRAG_SCALE
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_MAX_COUNT
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_MAX_TIME
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_IMPACT_VELOCITY
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_REALISTIC_GRAVITY
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_SPEED
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_STEERING
import com.dessalines.thumbkey.db.DEFAULT_DISABLE_FULLSCREEN_EDITOR
import com.dessalines.thumbkey.prediction.BinaryDictionaryBridge
import com.dessalines.thumbkey.prediction.ContactsDictionaryProvider
import com.dessalines.thumbkey.prediction.LanguageModelSwitcher
import com.dessalines.thumbkey.prediction.ModelPaths
import com.dessalines.thumbkey.prediction.PersonalizationTrainer
import com.dessalines.thumbkey.prediction.PredictionBridge
import com.dessalines.thumbkey.prediction.PredictionEngine
import com.dessalines.thumbkey.prediction.SuggestionBlacklist
import com.dessalines.thumbkey.prediction.TrainingLog
import com.dessalines.thumbkey.prediction.UserDictionaryObserver
import com.dessalines.thumbkey.prediction.UserHistoryDictionary
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dessalines.thumbkey.ui.components.keyboard.FloatingChar
import com.dessalines.thumbkey.ui.components.keyboard.FloatingCharOverlay
import com.dessalines.thumbkey.ui.components.keyboard.FracturingChar
import com.dessalines.thumbkey.ui.theme.ThumbkeyTheme
import com.dessalines.thumbkey.utils.KeyboardDefinition
import com.dessalines.thumbkey.utils.KeyboardLayout
import com.dessalines.thumbkey.utils.TAG
import com.dessalines.thumbkey.utils.ThumbKeyClipboardManager
import com.dessalines.thumbkey.utils.toBool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IMEService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private fun setupView(): ComposeKeyboardView {
        val app = application as ThumbkeyApplication
        val settingsRepo = app.appSettingsRepository
        val clipboardRepo = app.clipboardRepository

        val layoutIndex = settingsRepo.appSettings.value?.keyboardLayout
        if (layoutIndex != null) {
            currentKeyboardDefinition = KeyboardLayout.entries[layoutIndex].keyboardDefinition
        }

        val view = ComposeKeyboardView(this, settingsRepo, clipboardRepo)
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        view.let {
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeViewModelStoreOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
        }

        if (animationOverlay == null) {
            setupAnimationOverlay()
        }

        return view
    }

    private fun setupAnimationOverlay() {
        removeAnimationOverlay()

        val token = window?.window?.decorView?.windowToken ?: return
        val overlay = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IMEService)
            setViewTreeViewModelStoreOwner(this@IMEService)
            setViewTreeSavedStateRegistryOwner(this@IMEService)
            setContent {
                val app = application as ThumbkeyApplication
                // observeAsState keeps the overlay reactive — physics param changes from
                // settings update the running overlay without requiring a keyboard cycle.
                val settings by app.appSettingsRepository.appSettings.observeAsState()
                ThumbkeyTheme(settings = settings) {
                    FloatingCharOverlay(
                        floatingChars = floatingChars,
                        fracturingChars = fracturingChars,
                        onFractureComplete = { id -> fracturingChars.removeAll { it.id == id } },
                        realisticGravityEnabled = (settings?.floatingCharRealisticGravity ?: DEFAULT_FLOATING_CHAR_REALISTIC_GRAVITY).toBool(),
                        animationSpeed = settings?.animationSpeed ?: DEFAULT_ANIMATION_SPEED,
                        cursorScreenX = cursorScreenX.value,
                        cursorScreenY = cursorScreenY.value,
                        maxSpeed = (settings?.floatingCharSpeed ?: DEFAULT_FLOATING_CHAR_SPEED).toFloat(),
                        steerAccel = (settings?.floatingCharSteering ?: DEFAULT_FLOATING_CHAR_STEERING) * 100f,
                        velocityDamping = (settings?.floatingCharDamping ?: DEFAULT_FLOATING_CHAR_DAMPING) / 10f,
                        dragVelScale = (settings?.floatingCharDragScale ?: DEFAULT_FLOATING_CHAR_DRAG_SCALE) / 100f,
                        maxTime = (settings?.floatingCharMaxTime ?: DEFAULT_FLOATING_CHAR_MAX_TIME) / 10f,
                    )
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.token = token
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(overlay, params)
            animationOverlay = overlay
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add animation overlay window", e)
        }
    }

    private fun removeAnimationOverlay() {
        animationOverlay?.let { overlay ->
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(overlay)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove animation overlay", e)
            }
        }
        animationOverlay = null
    }

    var currentKeyboardDefinition: KeyboardDefinition? = null
    private var clipboardManager: ThumbKeyClipboardManager? = null

    private var modelSwitcher: LanguageModelSwitcher? = null

    val predictionEngine: PredictionEngine by lazy { PredictionEngine(this) }

    private var userDictionary: UserDictionaryObserver? = null
    private var suggestionBlacklist: SuggestionBlacklist? = null
    private var trainingLog: TrainingLog? = null
    private var personalizationTrainer: PersonalizationTrainer? = null
    private var userHistoryDictionary: UserHistoryDictionary? = null
    private var contactsDictionary: ContactsDictionaryProvider? = null
    private var binaryDictionary: BinaryDictionaryBridge? = null

    var touchThroughEnabled = false
    var zeroHeightInsets = false
    var floatingCharEnabled = false
    val suggestionBarRect = Rect()
    val keyboardKeysRect = Rect()

    val floatingChars = mutableStateListOf<FloatingChar>()
    val fracturingChars = mutableStateListOf<FracturingChar>()

    // Last key impact for thumb-velocity inference (non-swipe taps only)
    private var lastImpactX: Float = Float.NaN
    private var lastImpactY: Float = Float.NaN
    private var lastImpactNanos: Long = 0L
    var suggestionBarScreenCenterX: Float = Float.NaN
    var suggestionBarScreenCenterY: Float = Float.NaN
    private var animationOverlay: ComposeView? = null

    // Cursor position in screen coordinates, updated via CursorAnchorInfo
    val cursorScreenX = androidx.compose.runtime.mutableStateOf(Float.NaN)
    val cursorScreenY = androidx.compose.runtime.mutableStateOf(Float.NaN)

    fun updateTouchableRegion() {
        window?.window?.decorView?.requestLayout()
    }

    fun emitFloatingChar(
        text: String,
        startX: Float,
        startY: Float,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
    ) {
        if (!floatingCharEnabled) return
        val settings = (application as ThumbkeyApplication).appSettingsRepository.appSettings.value
        val maxCount = settings?.floatingCharMaxCount ?: DEFAULT_FLOATING_CHAR_MAX_COUNT
        if (floatingChars.size >= maxCount) return

        var finalVelX = velocityX
        var finalVelY = velocityY

        val nowNanos = System.nanoTime()
        val impactVelocityEnabled =
            (settings?.floatingCharImpactVelocity ?: DEFAULT_FLOATING_CHAR_IMPACT_VELOCITY).toBool()

        if (impactVelocityEnabled && velocityX == 0f && velocityY == 0f && !lastImpactX.isNaN()) {
            val deltaNanos = nowNanos - lastImpactNanos
            val deltaSec = deltaNanos / 1_000_000_000f
            if (deltaSec in 0f..1f && deltaSec > 0f) {
                finalVelX = (startX - lastImpactX) / deltaSec
                finalVelY = (startY - lastImpactY) / deltaSec
            }
        }

        lastImpactX = startX
        lastImpactY = startY
        lastImpactNanos = nowNanos

        floatingChars.add(
            FloatingChar(
                id = nowNanos,
                text = text,
                startX = startX,
                startY = startY,
                initVelX = finalVelX,
                initVelY = finalVelY,
            ),
        )
    }

    fun emitFloatingCharsSequential(
        chars: List<String>,
        startX: Float,
        startY: Float,
        perCharDelayMs: Long = 30,
    ) {
        if (!floatingCharEnabled) return
        val maxCount = (application as ThumbkeyApplication).appSettingsRepository.appSettings.value
            ?.floatingCharMaxCount ?: DEFAULT_FLOATING_CHAR_MAX_COUNT
        val slots = (maxCount - floatingChars.size).coerceAtLeast(0)
        val baseTime = System.nanoTime()
        chars.take(slots).forEachIndexed { index, ch ->
            floatingChars.add(
                FloatingChar(
                    id = baseTime + index,
                    text = ch,
                    startX = startX,
                    startY = startY,
                    delayMs = index * perCharDelayMs,
                ),
            )
        }
    }

    fun emitPdcShootdown(deletedText: String, startX: Float, startY: Float) {
        if (!floatingCharEnabled) return
        val app = application as ThumbkeyApplication
        val maxCount = app.appSettingsRepository.appSettings.value?.floatingCharMaxCount ?: DEFAULT_FLOATING_CHAR_MAX_COUNT

        if (fracturingChars.size >= maxCount + 10) return

        val validChars = deletedText.replace(Regex("\\s+"), "")
        val slotsRemaining = (maxCount + 10 - fracturingChars.size).coerceAtLeast(0)
        val charsToProcess = validChars.take(minOf(slotsRemaining, 15))

        if (charsToProcess.isEmpty()) return

        val safeStartX = if (startX.isNaN()) window?.window?.decorView?.width?.div(2)?.toFloat() ?: 500f else startX
        val safeStartY = if (startY.isNaN()) window?.window?.decorView?.height?.div(2)?.toFloat() ?: 1000f else startY

        val baseTime = System.nanoTime()
        val style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // Reverse so character closest to cursor (rightmost) spawns at baseX; spread horizontally
        val reversedChars = charsToProcess.reversed()
        val isMultiChar = reversedChars.length > 1
        val chaosMultiplier = if (isMultiChar) 1.4f else 1f
        val charSpacing = 32f // Average font width so characters don't overlap

        reversedChars.forEachIndexed { index, ch ->
            val spawnX = safeStartX - (index * charSpacing)
            fracturingChars.add(
                FracturingChar(
                    id = baseTime + index,
                    text = ch.toString(),
                    startX = spawnX,
                    startY = safeStartY,
                    style = style,
                    chaosMultiplier = chaosMultiplier,
                )
            )
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)

        if (zeroHeightInsets) {
            val viewHeight = window?.window?.decorView?.height ?: 0
            if (viewHeight > 0) {
                outInsets.contentTopInsets = viewHeight
                outInsets.visibleTopInsets = viewHeight
            }
        }

        if (touchThroughEnabled || zeroHeightInsets) {
            val region = Region()
            if (!suggestionBarRect.isEmpty) region.union(suggestionBarRect)
            if (!keyboardKeysRect.isEmpty) region.union(keyboardKeysRect)
            if (!region.isEmpty) {
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                outInsets.touchableRegion.set(region)
            }
        }
    }

    private var lastLayoutIndex: Int? = null

    /**
     * Called every time the keyboard is brought up.
     * Hot-reloads AI settings so changes take effect without app restart.
     * Only rebuilds the view if the layout changed or it's the first time.
     */
    override fun onFinishInput() {
        try {
            // Wipe all transient composing/autocorrect state when the current field ends.
            // Without this, state leaks into the next field when the keyboard stays visible
            // (e.g. tapping from one EditText to another in the same app). onWindowHidden
            // is not called in that case so this is the only reliable cleanup point.
            predictionEngine.resetForNewInput()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reset prediction engine on finish input", e)
        }
        super.onFinishInput()
    }

    override fun onStartInput(
        attribute: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInput(attribute, restarting)
        try {
            // Belt-and-suspenders reset: onFinishInput should have already cleaned up, but
            // if it was skipped (IME restart, restarting=true, etc.) this ensures the engine
            // never sees wordComposer/isComposing state from a previous input session.
            predictionEngine.resetForNewInput()
            predictionEngine.reloadSettings(this)

            val inputType = attribute?.inputType ?: 0
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val isPasswordField = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            val isNoSuggestions = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0

            predictionEngine.suppressPredictions = isPasswordField || isNoSuggestions
            if (isPasswordField) {
                predictionEngine.suppressLearning = true
                predictionEngine.clearSuggestions()
            } else {
                predictionEngine.suppressLearning = false
            }

            predictionEngine.open()

            currentKeyboardDefinition?.let { def ->
                predictionEngine.updateProximityModel(def.modes.main)
            }

            val newLocale = resources.configuration.locales[0]
            if (modelSwitcher?.getCurrentLocale() != newLocale) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val bridge = modelSwitcher?.switchToLocale(newLocale)
                    bridge?.let { predictionEngine.predictionBridge = it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize prediction engine on input start", e)
        }

        // Always set the input view: the system may have destroyed it to save memory when
        // the keyboard was hidden. Skipping setInputView() leaves an invisible keyboard.
        val app = application as ThumbkeyApplication
        val currentLayoutIndex = app.appSettingsRepository.appSettings.value?.keyboardLayout
        lastLayoutIndex = currentLayoutIndex
        val view = this.setupView()
        this.setInputView(view)

        // Request continuous cursor position updates. Without this, the target app rarely
        // sends CursorAnchorInfo, leaving cursorScreenX/Y stale during passive movement.
        currentInputConnection?.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE,
        )
    }

    // Lifecycle Methods
    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    private fun handleLifecycleEvent(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)

    override val lifecycle = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val app = application as ThumbkeyApplication
        clipboardManager = ThumbKeyClipboardManager(this, app.clipboardRepository)
        clipboardManager?.startListening()
        clipboardManager?.clearExpired()

        userDictionary = UserDictionaryObserver(this)
        predictionEngine.userDictionary = userDictionary

        suggestionBlacklist = SuggestionBlacklist(this)
        predictionEngine.suggestionBlacklist = suggestionBlacklist

        trainingLog = TrainingLog(this)
        predictionEngine.trainingLog = trainingLog

        if (PredictionBridge.isSafeToLoad(this)) {
            personalizationTrainer = PersonalizationTrainer(this, trainingLog!!)
            predictionEngine.personalizationTrainer = personalizationTrainer
        }

        userHistoryDictionary = UserHistoryDictionary(this)
        predictionEngine.userHistoryDictionary = userHistoryDictionary

        contactsDictionary = ContactsDictionaryProvider(this)
        predictionEngine.contactsDictionary = contactsDictionary
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contactsDictionary?.refresh()
                Log.d(TAG, "Contacts dictionary loaded: ${contactsDictionary?.size} words")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load contacts dictionary", e)
            }
        }

        binaryDictionary = BinaryDictionaryBridge(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                binaryDictionary?.load()
                withContext(Dispatchers.Main) {
                    predictionEngine.binaryDictionary = binaryDictionary
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load binary dictionary", e)
            }
        }

        predictionEngine.reloadSettings(this)

        if (PredictionBridge.isSafeToLoad(this)) {
            modelSwitcher = LanguageModelSwitcher(this)
            lifecycleScope.launch {
                try {
                    val bridge = modelSwitcher?.switchToLocale(resources.configuration.locales[0])
                    bridge?.let { predictionEngine.predictionBridge = it }
                    if (bridge != null) Log.i(TAG, "FUTO LanguageModel loaded successfully")
                    else Log.w(TAG, "FUTO Model failed to load. LLM prediction disabled.")
                } catch (e: Throwable) {
                    Log.e(TAG, "Error during model initialization. LLM prediction disabled.", e)
                }
            }
        } else {
            Log.w(TAG, "CRASH GUARD: Skipping LLM initialization")
        }
    }

    override fun onDestroy() {
        trainingLog?.saveToDisk()
        userHistoryDictionary?.saveToDisk()

        predictionEngine.predictionBridge = null
        predictionEngine.userDictionary = null
        predictionEngine.suggestionBlacklist = null
        predictionEngine.trainingLog = null
        predictionEngine.personalizationTrainer = null
        predictionEngine.userHistoryDictionary = null
        predictionEngine.contactsDictionary = null
        predictionEngine.binaryDictionary = null
        predictionEngine.close()

        userDictionary?.unregister()
        userDictionary = null
        suggestionBlacklist = null
        trainingLog = null
        personalizationTrainer = null
        userHistoryDictionary = null
        contactsDictionary = null
        binaryDictionary = null

        modelSwitcher?.closeAll()
        modelSwitcher = null

        try {
            org.futo.inputmethod.latin.xlm.LanguageModel.getInstance().forceClose()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not force-close native model", e)
        }

        clipboardManager?.stopListening()
        clipboardManager = null
        removeAnimationOverlay()
        super.onDestroy()
        handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    // Cursor update Methods
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)

        val mx = cursorAnchorInfo.insertionMarkerHorizontal
        val my = cursorAnchorInfo.insertionMarkerTop
        if (!mx.isNaN() && mx != Float.MAX_VALUE && !my.isNaN() && my != Float.MAX_VALUE) {
            val pts = floatArrayOf(mx, my)
            cursorAnchorInfo.matrix?.mapPoints(pts)
            cursorScreenX.value = pts[0]
            cursorScreenY.value = pts[1]
        }

        cursorMoved =
            if (ignoreCursorMove) {
                ignoreCursorMove = false
                false
            } else {
                cursorAnchorInfo.selectionStart != selectionStart ||
                    cursorAnchorInfo.selectionEnd != selectionEnd
            }

        // Fix for chat apps backloading messages. Detect if the app suddenly cleared the text field.
        if (cursorMoved && cursorAnchorInfo.selectionStart == 0 && cursorAnchorInfo.selectionEnd == 0) {
            val ic = currentInputConnection
            if (ic?.getTextBeforeCursor(1, 0).isNullOrEmpty() && ic?.getTextAfterCursor(1, 0).isNullOrEmpty()) {
                predictionEngine.resetForNewInput()
            }
        }

        currentKeyboardDefinition?.settings?.textProcessor?.handleCursorUpdate(
            this,
            selectionStart,
            selectionEnd,
            cursorAnchorInfo.selectionStart,
            cursorAnchorInfo.selectionEnd,
        )

        // Recorrection: only when cursor moved by the user (not our code),
        // and not during active composing (which fires spurious cursor events
        // that would create a feedback loop of LLM queries).
        if (cursorMoved && !predictionEngine.isComposingActive()) {
            try {
                val ic = currentInputConnection
                if (ic != null) {
                    predictionEngine.onCursorMovedTo(
                        ic,
                        cursorAnchorInfo.selectionStart,
                        cursorAnchorInfo.selectionEnd,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recorrection cursor check failed", e)
            }
        }

        selectionStart = cursorAnchorInfo.selectionStart
        selectionEnd = cursorAnchorInfo.selectionEnd
    }

    override fun onUpdateExtractingVisibility(ei: EditorInfo) {
        val settingsRepo = (application as ThumbkeyApplication).appSettingsRepository
        val settings = settingsRepo.appSettings.getValue()
        if ((settings?.disableFullscreenEditor ?: DEFAULT_DISABLE_FULLSCREEN_EDITOR).toBool()) {
            ei.imeOptions =
                ei.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        super.onUpdateExtractingVisibility(ei)
    }

    fun didCursorMove(): Boolean = cursorMoved

    fun ignoreNextCursorMove() {
        ignoreCursorMove = true
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // onStartInput fires before the IME window is visible, so windowToken can be null
        // and the overlay silently fails to create. Retry here where the token is guaranteed.
        if (animationOverlay == null) {
            setupAnimationOverlay()
        }
    }

    override fun onWindowHidden() {
        try {
            predictionEngine.clearSuggestions()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear suggestions on window hidden", e)
        }
        currentKeyboardDefinition?.settings?.textProcessor?.handleFinishInput(this)
        super.onWindowHidden()
    }

    private var ignoreCursorMove: Boolean = false
    private var cursorMoved: Boolean = false
    private var selectionStart: Int = 0
    private var selectionEnd: Int = 0

    // ViewModelStore Methods
    override val viewModelStore = ViewModelStore()

    // SaveStateRegistry Methods
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry =
        savedStateRegistryController.savedStateRegistry
}
