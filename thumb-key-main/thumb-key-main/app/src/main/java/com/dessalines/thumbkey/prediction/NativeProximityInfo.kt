package com.dessalines.thumbkey.prediction

import android.util.Log
import com.dessalines.thumbkey.utils.KeyAction
import com.dessalines.thumbkey.utils.KeyboardC
import com.dessalines.thumbkey.utils.KeyItemC
import org.futo.inputmethod.keyboard.ProximityInfo
import org.futo.inputmethod.latin.xlm.LanguageModel

class NativeProximityInfo private constructor(private val handle: Long) {

    fun getHandle(): Long = handle

    fun release() {
        if (handle != 0L) ProximityInfo.release(handle)
    }

    companion object {
        private const val TAG = "ThumbKey"
        private const val MAX_PROXIMITY_CHARS_SIZE = 16
        private const val CELL_WIDTH = 100
        private const val CELL_HEIGHT = 133
        private const val GRID_COLS = 3
        private const val GRID_ROWS = 3
        private const val DISPLAY_WIDTH = GRID_COLS * CELL_WIDTH
        private const val DISPLAY_HEIGHT = GRID_ROWS * CELL_HEIGHT

        fun fromKeyboard(keyboard: KeyboardC): NativeProximityInfo {
            return try {
                LanguageModel.loadNativeLibrary()
                buildFromKeyboard(keyboard)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "ProximityInfo native methods not available", e)
                NativeProximityInfo(0L)
            }
        }

        private fun buildFromKeyboard(keyboard: KeyboardC): NativeProximityInfo {
            val keys = mutableListOf<KeyData>()
            val proximityChars = IntArray(GRID_COLS * GRID_ROWS * MAX_PROXIMITY_CHARS_SIZE)

            for (row in 0 until minOf(GRID_ROWS, keyboard.arr.size)) {
                val rowData = keyboard.arr[row]
                for (col in 0 until minOf(GRID_COLS, rowData.size)) {
                    val keyItem = rowData[col]
                    val chars = extractLetterChars(keyItem)
                    if (chars.isEmpty()) continue

                    val primary = chars.first().code
                    val keyIdx = row * GRID_COLS + col
                    keys.add(
                        KeyData(
                            x = col * CELL_WIDTH,
                            y = row * CELL_HEIGHT,
                            width = CELL_WIDTH,
                            height = CELL_HEIGHT,
                            primaryCodePoint = primary,
                            centerX = col * CELL_WIDTH + CELL_WIDTH / 2f,
                            centerY = row * CELL_HEIGHT + CELL_HEIGHT / 2f,
                            radius = CELL_WIDTH / 2f,
                        ),
                    )

                    val slotStart = keyIdx * MAX_PROXIMITY_CHARS_SIZE
                    chars.forEachIndexed { i, c ->
                        if (i < MAX_PROXIMITY_CHARS_SIZE) {
                            proximityChars[slotStart + i] = c.code
                        }
                    }
                }
            }

            if (keys.isEmpty()) return NativeProximityInfo(0L)

            val keyCount = keys.size
            val keyX = IntArray(keyCount) { keys[it].x }
            val keyY = IntArray(keyCount) { keys[it].y }
            val keyW = IntArray(keyCount) { keys[it].width }
            val keyH = IntArray(keyCount) { keys[it].height }
            val keyCodes = IntArray(keyCount) { keys[it].primaryCodePoint }
            val sweetX = FloatArray(keyCount) { keys[it].centerX }
            val sweetY = FloatArray(keyCount) { keys[it].centerY }
            val sweetR = FloatArray(keyCount) { keys[it].radius }

            val handle = ProximityInfo.create(
                displayWidth = DISPLAY_WIDTH,
                displayHeight = DISPLAY_HEIGHT,
                gridWidth = GRID_COLS,
                gridHeight = GRID_ROWS,
                mostCommonKeyWidth = CELL_WIDTH,
                mostCommonKeyHeight = CELL_HEIGHT,
                proximityCharsArray = proximityChars,
                keyCount = keyCount,
                keyXCoordinates = keyX,
                keyYCoordinates = keyY,
                keyWidths = keyW,
                keyHeights = keyH,
                keyCharCodes = keyCodes,
                sweetSpotCenterXs = sweetX,
                sweetSpotCenterYs = sweetY,
                sweetSpotRadii = sweetR,
            )

            if (handle == 0L) {
                Log.w(TAG, "ProximityInfo native returned 0L (LLM still uses inComposeX/Y)")
            }
            return NativeProximityInfo(handle)
        }

        private data class KeyData(
            val x: Int,
            val y: Int,
            val width: Int,
            val height: Int,
            val primaryCodePoint: Int,
            val centerX: Float,
            val centerY: Float,
            val radius: Float,
        )

        private fun extractLetterChars(keyItem: KeyItemC): List<Char> {
            val result = mutableListOf<Char>()
            fun addFrom(action: KeyAction?) {
                if (action is KeyAction.CommitText && action.text.length == 1) {
                    val c = action.text[0]
                    if (c.isLetter() && c.lowercaseChar() !in result) {
                        result.add(c.lowercaseChar())
                    }
                }
            }
            addFrom(keyItem.center.action)
            val swipes = listOf(
                keyItem.left, keyItem.topLeft, keyItem.top, keyItem.topRight,
                keyItem.right, keyItem.bottomRight, keyItem.bottom, keyItem.bottomLeft,
            )
            swipes.forEach { addFrom(it?.action) }
            return result
        }
    }
}
