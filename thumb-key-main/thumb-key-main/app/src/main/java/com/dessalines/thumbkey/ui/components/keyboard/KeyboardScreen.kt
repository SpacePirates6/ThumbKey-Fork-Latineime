package com.dessalines.thumbkey.ui.components.keyboard

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.dessalines.thumbkey.IMEService
import com.dessalines.thumbkey.R
import com.dessalines.thumbkey.db.AppSettings
import com.dessalines.thumbkey.db.ClipboardItem
import com.dessalines.thumbkey.db.ClipboardRepository
import com.dessalines.thumbkey.db.DEFAULT_ANIMATION_HELPER_SPEED
import com.dessalines.thumbkey.db.DEFAULT_ANIMATION_SPEED
import com.dessalines.thumbkey.db.DEFAULT_AUTO_CAPITALIZE
import com.dessalines.thumbkey.db.DEFAULT_AUTO_SIZE_KEYS
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_CHAR_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_HELPER_FULL_OPACITY
import com.dessalines.thumbkey.db.DEFAULT_ENABLE_KEY_FADEOUT
import com.dessalines.thumbkey.db.DEFAULT_FADEOUT_BORDER
import com.dessalines.thumbkey.db.DEFAULT_KEY_FADEOUT_TIME_MS
import com.dessalines.thumbkey.db.DEFAULT_KEYBOARD_OPACITY
import com.dessalines.thumbkey.db.DEFAULT_TOUCH_THROUGH_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_ZERO_HEIGHT_INSETS
import com.dessalines.thumbkey.db.DEFAULT_BACKDROP_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_CIRCULAR_DRAG_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_CLIPBOARD_HISTORY_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_CLOCKWISE_DRAG_ACTION
import com.dessalines.thumbkey.db.DEFAULT_COUNTERCLOCKWISE_DRAG_ACTION
import com.dessalines.thumbkey.db.DEFAULT_DRAG_RETURN_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_GHOST_KEYS_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_HIDE_LETTERS
import com.dessalines.thumbkey.db.DEFAULT_HIDE_SYMBOLS
import com.dessalines.thumbkey.db.DEFAULT_IGNORE_BOTTOM_PADDING
import com.dessalines.thumbkey.db.DEFAULT_KEYBOARD_LAYOUT
import com.dessalines.thumbkey.db.DEFAULT_KEY_BORDER_WIDTH
import com.dessalines.thumbkey.db.DEFAULT_KEY_HEIGHT
import com.dessalines.thumbkey.db.DEFAULT_KEY_PADDING
import com.dessalines.thumbkey.db.DEFAULT_KEY_RADIUS
import com.dessalines.thumbkey.db.DEFAULT_KEY_WIDTH
import com.dessalines.thumbkey.db.DEFAULT_MIN_SWIPE_LENGTH
import com.dessalines.thumbkey.db.DEFAULT_NON_SQUARE_KEYS
import com.dessalines.thumbkey.db.DEFAULT_POSITION
import com.dessalines.thumbkey.db.DEFAULT_POSITION_PADDING
import com.dessalines.thumbkey.db.DEFAULT_PUSHUP_SIZE
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_BACKSPACE_DEADZONE_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_CURSOR_MOVEMENT_MODE
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_SENSITIVITY
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_SPACEBAR_DEADZONE_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SOUND_ON_TAP
import com.dessalines.thumbkey.db.DEFAULT_SPACEBAR_MULTITAPS
import com.dessalines.thumbkey.db.DEFAULT_VIBRATE_ON_SLIDE
import com.dessalines.thumbkey.db.DEFAULT_VIBRATE_ON_TAP
import com.dessalines.thumbkey.keyboards.BACKSPACE_KEY_ITEM
import com.dessalines.thumbkey.keyboards.EMOJI_BACK_KEY_ITEM
import com.dessalines.thumbkey.keyboards.KB_EN_THUMBKEY_MAIN
import com.dessalines.thumbkey.keyboards.NUMERIC_KEY_ITEM
import com.dessalines.thumbkey.keyboards.RETURN_KEY_ITEM
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.dessalines.thumbkey.prediction.SuggestionBar
import com.dessalines.thumbkey.utils.CircularDragAction
import com.dessalines.thumbkey.utils.KeyAction
import com.dessalines.thumbkey.utils.KeyboardLayout
import com.dessalines.thumbkey.utils.KeyboardMode
import com.dessalines.thumbkey.utils.KeyboardPosition
import com.dessalines.thumbkey.utils.TAG
import com.dessalines.thumbkey.utils.getAutoKeyWidth
import com.dessalines.thumbkey.utils.getKeyboardMode
import com.dessalines.thumbkey.utils.getModifiedKeyboardDefinition
import com.dessalines.thumbkey.utils.keyboardPositionToAlignment
import com.dessalines.thumbkey.utils.toBool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeMark

@Composable
fun KeyboardScreen(
    settings: AppSettings?,
    clipboardRepository: ClipboardRepository,
    floatingMode: Boolean = false,
    onSwitchLanguage: () -> Unit,
    onChangePosition: ((old: KeyboardPosition) -> KeyboardPosition) -> Unit,
    onToggleHideLetters: () -> Unit,
    onGoToClipboardSettings: () -> Unit,
) {
    val ctx = LocalContext.current as IMEService

    // Clamp the stored layout index: old installs may reference a layout whose
    // ordinal no longer exists (layouts can be removed between releases, or the
    // DB may have been hand-edited via backup/restore). Without the clamp this
    // throws `ArrayIndexOutOfBoundsException` at keyboard start.
    val layoutEntries = KeyboardLayout.entries.sortedBy { it.ordinal }
    val layoutIndex = (settings?.keyboardLayout ?: DEFAULT_KEYBOARD_LAYOUT)
        .coerceIn(0, layoutEntries.size - 1)
    val layout = layoutEntries[layoutIndex]

    val keyMods = settings?.keyModifications
    val keyboardDefinition = remember(layout, keyMods) {
        if (!keyMods.isNullOrEmpty()) {
            getModifiedKeyboardDefinition(layout, keyMods)
                ?: layout.keyboardDefinition
        } else {
            layout.keyboardDefinition
        }
    }

    val modeState = remember {
        val startMode =
            getKeyboardMode(
                ime = ctx,
                autoCapitalize = settings?.autoCapitalize?.toBool() == true && keyboardDefinition.settings.autoShift,
            )

        mutableStateOf(startMode)
    }
    var mode by modeState

    val capsLockState = remember {
        mutableStateOf(false)
    }
    var capsLock by capsLockState

    // TODO get rid of this crap
    val lastAction = remember { mutableStateOf<Pair<KeyAction, TimeMark>?>(null) }

    val clipboardItems by clipboardRepository.allClipboardItems.observeAsState(initial = emptyList())

    val keyboard =
        when (mode) {
            KeyboardMode.MAIN -> {
                keyboardDefinition.modes.main
            }

            KeyboardMode.SHIFTED -> {
                keyboardDefinition.modes.shifted
            }

            KeyboardMode.NUMERIC -> {
                keyboardDefinition.modes.numeric
            }

            KeyboardMode.CTRLED -> {
                keyboardDefinition.modes.ctrled ?: run {
                    val text = stringResource(R.string.warning_invalid_mode, mode, keyboardDefinition.title)
                    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, text)

                    mode = KeyboardMode.MAIN
                    keyboardDefinition.modes.main
                }
            }

            KeyboardMode.ALTED -> {
                keyboardDefinition.modes.alted ?: run {
                    val text = stringResource(R.string.warning_invalid_mode, mode, keyboardDefinition.title)
                    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, text)

                    mode = KeyboardMode.MAIN
                    keyboardDefinition.modes.main
                }
            }

            else -> {
                // Emoji and Clipboard modes use their own rendering, which does not depend on this value
                KB_EN_THUMBKEY_MAIN
            }
        }

    val position =
        KeyboardPosition.entries[
            settings?.position
                ?: DEFAULT_POSITION,
        ]

    val positionPadding = settings?.positionPadding ?: DEFAULT_POSITION_PADDING

    val pushupSizeDp = (settings?.pushupSize ?: DEFAULT_PUSHUP_SIZE).dp
    val ignoreBottomPadding = (settings?.ignoreBottomPadding ?: DEFAULT_IGNORE_BOTTOM_PADDING).toBool()

    val autoCapitalize = (settings?.autoCapitalize ?: DEFAULT_AUTO_CAPITALIZE).toBool()
    val spacebarMultiTaps = (settings?.spacebarMultiTaps ?: DEFAULT_SPACEBAR_MULTITAPS).toBool()
    val slideEnabled = (settings?.slideEnabled ?: DEFAULT_SLIDE_ENABLED).toBool()
    val slideCursorMovementMode = (settings?.slideCursorMovementMode ?: DEFAULT_SLIDE_CURSOR_MOVEMENT_MODE)
    val slideSpacebarDeadzoneEnabled = (settings?.slideSpacebarDeadzoneEnabled ?: DEFAULT_SLIDE_SPACEBAR_DEADZONE_ENABLED).toBool()
    val slideBackspaceDeadzoneEnabled = (settings?.slideBackspaceDeadzoneEnabled ?: DEFAULT_SLIDE_BACKSPACE_DEADZONE_ENABLED).toBool()
    val keyBorderWidth = (settings?.keyBorderWidth ?: DEFAULT_KEY_BORDER_WIDTH)
    val vibrateOnTap = (settings?.vibrateOnTap ?: DEFAULT_VIBRATE_ON_TAP).toBool()
    val vibrateOnSlide = (settings?.vibrateOnSlide ?: DEFAULT_VIBRATE_ON_SLIDE).toBool()
    val soundOnTap = (settings?.soundOnTap ?: DEFAULT_SOUND_ON_TAP).toBool()
    val hideLetters = (settings?.hideLetters ?: DEFAULT_HIDE_LETTERS).toBool()
    val hideSymbols = (settings?.hideSymbols ?: DEFAULT_HIDE_SYMBOLS).toBool()
    val backdropEnabled = (settings?.backdropEnabled ?: DEFAULT_BACKDROP_ENABLED).toBool()
    val backdropColor = MaterialTheme.colorScheme.background
    val backdropPadding = 6.dp
    val keyPadding = settings?.keyPadding ?: DEFAULT_KEY_PADDING
    val autoSizeKeys = (settings?.autoSizeKeys ?: DEFAULT_AUTO_SIZE_KEYS).toBool()
    val nonSquareKeys = (settings?.nonSquareKeys ?: DEFAULT_NON_SQUARE_KEYS).toBool()
    val legendWidth =
        if (autoSizeKeys) {
            val keyboardLayout = settings?.keyboardLayout ?: DEFAULT_KEYBOARD_LAYOUT
            getAutoKeyWidth(keyboardLayout, keyPadding, position, ctx)
        } else {
            settings?.keyWidth ?: DEFAULT_KEY_WIDTH
        }
    val legendHeight =
        if (!nonSquareKeys) {
            legendWidth
        } else {
            settings?.keyHeight ?: DEFAULT_KEY_HEIGHT
        }
    val keyRadius = settings?.keyRadius ?: DEFAULT_KEY_RADIUS
    val dragReturnEnabled = (settings?.dragReturnEnabled ?: DEFAULT_DRAG_RETURN_ENABLED).toBool()
    val circularDragEnabled = (settings?.circularDragEnabled ?: DEFAULT_CIRCULAR_DRAG_ENABLED).toBool()
    val clockwiseDragAction = CircularDragAction.entries[settings?.clockwiseDragAction ?: DEFAULT_CLOCKWISE_DRAG_ACTION]
    val counterclockwiseDragAction =
        CircularDragAction.entries[settings?.counterclockwiseDragAction ?: DEFAULT_COUNTERCLOCKWISE_DRAG_ACTION]
    val ghostKeysEnabled = (settings?.ghostKeysEnabled ?: DEFAULT_GHOST_KEYS_ENABLED).toBool()
    val keyboardOpacity = (settings?.keyboardOpacity ?: DEFAULT_KEYBOARD_OPACITY)
    val touchThroughEnabled = (settings?.touchThroughEnabled ?: DEFAULT_TOUCH_THROUGH_ENABLED).toBool()
    val helperFullOpacity = (settings?.helperFullOpacity ?: DEFAULT_HELPER_FULL_OPACITY).toBool()
    val enableKeyFadeout = (settings?.enableKeyFadeout ?: DEFAULT_ENABLE_KEY_FADEOUT).toBool()
    val keyFadeoutTimeMs = settings?.keyFadeoutTimeMs ?: DEFAULT_KEY_FADEOUT_TIME_MS
    val fadeoutBorder = (settings?.fadeoutBorder ?: DEFAULT_FADEOUT_BORDER).toBool()
    val zeroHeightInsets = (settings?.zeroHeightInsets ?: DEFAULT_ZERO_HEIGHT_INSETS).toBool()
    val floatingCharEnabled = (settings?.floatingCharEnabled ?: DEFAULT_FLOATING_CHAR_ENABLED).toBool()

    if (floatingMode) {
        // Floating panel handles its own touches via WindowManager; don't let
        // coordinates from this window leak into the IME window's insets/rects.
        ctx.touchThroughEnabled = false
        ctx.zeroHeightInsets = false
        ctx.floatingCharEnabled = floatingCharEnabled
        ctx.suggestionBarRect.setEmpty()
        ctx.keyboardKeysRect.setEmpty()
        ctx.updateTouchableRegion()
    } else {
        ctx.touchThroughEnabled = touchThroughEnabled
        ctx.zeroHeightInsets = zeroHeightInsets
        ctx.floatingCharEnabled = floatingCharEnabled
        if (!touchThroughEnabled && !zeroHeightInsets) {
            ctx.suggestionBarRect.setEmpty()
            ctx.keyboardKeysRect.setEmpty()
        }
    }

    val keyBorderWidthFloat = keyBorderWidth / 10.0f
    val keyBorderColour = MaterialTheme.colorScheme.outline
    val keyHeight = legendHeight.toFloat()
    val keyWidth = legendWidth.toFloat()
    val cornerRadius = (keyRadius / 100.0f) * ((keyWidth + keyHeight) / 4.0f)

    val currentOnSwitchLanguage by rememberUpdatedState(onSwitchLanguage)
    val stableOnToggleShiftMode: (Boolean) -> Unit = remember {
        { enable: Boolean ->
            modeState.value = if (enable) {
                KeyboardMode.SHIFTED
            } else {
                capsLockState.value = false
                KeyboardMode.MAIN
            }
        }
    }
    val stableOnToggleCtrlMode: (Boolean) -> Unit = remember {
        { enable: Boolean ->
            modeState.value = if (enable) KeyboardMode.CTRLED else KeyboardMode.MAIN
        }
    }
    val stableOnToggleAltMode: (Boolean) -> Unit = remember {
        { enable: Boolean ->
            modeState.value = if (enable) KeyboardMode.ALTED else KeyboardMode.MAIN
        }
    }
    val stableOnToggleNumericMode: (Boolean) -> Unit = remember {
        { enable: Boolean ->
            modeState.value = if (enable) {
                KeyboardMode.NUMERIC
            } else {
                capsLockState.value = false
                KeyboardMode.MAIN
            }
        }
    }
    val stableOnToggleEmojiMode: (Boolean) -> Unit = remember {
        { enable: Boolean ->
            modeState.value = if (enable) KeyboardMode.EMOJI else KeyboardMode.MAIN
        }
    }
    val stableOnToggleClipboardMode: (Boolean) -> Unit = remember {
        { enable: Boolean ->
            modeState.value = if (enable) KeyboardMode.CLIPBOARD else KeyboardMode.MAIN
        }
    }
    val stableOnToggleCapsLock: () -> Unit = remember {
        {
            capsLockState.value = !capsLockState.value
            if (capsLockState.value) {
                modeState.value = KeyboardMode.SHIFTED
            }
        }
    }
    val stableOnKeyEvent: () -> Unit = remember {
        {
            when (modeState.value) {
                KeyboardMode.CTRLED, KeyboardMode.ALTED -> modeState.value = KeyboardMode.MAIN
                else -> {}
            }
        }
    }
    val stableOnAutoCapitalize: (Boolean) -> Unit = remember {
        { enable: Boolean ->
            if (modeState.value !== KeyboardMode.NUMERIC) {
                if (enable) {
                    modeState.value = KeyboardMode.SHIFTED
                } else if (!capsLockState.value) {
                    modeState.value = KeyboardMode.MAIN
                }
            }
        }
    }
    val stableOnSwitchLanguage: () -> Unit = remember {
        {
            currentOnSwitchLanguage()
            modeState.value = KeyboardMode.MAIN
        }
    }

    val opacityAlpha = keyboardOpacity / 100f

    if (mode == KeyboardMode.EMOJI) {
        // Dynamically determine number of rows based on keyboard structure
        val rowCount = keyboardDefinition.modes.main.arr.size
        val controllerKeys =
            if (rowCount <= 3) {
                listOf(EMOJI_BACK_KEY_ITEM, BACKSPACE_KEY_ITEM, RETURN_KEY_ITEM)
            } else {
                listOf(EMOJI_BACK_KEY_ITEM, NUMERIC_KEY_ITEM, BACKSPACE_KEY_ITEM, RETURN_KEY_ITEM)
            }
        val keyboardHeight = Dp((keyHeight * controllerKeys.size) - (keyPadding * 2))

        LaunchedEffect(Unit) {
            ctx.currentInputConnection?.requestCursorUpdates(0)
        }

        Box(
            modifier =
                Modifier
                    .alpha(opacityAlpha)
                    .then(
                        if (backdropEnabled) {
                            Modifier.background(backdropColor)
                        } else {
                            (Modifier)
                        },
                    ),
        ) {
            // adds a pretty line if you're using the backdrop
            if (backdropEnabled) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(color = MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Row(
                modifier =
                    Modifier
                        .then(if (!ignoreBottomPadding) Modifier.safeDrawingPadding() else Modifier)
                        .padding(bottom = pushupSizeDp)
                        .fillMaxWidth()
                        .then(
                            if (backdropEnabled) {
                                Modifier.padding(top = backdropPadding)
                            } else {
                                (Modifier)
                            },
                        ),
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f) // Take up available space equally
                            .padding(keyPadding.dp)
                            .clip(RoundedCornerShape(cornerRadius.dp))
                            .then(
                                if (keyBorderWidthFloat > 0.0) {
                                    Modifier.border(
                                        keyBorderWidthFloat.dp,
                                        keyBorderColour,
                                        shape = RoundedCornerShape(cornerRadius.dp),
                                    )
                                } else {
                                    (Modifier)
                                },
                            ).background(MaterialTheme.colorScheme.surface),
                ) {
                    val view = LocalView.current
                    val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    AndroidView(
                        // Write the emoji to our text box when we tap one.
                        factory = { context ->
                            val emojiPicker = EmojiPickerView(context)
                            emojiPicker.setOnEmojiPickedListener {
                                if (vibrateOnTap) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                                if (soundOnTap) {
                                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, .1f)
                                }
                                ctx.currentInputConnection.commitText(
                                    it.emoji,
                                    1,
                                )
                            }
                            emojiPicker
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(keyboardHeight),
                    )
                }
                Column {
                    controllerKeys.forEach { key ->
                        Column {
                            KeyboardKey(
                                key = key,
                                lastAction = lastAction,
                                legendHeight = legendHeight,
                                legendWidth = legendWidth,
                                keyHeight = keyHeight,
                                keyWidth = keyWidth,
                                keyPadding = keyPadding,
                                keyBorderWidth = keyBorderWidthFloat,
                                keyRadius = cornerRadius,
                                autoCapitalize = autoCapitalize,
                                keyboardSettings = keyboardDefinition.settings,
                                spacebarMultiTaps = spacebarMultiTaps,
                                vibrateOnTap = vibrateOnTap,
                                vibrateOnSlide = vibrateOnSlide,
                                soundOnTap = soundOnTap,
                                hideLetters = hideLetters,
                                hideSymbols = hideSymbols,
                                capsLock = capsLock,
                                animationSpeed =
                                    settings?.animationSpeed
                                        ?: DEFAULT_ANIMATION_SPEED,
                                animationHelperSpeed =
                                    settings?.animationHelperSpeed
                                        ?: DEFAULT_ANIMATION_HELPER_SPEED,
                                minSwipeLength = settings?.minSwipeLength ?: DEFAULT_MIN_SWIPE_LENGTH,
                                slideSensitivity = settings?.slideSensitivity ?: DEFAULT_SLIDE_SENSITIVITY,
                                slideEnabled = slideEnabled,
                                slideCursorMovementMode = slideCursorMovementMode,
                                slideSpacebarDeadzoneEnabled = slideSpacebarDeadzoneEnabled,
                                slideBackspaceDeadzoneEnabled = slideBackspaceDeadzoneEnabled,
                                onToggleShiftMode = stableOnToggleShiftMode,
                                onToggleCtrlMode = stableOnToggleCtrlMode,
                                onToggleAltMode = stableOnToggleAltMode,
                                onToggleNumericMode = stableOnToggleNumericMode,
                                onToggleEmojiMode = stableOnToggleEmojiMode,
                                onToggleClipboardMode = stableOnToggleClipboardMode,
                                onToggleCapsLock = stableOnToggleCapsLock,
                                onToggleHideLetters = onToggleHideLetters,
                                onAutoCapitalize = stableOnAutoCapitalize,
                                onSwitchLanguage = stableOnSwitchLanguage,
                                onChangePosition = onChangePosition,
                                onKeyEvent = stableOnKeyEvent,
                                dragReturnEnabled = dragReturnEnabled,
                                circularDragEnabled = circularDragEnabled,
                                clockwiseDragAction = clockwiseDragAction,
                                counterclockwiseDragAction = counterclockwiseDragAction,
                                helperFullOpacity = helperFullOpacity,
                                opacityAlpha = opacityAlpha,
                                enableKeyFadeout = enableKeyFadeout,
                                keyFadeoutTimeMs = keyFadeoutTimeMs,
                                fadeoutBorder = fadeoutBorder,
                            )
                        }
                    }
                }
            }
        }
    } else if (mode == KeyboardMode.CLIPBOARD) {
        // Clipboard history view
        val scope = rememberCoroutineScope()
        val clipboardHistoryEnabled =
            (settings?.clipboardHistoryEnabled ?: DEFAULT_CLIPBOARD_HISTORY_ENABLED).toBool()

        // Calculate keyboard height based on number of rows
        val rowCount = keyboardDefinition.modes.main.arr.size
        val keyboardHeight = Dp(keyHeight * rowCount)

        // Perform auto-cleanup when entering clipboard view, and clear all if disabled
        LaunchedEffect(Unit) {
            if (clipboardHistoryEnabled) {
                clipboardRepository?.clearExpired()
            } else {
                clipboardRepository?.clearAll()
            }
        }

        Box(
            modifier =
                Modifier
                    .alpha(opacityAlpha)
                    .then(
                        if (backdropEnabled) {
                            Modifier.background(backdropColor)
                        } else {
                            (Modifier)
                        },
                    ),
        ) {
            // adds a pretty line if you're using the backdrop
            if (backdropEnabled) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(color = MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Box(
                modifier =
                    Modifier
                        .then(if (!ignoreBottomPadding) Modifier.safeDrawingPadding() else Modifier)
                        .padding(bottom = pushupSizeDp)
                        .fillMaxWidth()
                        .height(keyboardHeight)
                        .then(
                            if (backdropEnabled) {
                                Modifier.padding(top = backdropPadding)
                            } else {
                                (Modifier)
                            },
                        ),
            ) {
                ClipboardHistoryScreen(
                    clipboardItems = clipboardItems,
                    isEnabled = clipboardHistoryEnabled,
                    onItemClick = { item ->
                        // Paste and return to keyboard
                        ctx.currentInputConnection.commitText(item.text, 1)
                        mode = KeyboardMode.MAIN
                    },
                    onItemPaste = { item ->
                        // Paste WITHOUT returning to keyboard
                        ctx.currentInputConnection.commitText(item.text, 1)
                    },
                    onItemDelete = { item ->
                        scope.launch(Dispatchers.IO) {
                            clipboardRepository?.deleteItem(item)
                        }
                    },
                    onItemTogglePin = { item ->
                        scope.launch(Dispatchers.IO) {
                            clipboardRepository?.togglePin(item)
                        }
                    },
                    onBack = {
                        mode = KeyboardMode.MAIN
                    },
                    onClearAll = {
                        scope.launch(Dispatchers.IO) {
                            clipboardRepository?.clearUnpinned()
                        }
                    },
                    onGoToClipboardSettings = onGoToClipboardSettings,
                    keyHeight = keyHeight,
                    keyPadding = keyPadding,
                    cornerRadius = cornerRadius,
                    vibrateOnTap = vibrateOnTap,
                )
            }
        }
    } else {
        // NOTE, this should use or CURSOR_UPDATE_FILTER_INSERTION_MARKER , but it doesn't work on
        // non-compose textfields.
        // This also requires jetpack compose >= 1.6
        // See https://github.com/dessalines/thumb-key/issues/242
        LaunchedEffect(Unit) {
            if (ctx.currentInputConnection?.requestCursorUpdates(CURSOR_UPDATE_MONITOR) == true) {
                Log.d(TAG, "request for cursor updates succeeded, cursor updates will be provided")
            } else {
                Log.d(TAG, "request for cursor updates failed, cursor updates will not be provided")
            }
        }

        // Prediction engine suggestion bar
        val predictionEngine = ctx.predictionEngine

        // Set up gesture decoder from the current keyboard layout
        val densityValue = LocalDensity.current.density
        val keyWidthPx = keyWidth * densityValue
        val keyHeightPx = keyHeight * densityValue
        LaunchedEffect(keyboard, keyWidth, keyHeight) {
            try {
                val decoder = predictionEngine.gestureDecoder
                val rows = keyboard.arr.size
                val cols = keyboard.arr.firstOrNull()?.size ?: 3
                decoder.setGridDimensions(keyWidthPx, keyHeightPx, rows, cols)

                val charMap = mutableMapOf<Pair<Int, Int>, Char>()
                keyboard.arr.forEachIndexed { row, keys ->
                    keys.forEachIndexed { col, key ->
                        val action = key.center.action
                        if (action is KeyAction.CommitText && action.text.length == 1) {
                            charMap[row to col] = action.text[0].lowercaseChar()
                        }
                    }
                }
                decoder.setKeyCharMap(charMap)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set up gesture decoder", e)
            }
        }

        val effectiveBackdropColor = if (helperFullOpacity) {
            backdropColor.copy(alpha = backdropColor.alpha * opacityAlpha)
        } else {
            backdropColor
        }

        val drawKeyboard = @Composable { alignment: Alignment, drawBackdrop: Boolean, positionPadding: Int ->
            // ── keyboard open/close animation ──────────────────────────────────────────
            val entryAnimTrigger by ctx.keyboardEntryAnimTrigger
            val entryOriginX by ctx.keyboardEntryAnimOriginX
            val entryOriginY by ctx.keyboardEntryAnimOriginY
            // backProgress is updated by IMEService coroutines (predictive-back gesture
            // or requestHideSelf fall loop) so no extra Compose smoothing is needed.
            val backProgress by ctx.keyboardBackProgress
            val backEdge by ctx.keyboardBackEdge

            val keyRows = keyboard.arr.size
            val keyCols = keyboard.arr.firstOrNull()?.size ?: 0
            val totalKeys = keyRows * keyCols

            // Bug 10/11 fix: `entryOriginX` arrives as a fraction of the full
            // display width (see IMEService.onWindowShown), but the wave math
            // below interprets it as a fraction of the keyboard grid.  Those
            // agree only when the keyboard is exactly screen-wide and anchored
            // at x=0 — not true for Left/Right/Dual positions or for the
            // tap-to-place floating panel.  Track this keyboard instance's
            // screen-space X bounds and remap screen-fraction → grid-fraction
            // before the animation reads it.  We capture these from the same
            // Column whose onGloballyPositioned already runs for touchThrough.
            val rootView = LocalView.current
            var kbGridScreenLeft by remember { mutableStateOf(Float.NaN) }
            var kbGridScreenWidth by remember { mutableStateOf(0f) }

            // Both animatables store raw dp offsets (no normalisation).
            //
            // Bug 6 fix: keyed on `totalKeys` instead of `keyboard`.  Shift /
            // caps / numeric toggles all produce a NEW `keyboard` reference,
            // which used to reset the Animatable list mid-animation — any
            // in-flight entry wave was orphaned on old objects and the new
            // grid popped straight to 0f.  Keeping the lists alive across
            // same-grid-size mode switches lets the wave finish naturally.
            val keyAnimX = remember(totalKeys) { List(totalKeys) { Animatable(0f) } }
            val keyAnimY = remember(totalKeys) { List(totalKeys) { Animatable(0f) } }

            LaunchedEffect(entryAnimTrigger) {
                if (entryAnimTrigger > 0 && totalKeys > 0) {
                    val dur = 460
                    // Wave speed: ms of extra stagger per grid unit of distance from origin.
                    val waveSpeed = 32f
                    // Push distance (dp): how far each key starts from its slot.
                    val pushDist = 52f

                    // Remap the screen-fraction X origin into a keyboard-local
                    // fraction.  If we haven't received a layout pass yet
                    // (kbGridScreenLeft == NaN), fall back to the center so at
                    // least the wave is symmetrical instead of skewed.
                    val dm = ctx.resources.displayMetrics
                    val screenWidthPx = dm.widthPixels.toFloat().coerceAtLeast(1f)
                    val localOriginX = if (
                        kbGridScreenLeft.isFinite() && kbGridScreenWidth > 1f
                    ) {
                        val screenPx = entryOriginX * screenWidthPx
                        ((screenPx - kbGridScreenLeft) / kbGridScreenWidth)
                            .coerceIn(0f, 1f)
                    } else {
                        0.5f
                    }

                    // Origin in grid-unit coords. entryOriginY stays normalised 0..1
                    // where Y = 1.2 puts the origin just below the bottom row.
                    val ox = localOriginX * (keyCols - 1).coerceAtLeast(1).toFloat()
                    val oy = entryOriginY * (keyRows - 1).coerceAtLeast(1).toFloat()

                    // Snap all keys to their push-start synchronously BEFORE
                    // any delayed coroutines fire.  Without this, a slow frame
                    // between increment-trigger and the first animateTo would
                    // leave keys at their previous state (often 0f) for one
                    // draw — looks like a flash-then-animate.
                    for (i in 0 until keyRows) {
                        for (j in 0 until keyCols) {
                            val idx = i * keyCols + j
                            val dx = j.toFloat() - ox
                            val dy = i.toFloat() - oy
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.5f)
                            val normDx = dx / dist
                            val normDy = dy / dist
                            keyAnimX[idx].snapTo(-normDx * pushDist)
                            keyAnimY[idx].snapTo(-normDy * pushDist)
                        }
                    }

                    for (i in 0 until keyRows) {
                        for (j in 0 until keyCols) {
                            val idx = i * keyCols + j

                            // Vector FROM origin TO this key's grid slot.
                            val dx = j.toFloat() - ox
                            val dy = i.toFloat() - oy
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.5f)

                            // Keys nearest the origin animate first.
                            val staggerMs = (dist * waveSpeed).toLong()

                            // Each key starts pushed toward the origin (opposite of travel
                            // direction), then springs outward into its slot.
                            val normDx = dx / dist
                            val normDy = dy / dist

                            launch {
                                delay(staggerMs)
                                keyAnimX[idx].animateTo(0f, keyframes {
                                    durationMillis = dur
                                    (normDx * 5f) at (dur * 78 / 100) using FastOutLinearInEasing
                                })
                            }
                            launch {
                                delay(staggerMs)
                                keyAnimY[idx].animateTo(0f, keyframes {
                                    durationMillis = dur
                                    (normDy * 5f) at (dur * 78 / 100) using FastOutLinearInEasing
                                })
                            }
                        }
                    }
                }
            }
            // ───────────────────────────────────────────────────────────────────────────

            val modifierPositionPadding =
                if (floatingMode) {
                    Modifier
                } else if (positionPadding > 0) {
                    Modifier.padding(start = positionPadding.dp)
                } else {
                    Modifier.padding(end = -positionPadding.dp)
                }
            Box(
                contentAlignment = if (floatingMode) Alignment.TopStart else alignment,
                modifier =
                    if (floatingMode) {
                        Modifier
                            .then(if (drawBackdrop) Modifier.background(effectiveBackdropColor) else Modifier)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .then(if (drawBackdrop) Modifier.background(effectiveBackdropColor) else (Modifier))
                            .then(if (!ignoreBottomPadding) Modifier.safeDrawingPadding() else Modifier)
                            .padding(bottom = pushupSizeDp)
                            .then(
                                if (backdropEnabled) {
                                    Modifier.padding(top = backdropPadding)
                                } else {
                                    (Modifier)
                                },
                            )
                    },
            ) {
                if (drawBackdrop) {
                    val dividerColor = if (helperFullOpacity) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = opacityAlpha)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(color = dividerColor),
                    )
                }
                Column(
                    modifier = modifierPositionPadding
                        // Capture the Column's screen-space X range so the
                        // entry wave's origin (reported in screen fractions)
                        // can be remapped to a keyboard-grid fraction.  Runs
                        // unconditionally so non-touchthrough keyboards still
                        // produce a correct wave origin.
                        .onGloballyPositioned { coordinates ->
                            val winPos = coordinates.localToWindow(Offset.Zero)
                            val size = coordinates.size
                            val screenX = if (ctx.isFloatingPanelActive) {
                                ctx.floatingPanelScreenX + winPos.x
                            } else {
                                val loc = IntArray(2)
                                rootView.getLocationOnScreen(loc)
                                loc[0] + winPos.x
                            }
                            kbGridScreenLeft = screenX
                            kbGridScreenWidth = size.width.toFloat()
                            if (touchThroughEnabled) {
                                ctx.keyboardKeysRect.set(
                                    winPos.x.toInt(),
                                    winPos.y.toInt(),
                                    (winPos.x + size.width).toInt(),
                                    (winPos.y + size.height).toInt(),
                                )
                                ctx.updateTouchableRegion()
                            }
                        },
                ) {
                    keyboard.arr.forEachIndexed { i, row ->
                        Row {
                            row.forEachIndexed { j, key ->
                                val keyIdx = i * keyCols + j

                                // ── entry offset (raw dp from animatables) ──
                                val entryOffsetY =
                                    if (keyIdx < totalKeys) keyAnimY[keyIdx].value else 0f
                                val entryOffsetX =
                                    if (keyIdx < totalKeys) keyAnimX[keyIdx].value else 0f

                                // ── exit offset (per-key stagger) ──
                                // Bottom rows fall first; top rows trail. Each row gets
                                // a 0.12 progress "head-start" over the row above it.
                                val exitDelay = i.toFloat() / keyRows.coerceAtLeast(1) * 0.35f
                                val perKeyExit = ((backProgress - exitDelay) / (1f - exitDelay)).coerceIn(0f, 1f)
                                // Quadratic easing → gravity-like acceleration.
                                val exitEased = perKeyExit * perKeyExit
                                val exitOffsetY = exitEased * (keyHeight * keyRows + 200f)
                                // Edge keys splay outward; center keys fall straight.
                                val centerJ = (keyCols - 1) / 2f
                                val lateralBias = if (centerJ > 0f) (j - centerJ) / centerJ else 0f
                                val exitOffsetX = exitEased * lateralBias * 80f
                                val exitAlpha = (1f - perKeyExit * 1.6f).coerceIn(0f, 1f)

                                // Bug 8 fix: keys offset off-screen during a
                                // back-progress gesture retain their original
                                // laid-out pointer rect (Modifier.offset is
                                // visual-only), so the user can still tap
                                // invisible keys — or during the 80 ms pause
                                // after `onBackInvoked`, *all* keys are
                                // invisible but every tap still types.  Drop
                                // taps while the exit is visibly underway.
                                val exitInputBlocked = backProgress > 0.05f
                                Column(
                                    modifier = Modifier
                                        .offset(
                                            x = (entryOffsetX + exitOffsetX).dp,
                                            y = (entryOffsetY + exitOffsetY).dp,
                                        )
                                        .alpha(exitAlpha)
                                        .then(
                                            if (exitInputBlocked) {
                                                Modifier.pointerInput(Unit) {
                                                    awaitPointerEventScope {
                                                        while (true) {
                                                            val event = awaitPointerEvent(
                                                                PointerEventPass.Initial,
                                                            )
                                                            event.changes.forEach { it.consume() }
                                                        }
                                                    }
                                                }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                ) {
                                    val ghostKey =
                                        if (ghostKeysEnabled) {
                                            when (mode) {
                                                KeyboardMode.MAIN, KeyboardMode.SHIFTED, KeyboardMode.CTRLED, KeyboardMode.ALTED -> {
                                                    keyboardDefinition.modes.numeric
                                                }

                                                else -> {
                                                    null
                                                }
                                            }?.arr?.getOrNull(i)?.getOrNull(j)
                                        } else {
                                            null
                                        }
                                    KeyboardKey(
                                        key = key,
                                        ghostKey = ghostKey,
                                        lastAction = lastAction,
                                        legendHeight = legendHeight,
                                        legendWidth = legendWidth,
                                        keyHeight = keyHeight,
                                        keyWidth = keyWidth,
                                        keyPadding = keyPadding,
                                        keyBorderWidth = keyBorderWidthFloat,
                                        keyRadius = cornerRadius,
                                        autoCapitalize = autoCapitalize,
                                        keyboardSettings = keyboardDefinition.settings,
                                        spacebarMultiTaps = spacebarMultiTaps,
                                        vibrateOnTap = vibrateOnTap,
                                        vibrateOnSlide = vibrateOnSlide,
                                        soundOnTap = soundOnTap,
                                        hideLetters = hideLetters,
                                        hideSymbols = hideSymbols,
                                        capsLock = capsLock,
                                        animationSpeed =
                                            settings?.animationSpeed
                                                ?: DEFAULT_ANIMATION_SPEED,
                                        animationHelperSpeed =
                                            settings?.animationHelperSpeed
                                                ?: DEFAULT_ANIMATION_HELPER_SPEED,
                                        minSwipeLength =
                                            settings?.minSwipeLength
                                                ?: DEFAULT_MIN_SWIPE_LENGTH,
                                        slideSensitivity =
                                            settings?.slideSensitivity
                                                ?: DEFAULT_SLIDE_SENSITIVITY,
                                        slideEnabled = slideEnabled,
                                        slideCursorMovementMode = slideCursorMovementMode,
                                        slideSpacebarDeadzoneEnabled = slideSpacebarDeadzoneEnabled,
                                        slideBackspaceDeadzoneEnabled = slideBackspaceDeadzoneEnabled,
                                        onToggleShiftMode = stableOnToggleShiftMode,
                                        onToggleCtrlMode = stableOnToggleCtrlMode,
                                        onToggleAltMode = stableOnToggleAltMode,
                                        onToggleNumericMode = stableOnToggleNumericMode,
                                        onToggleEmojiMode = stableOnToggleEmojiMode,
                                        onToggleClipboardMode = stableOnToggleClipboardMode,
                                        onToggleCapsLock = stableOnToggleCapsLock,
                                        onToggleHideLetters = onToggleHideLetters,
                                        onKeyEvent = stableOnKeyEvent,
                                        onAutoCapitalize = stableOnAutoCapitalize,
                                        onSwitchLanguage = stableOnSwitchLanguage,
                                        onChangePosition = onChangePosition,
                                        oppositeCaseKey =
                                            when (mode) {
                                                KeyboardMode.MAIN -> keyboardDefinition.modes.shifted
                                                KeyboardMode.SHIFTED -> keyboardDefinition.modes.main
                                                else -> null
                                            }?.arr?.getOrNull(i)?.getOrNull(j),
                                        numericKey =
                                            when (mode) {
                                                KeyboardMode.MAIN, KeyboardMode.SHIFTED, KeyboardMode.CTRLED, KeyboardMode.ALTED -> {
                                                    keyboardDefinition.modes.numeric.arr
                                                        .getOrNull(i)
                                                        ?.getOrNull(j)
                                                }

                                                else -> {
                                                    null
                                                }
                                            },
                                        dragReturnEnabled = dragReturnEnabled,
                                        circularDragEnabled = circularDragEnabled,
                                        clockwiseDragAction = clockwiseDragAction,
                                        counterclockwiseDragAction = counterclockwiseDragAction,
                                        helperFullOpacity = helperFullOpacity,
                                        opacityAlpha = opacityAlpha,
                                        enableKeyFadeout = enableKeyFadeout,
                                        keyFadeoutTimeMs = keyFadeoutTimeMs,
                                        fadeoutBorder = fadeoutBorder,
                                        predictionEngine = predictionEngine,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Column stacks the suggestion bar above the keyboard.
        // The inner Box lets Dual-mode keyboards overlap as siblings.
        val kbView = LocalView.current
        Column(modifier = (if (floatingMode) Modifier else Modifier.fillMaxWidth()).then(
            if (!helperFullOpacity) Modifier.alpha(opacityAlpha) else Modifier
        )) {
            Box(
                modifier =
                    (if (touchThroughEnabled || floatingCharEnabled) {
                        Modifier.onGloballyPositioned { coordinates ->
                            val pos = coordinates.localToWindow(Offset.Zero)
                            val size = coordinates.size
                            if (touchThroughEnabled) {
                                ctx.suggestionBarRect.set(
                                    pos.x.toInt(),
                                    pos.y.toInt(),
                                    (pos.x + size.width).toInt(),
                                    (pos.y + size.height).toInt(),
                                )
                                ctx.updateTouchableRegion()
                            }
                            if (floatingCharEnabled) {
                                val inRoot = coordinates.localToRoot(Offset.Zero)
                                val screenX: Float
                                val screenY: Float
                                if (ctx.isFloatingPanelActive) {
                                    screenX = ctx.floatingPanelScreenX + inRoot.x
                                    screenY = ctx.floatingPanelScreenY + inRoot.y
                                } else {
                                    val loc = IntArray(2)
                                    kbView.getLocationOnScreen(loc)
                                    screenX = loc[0] + inRoot.x
                                    screenY = loc[1] + inRoot.y
                                }
                                ctx.suggestionBarScreenCenterX = screenX + size.width / 2f
                                ctx.suggestionBarScreenCenterY = screenY + size.height / 2f
                            }
                        }
                    } else {
                        Modifier
                    }).then(if (helperFullOpacity) Modifier.alpha(opacityAlpha) else Modifier),
            ) {
                SuggestionBar(
                    suggestions = predictionEngine.suggestions,
                    willAutocorrect = predictionEngine.shouldAutocorrect.value,
                    onSuggestionSelected = { index ->
                        try {
                            val selectedWord = predictionEngine.suggestions
                                .getOrNull(index)?.word
                            val isRecorrection = predictionEngine.isInRecorrectionMode
                            val originalWord = if (isRecorrection) {
                                ctx.currentInputConnection
                                    ?.getSelectedText(0)?.toString() ?: ""
                            } else ""

                            when {
                                isRecorrection ->
                                    predictionEngine.applyRecorrection(index, ctx)
                                predictionEngine.gestureResultPending ->
                                    predictionEngine.onGestureSuggestionSelected(index, ctx)
                                else ->
                                    predictionEngine.onSuggestionSelected(index, ctx)
                            }

                            if (floatingCharEnabled && selectedWord != null) {
                                val barCenterX = ctx.suggestionBarScreenCenterX
                                val barCenterY = ctx.suggestionBarScreenCenterY
                                if (isRecorrection && originalWord.isNotEmpty()) {
                                    val changedChars = selectedWord.mapIndexedNotNull { i, c ->
                                        if (i >= originalWord.length || c != originalWord[i]) {
                                            c.toString()
                                        } else null
                                    }
                                    if (changedChars.isNotEmpty()) {
                                        ctx.emitFloatingCharsSequential(
                                            changedChars, barCenterX, barCenterY,
                                        )
                                    }
                                } else {
                                    ctx.emitFloatingCharsSequential(
                                        selectedWord.map { it.toString() },
                                        barCenterX, barCenterY,
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "onSuggestionSelected failed", e)
                        }
                    },
                )
            }

            Box(modifier = if (floatingMode) Modifier else Modifier.fillMaxWidth()) {
                drawKeyboard(keyboardPositionToAlignment(position), backdropEnabled, positionPadding)
                if (position == KeyboardPosition.Dual) {
                    drawKeyboard(keyboardPositionToAlignment(KeyboardPosition.Right), false, positionPadding)
                }
            }
        }
    }
}
