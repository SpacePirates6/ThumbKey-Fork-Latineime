package com.dessalines.thumbkey

import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
import com.dessalines.thumbkey.db.DEFAULT_TAP_TO_PLACE_ENABLED
import com.dessalines.thumbkey.prediction.BinaryDictionaryBridge
import com.dessalines.thumbkey.prediction.ContactsDictionaryProvider
import com.dessalines.thumbkey.prediction.LanguageModelSwitcher
import com.dessalines.thumbkey.prediction.ModelPaths
import com.dessalines.thumbkey.prediction.PersonalizationTrainer
import com.dessalines.thumbkey.prediction.PredictionBridge
import com.dessalines.thumbkey.prediction.PredictionEngine
import com.dessalines.thumbkey.prediction.SuggestionBlacklist
import com.dessalines.thumbkey.prediction.TrainingLog
import com.dessalines.thumbkey.prediction.TrainingWorker
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WHITESPACE_REGEX = Regex("\\s+")

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

    private fun getRealScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point.x to point.y
        }
    }

    /**
     * Returns the device's display rounded-corner radius in pixels.
     * Queries the largest of the four corners (phones are usually symmetric)
     * on API 31+. Falls back to a sensible default on older devices.
     */
    private fun getDisplayCornerRadiusPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val insets = wm.currentWindowMetrics.windowInsets
                val positions = intArrayOf(
                    android.view.RoundedCorner.POSITION_TOP_LEFT,
                    android.view.RoundedCorner.POSITION_TOP_RIGHT,
                    android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
                    android.view.RoundedCorner.POSITION_BOTTOM_RIGHT,
                )
                var maxRadius = 0
                for (p in positions) {
                    val r = insets.getRoundedCorner(p)?.radius ?: 0
                    if (r > maxRadius) maxRadius = r
                }
                if (maxRadius > 0) return maxRadius
            } catch (_: Exception) { /* fall through to default */ }
        }
        // Fallback: ~32 dp, typical for modern Android phones that don't expose the API
        val density = resources.displayMetrics.density
        return (32f * density).toInt()
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
                        cursorXState = cursorScreenX,
                        cursorYState = cursorScreenY,
                        maxSpeed = (settings?.floatingCharSpeed ?: DEFAULT_FLOATING_CHAR_SPEED).toFloat(),
                        steerAccel = (settings?.floatingCharSteering ?: DEFAULT_FLOATING_CHAR_STEERING) * 100f,
                        velocityDamping = (settings?.floatingCharDamping ?: DEFAULT_FLOATING_CHAR_DAMPING) / 10f,
                        dragVelScale = (settings?.floatingCharDragScale ?: DEFAULT_FLOATING_CHAR_DRAG_SCALE) / 100f,
                        maxTime = (settings?.floatingCharMaxTime ?: DEFAULT_FLOATING_CHAR_MAX_TIME) / 10f,
                    )
                }
            }
        }

        val (overlayW, overlayH) = getRealScreenSize()
        val params = WindowManager.LayoutParams(
            overlayW,
            overlayH,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

    // --- Tap-to-place state ---
    private var placementOverlay: ComposeView? = null
    private var floatingKeyboardPanel: ComposeView? = null
    private var savedPlacementX: Float? = null
    private var savedPlacementY: Float? = null
    private var userDismissed = false
    // Tracks whether an input session is active. Used to distinguish user-dismiss
    // (back gesture: onWindowHidden fires while inputActive is still true) from
    // system-dismiss (app navigation: onFinishInput fires first, setting this false).
    private var inputActive = false

    private fun setupPlacementOverlay() {
        removePlacementOverlay()

        val token = window?.window?.decorView?.windowToken ?: return

        // Resolve the display's corner radius once, on the main thread, in px.
        // We convert to dp inside the composable so density is handled correctly.
        val cornerRadiusPx = getDisplayCornerRadiusPx()
        val density = resources.displayMetrics.density
        val cornerRadiusDp = cornerRadiusPx / density

        val overlay = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IMEService)
            setViewTreeViewModelStoreOwner(this@IMEService)
            setViewTreeSavedStateRegistryOwner(this@IMEService)
            setContent {
                // Pulsing blue border around the full-screen placement overlay.
                // Signals to the user that they need to tap somewhere to set the
                // keyboard origin, without needing a text label.
                val transition = rememberInfiniteTransition(label = "placementPulse")
                val pulse by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "placementPulseFraction",
                )
                val borderWidthDp = (4f + pulse * 8f).dp
                val borderAlpha = 0.45f + pulse * 0.55f
                val borderColor = Color(0xFF2196F3).copy(alpha = borderAlpha)

                // Match the device's physical display corners so the pulse follows
                // the curve instead of painting into the black cutout region and
                // leaving ugly right-angle stubs behind the rounded display.
                val overlayShape = RoundedCornerShape(cornerRadiusDp.dp)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(overlayShape)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .border(width = borderWidthDp, color = borderColor, shape = overlayShape)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val screenX = offset.x
                                val screenY = offset.y
                                savedPlacementX = screenX
                                savedPlacementY = screenY
                                removePlacementOverlay()
                                showFloatingKeyboard(screenX, screenY)
                            }
                        },
                )
            }
        }

        val (overlayW, overlayH) = getRealScreenSize()
        val params = WindowManager.LayoutParams(
            overlayW,
            overlayH,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.token = token
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(overlay, params)
            placementOverlay = overlay
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add placement overlay", e)
        }
    }

    private fun removePlacementOverlay() {
        placementOverlay?.let { overlay ->
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(overlay)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove placement overlay", e)
            }
        }
        placementOverlay = null
    }

    private fun showFloatingKeyboard(centerX: Float, centerY: Float) {
        removeFloatingKeyboard()

        // Safeguard: if the inline input view still holds a real keyboard, swap it
        // for a 1px empty view so we never simultaneously show the inline + floating
        // keyboards. The currently-cached inline view is detached from its window
        // at this point, so this is cheap.
        cachedInputView?.let {
            cachedInputView = null
            val emptyView = View(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
            }
            try { this.setInputView(emptyView) } catch (_: Exception) { /* IME may not be attached yet */ }
        }

        val token = window?.window?.decorView?.windowToken ?: return
        val app = application as ThumbkeyApplication
        val settingsRepo = app.appSettingsRepository
        val clipboardRepo = app.clipboardRepository

        val panel = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IMEService)
            setViewTreeViewModelStoreOwner(this@IMEService)
            setViewTreeSavedStateRegistryOwner(this@IMEService)
            setContent {
                KeyboardContent(
                    ctx = this@IMEService,
                    settingsRepo = settingsRepo,
                    clipboardRepo = clipboardRepo,
                    floatingMode = true,
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.token = token
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(panel, params)
            floatingKeyboardPanel = panel

            // Sub-window z-order follows WindowManager addition order.
            // Re-create the animation overlay so it draws ON TOP of the
            // keyboard panel — otherwise floating chars render behind the keys.
            setupAnimationOverlay()

            panel.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, l: Int, t: Int, r: Int, b: Int,
                    ol: Int, ot: Int, or2: Int, ob: Int,
                ) {
                    if (r - l > 0 && b - t > 0) {
                        panel.removeOnLayoutChangeListener(this)
                        repositionFloatingKeyboard(centerX, centerY)
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add floating keyboard panel", e)
        }
    }

    private fun repositionFloatingKeyboard(centerX: Float, centerY: Float) {
        val panel = floatingKeyboardPanel ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val (screenW, screenH) = getRealScreenSize()
        val panelW = panel.width
        val panelH = panel.height

        val x = (centerX - panelW / 2f).toInt().coerceIn(0, (screenW - panelW).coerceAtLeast(0))
        val y = (centerY - panelH / 2f).toInt().coerceIn(0, (screenH - panelH).coerceAtLeast(0))

        try {
            val lp = panel.layoutParams as WindowManager.LayoutParams
            lp.x = x
            lp.y = y
            wm.updateViewLayout(panel, lp)
            floatingPanelScreenX = x
            floatingPanelScreenY = y
            isFloatingPanelActive = true
            // updateViewLayout during an active traversal silently skips
            // scheduleTraversals, leaving mAttachInfo stale.  Post a
            // requestLayout so the next frame's traversal picks up the
            // real window position for getLocationOnScreen callers.
            panel.post { panel.requestLayout() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reposition floating keyboard", e)
        }
    }

    private fun removeFloatingKeyboard() {
        floatingKeyboardPanel?.let { panel ->
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(panel)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove floating keyboard", e)
            }
        }
        floatingKeyboardPanel = null
        isFloatingPanelActive = false
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

    // Authoritative screen position of the floating keyboard panel.
    // getLocationOnScreen() is unreliable for TYPE_APPLICATION_PANEL windows
    // that are repositioned during an active layout traversal (mAttachInfo
    // never gets updated). These values are set directly from the LayoutParams.
    var floatingPanelScreenX: Int = 0
    var floatingPanelScreenY: Int = 0
    var isFloatingPanelActive: Boolean = false
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

    // Keyboard open/close animation state, observed by KeyboardScreen composable.
    // entryAnimTrigger increments each time the keyboard window becomes visible,
    // causing each key to animate in as a ripple wave from the tap origin.
    val keyboardEntryAnimTrigger = mutableIntStateOf(0)
    // Monotonically incremented on each `onStartInput` so composables that need
    // to re-read fields off `currentInputEditorInfo` (e.g. per-key password-field
    // checks that gate reveal animations) can key their state off this.
    // The ComposeKeyboardView is cached and reused across field changes, so
    // without this trigger child composables never recompose on field swap.
    val inputStartGeneration = mutableIntStateOf(0)
    // Normalized (0..1) horizontal origin of the entry wave; derived from cursor X
    // so the ripple starts near where the user's thumb tapped the text field.
    // Y is fixed just below the bottom row so the wave always rises upward.
    val keyboardEntryAnimOriginX = mutableFloatStateOf(0.5f)
    val keyboardEntryAnimOriginY = mutableFloatStateOf(1.2f)
    // backProgress tracks the predictive-back gesture (0 = no gesture, 1 = committed).
    // backEdge: 0 = left edge swipe, 1 = right edge swipe.
    val keyboardBackProgress = mutableFloatStateOf(0f)
    val keyboardBackEdge = mutableIntStateOf(0)
    // Holds the registered OnBackInvokedCallback (API 33+) as Any? to avoid
    // a ClassNotFoundException on older devices.
    private var predictiveBackCallback: Any? = null
    // Prevents re-entrant requestHideSelf from launching duplicate animation coroutines.
    private var hideAnimJob: kotlinx.coroutines.Job? = null

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

        val cxVal = cursorScreenX.value
        val cyVal = cursorScreenY.value
        val hasCursor = !cxVal.isNaN() && !cyVal.isNaN()

        val baseAngle = if (hasCursor) {
            kotlin.math.atan2(cyVal - startY, cxVal - startX)
        } else {
            (-Math.PI / 2.0).toFloat()
        }

        val coneHalfAngle = Math.toRadians(40.0).toFloat()

        chars.take(slots).forEachIndexed { index, ch ->
            val angleOffset = (kotlin.random.Random.nextFloat() * 2f - 1f) * coneHalfAngle
            val angle = baseAngle + angleOffset

            val speed = kotlin.random.Random.nextFloat() * 1000f + 1200f

            val velX = kotlin.math.cos(angle) * speed
            val velY = kotlin.math.sin(angle) * speed

            val jitterX = kotlin.random.Random.nextFloat() * 16f - 8f
            val jitterY = kotlin.random.Random.nextFloat() * 16f - 8f

            val delay = (kotlin.random.Random.nextFloat() * 15f).toLong()
            val spin = (kotlin.random.Random.nextFloat() * 400f - 200f)

            floatingChars.add(
                FloatingChar(
                    id = baseTime + index,
                    text = ch,
                    startX = startX + jitterX,
                    startY = startY + jitterY,
                    initVelX = velX,
                    initVelY = velY,
                    delayMs = delay,
                    isSpray = true,
                    spinSpeed = spin,
                ),
            )
        }
    }

    fun emitPdcShootdown(deletedText: String, startX: Float, startY: Float) {
        if (!floatingCharEnabled) return
        val app = application as ThumbkeyApplication
        val maxCount = app.appSettingsRepository.appSettings.value?.floatingCharMaxCount ?: DEFAULT_FLOATING_CHAR_MAX_COUNT

        if (fracturingChars.size >= maxCount + 10) return

        val validChars = deletedText.replace(WHITESPACE_REGEX, "")
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

        // When tap-to-place is active the real keyboard lives in a separate WM panel.
        // The IME window only has a 1px placeholder — report the full window height as
        // the content/visible top so the OS sees zero effective keyboard height.  This
        // prevents the system from pushing app content off-screen or drawing a filler.
        if (floatingKeyboardPanel != null || placementOverlay != null) {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
            val h = window?.window?.decorView?.height ?: 0
            outInsets.contentTopInsets = h
            outInsets.visibleTopInsets = h
            return
        }

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
    private var cachedInputView: ComposeKeyboardView? = null

    /**
     * Called every time the keyboard is brought up.
     * Hot-reloads AI settings so changes take effect without app restart.
     * Only rebuilds the view if the layout changed or it's the first time.
     */
    override fun onFinishInput() {
        inputActive = false
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
        inputActive = true
        // Trigger recomposition of anything that reads `currentInputEditorInfo`
        // (e.g. password-field gating of reveal animations in KeyboardKey).
        inputStartGeneration.intValue += 1
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

            // Suppress prediction UI ONLY for password fields. Username, email, and
            // NO_SUGGESTIONS fields still show suggestions — we just don't learn from
            // them (so we never populate the user-history dict with something that
            // looks like an account name or a one-off token).
            predictionEngine.suppressPredictions = isPasswordField
            predictionEngine.suppressLearning = isPasswordField || isNoSuggestions
            if (isPasswordField) {
                predictionEngine.clearSuggestions()
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

        val app = application as ThumbkeyApplication
        val settings = app.appSettingsRepository.appSettings.value
        val currentLayoutIndex = settings?.keyboardLayout
        val layoutChanged = currentLayoutIndex != lastLayoutIndex
        lastLayoutIndex = currentLayoutIndex

        val tapToPlaceEnabled = (settings?.tapToPlaceEnabled ?: DEFAULT_TAP_TO_PLACE_ENABLED).toBool()

        if (tapToPlaceEnabled) {
            // Safeguard: we must never have the inline keyboard AND the floating panel
            // visible at the same time. Throw away any cached inline view so a later
            // toggle doesn't momentarily render both.
            cachedInputView = null

            // Set a minimal empty input view so the IME framework is satisfied
            val emptyView = View(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
            }
            this.setInputView(emptyView)

            // Ensure keyboard definition is loaded for the floating panel
            val layoutIndex = settings?.keyboardLayout
            if (layoutIndex != null) {
                currentKeyboardDefinition = KeyboardLayout.entries[layoutIndex].keyboardDefinition
            }

            if (animationOverlay == null) {
                setupAnimationOverlay()
            }

            val sx = savedPlacementX
            val sy = savedPlacementY
            if (sx != null && sy != null) {
                showFloatingKeyboard(sx, sy)
            }
            // else: wait for onWindowShown to show placement overlay (needs token)
        } else {
            // Safeguard: if a floating panel was left over from a prior tap-to-place
            // session (setting toggled mid-session, external intent, etc.), remove it
            // before installing the inline keyboard view. Otherwise we'd render two
            // keyboards at once.
            if (floatingKeyboardPanel != null || placementOverlay != null) {
                removeFloatingKeyboard()
                removePlacementOverlay()
            }

            if (layoutChanged || cachedInputView == null) {
                val view = this.setupView()
                cachedInputView = view
                this.setInputView(view)
            } else {
                this.setInputView(cachedInputView)
            }
        }

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
            val autoTrainEnabled = getSharedPreferences("ai_settings", MODE_PRIVATE)
                .getBoolean("auto_training_enabled", true)
            if (autoTrainEnabled) TrainingWorker.schedule(this)
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
        removePlacementOverlay()
        removeFloatingKeyboard()
        savedPlacementX = null
        savedPlacementY = null
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
        // Only check when cursor jumped to position 0 AND was previously elsewhere (avoids IC reads on every update).
        if (cursorMoved && cursorAnchorInfo.selectionStart == 0 && cursorAnchorInfo.selectionEnd == 0
            && selectionStart > 0) {
            val ic = currentInputConnection
            if (ic?.getTextBeforeCursor(1, 0).isNullOrEmpty()) {
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

    /**
     * Registers a back-gesture callback on the IME window's OnBackInvokedDispatcher.
     * API 34+: OnBackAnimationCallback gives per-frame progress so keys track the swipe.
     * API 33:  OnBackInvokedCallback fires once on commit – we animate a quick fall then hide.
     * API <33: No-op; the framework's KEYCODE_BACK path handles keyboard dismissal.
     */
    private fun setupPredictiveBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val win = window?.window ?: return
        teardownPredictiveBack() // remove any stale callback first

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("NewApi")
            val cb = object : android.window.OnBackAnimationCallback {
                override fun onBackStarted(backEvent: android.window.BackEvent) {}

                override fun onBackProgressed(backEvent: android.window.BackEvent) {
                    keyboardBackProgress.floatValue = backEvent.progress
                    keyboardBackEdge.intValue = backEvent.swipeEdge
                }

                override fun onBackInvoked() {
                    // Gesture committed – snap keys to fully-fallen, then let
                    // requestHideSelf see backProgress ≥ 1 and close immediately.
                    keyboardBackProgress.floatValue = 1f
                    lifecycleScope.launch {
                        delay(80L) // short pause so fully-fallen state is visible
                        requestHideSelf(0)
                    }
                }

                override fun onBackCancelled() {
                    lifecycleScope.launch {
                        // Smoothly return keys to their normal positions over ~200 ms.
                        val start = keyboardBackProgress.floatValue
                        val steps = 12
                        for (step in 1..steps) {
                            keyboardBackProgress.floatValue = start * (1f - step.toFloat() / steps)
                            delay(16L)
                        }
                        keyboardBackProgress.floatValue = 0f
                        keyboardBackEdge.intValue = 0
                    }
                }
            }
            @Suppress("NewApi")
            win.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb,
            )
            predictiveBackCallback = cb
        } else {
            // API 33 only: no per-frame progress, just intercept the back commit.
            // requestHideSelf's override will run the fall animation automatically.
            @Suppress("NewApi")
            val cb = android.window.OnBackInvokedCallback {
                requestHideSelf(0)
            }
            @Suppress("NewApi")
            win.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb,
            )
            predictiveBackCallback = cb
        }
    }

    private fun teardownPredictiveBack() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val cb = predictiveBackCallback ?: return
        predictiveBackCallback = null
        val win = window?.window ?: run {
            keyboardBackProgress.floatValue = 0f
            keyboardBackEdge.intValue = 0
            return
        }
        @Suppress("NewApi")
        win.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(
            cb as android.window.OnBackInvokedCallback,
        )
        keyboardBackProgress.floatValue = 0f
        keyboardBackEdge.intValue = 0
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Ensure clean state from any previous close animation.
        hideAnimJob = null
        keyboardBackProgress.floatValue = 0f
        keyboardBackEdge.intValue = 0
        // Derive wave origin X from cursor screen position so the ripple starts near
        // where the user tapped. Fall back to horizontal center if unavailable.
        val dm = resources.displayMetrics
        val curX = cursorScreenX.value
        keyboardEntryAnimOriginX.floatValue =
            if (!curX.isNaN() && dm.widthPixels > 0) {
                (curX / dm.widthPixels).coerceIn(0f, 1f)
            } else {
                0.5f
            }
        keyboardEntryAnimOriginY.floatValue = 1.2f
        keyboardEntryAnimTrigger.intValue++
        setupPredictiveBack()
        // onStartInput fires before the IME window is visible, so windowToken can be null
        // and the overlay silently fails to create. Retry here where the token is guaranteed.
        if (animationOverlay == null) {
            setupAnimationOverlay()
        }

        val app = application as ThumbkeyApplication
        val tapToPlaceEnabled = (app.appSettingsRepository.appSettings.value?.tapToPlaceEnabled
            ?: DEFAULT_TAP_TO_PLACE_ENABLED).toBool()
        if (tapToPlaceEnabled && savedPlacementX == null && placementOverlay == null && floatingKeyboardPanel == null) {
            setupPlacementOverlay()
        }
    }

    override fun requestHideSelf(flags: Int) {
        userDismissed = true

        // Already fully animated → close immediately.
        if (keyboardBackProgress.floatValue >= 1f) {
            hideAnimJob = null
            super.requestHideSelf(flags)
            return
        }

        // Animation already running from a previous call → let it finish.
        if (hideAnimJob?.isActive == true) return

        hideAnimJob = lifecycleScope.launch {
            try {
                val start = keyboardBackProgress.floatValue
                val remaining = 1f - start
                val steps = (remaining * 20).toInt().coerceAtLeast(8)
                for (step in 1..steps) {
                    keyboardBackProgress.floatValue = start + remaining * (step.toFloat() / steps)
                    delay(12L)
                }
                keyboardBackProgress.floatValue = 1f
                delay(50L)
            } finally {
                super@IMEService.requestHideSelf(flags)
            }
        }
    }

    override fun onWindowHidden() {
        hideAnimJob = null
        teardownPredictiveBack()
        try {
            predictionEngine.clearSuggestions()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear suggestions on window hidden", e)
        }
        currentKeyboardDefinition?.settings?.textProcessor?.handleFinishInput(this)

        removePlacementOverlay()
        removeFloatingKeyboard()

        // Detect user-initiated dismiss (back gesture/button) vs system-initiated
        // (app navigation, field lost focus). Two signals:
        //   1. requestHideSelf() was called → userDismissed is true
        //   2. Input session is still active → onFinishInput hasn't fired yet,
        //      meaning the window hid before the field ended (user back-swipe)
        // For system closes, onFinishInput fires first (inputActive becomes false),
        // THEN onWindowHidden fires.
        if (userDismissed || inputActive) {
            savedPlacementX = null
            savedPlacementY = null
        }
        userDismissed = false

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
