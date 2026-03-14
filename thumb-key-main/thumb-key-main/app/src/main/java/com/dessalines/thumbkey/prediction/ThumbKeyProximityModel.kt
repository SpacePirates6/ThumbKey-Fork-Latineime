package com.dessalines.thumbkey.prediction

import com.dessalines.thumbkey.utils.KeyAction
import com.dessalines.thumbkey.utils.KeyItemC
import com.dessalines.thumbkey.utils.KeyboardC
import com.dessalines.thumbkey.utils.SwipeDirection

/**
 * Represents a character's position on the ThumbKey 3×3 grid.
 */
data class CharPosition(
    val row: Int,
    val col: Int,
    val swipeDir: SwipeDirection?,
)

/**
 * Custom proximity model for ThumbKey's 3×3 swipe-grid keyboard.
 *
 * This model has two roles:
 *  1. **Confusion matrix**: Returns which characters are confusable with a given char
 *     and how likely that confusion is (for generating alternative word candidates).
 *  2. **Virtual coordinates**: Maps each character to virtual x,y pixel coordinates
 *     that can be fed to the native LLM's `getSuggestionsNative` for proximity-aware
 *     prediction (the LLM's `TokenMix` uses coordinates to blend nearby-char tokens).
 *
 * ### Virtual coordinate system
 * We create a virtual 300×400 grid:
 *  - Each cell center is at `(col * 100 + 50, row * 133 + 67)`
 *  - Swipe sub-positions offset from the center by ±30px
 *  - This ensures characters on the same key are close together,
 *    adjacent keys are moderately far, and diagonal keys are farthest.
 */
class ThumbKeyProximityModel(keyboard: KeyboardC) {

    // ── Confusion-weight constants ──────────────────────────────────
    private val WEIGHT_SAME_KEY = 0.9f
    private val WEIGHT_ADJACENT_KEY = 0.4f
    private val WEIGHT_ADJACENT_SWIPE = 0.25f
    private val WEIGHT_DIAGONAL_KEY = 0.2f

    // ── Virtual coordinate constants ────────────────────────────────
    private val CELL_WIDTH = 100
    private val CELL_HEIGHT = 133
    private val SWIPE_OFFSET = 30

    // ── Internal state ──────────────────────────────────────────────
    /** Map from lowercase character → its position on the grid. */
    private val charPositions = mutableMapOf<Char, CharPosition>()
    /** Map from (row, col) → list of characters on that key. */
    private val keyChars = mutableMapOf<Pair<Int, Int>, MutableList<Char>>()
    /** Map from lowercase character → virtual (x, y) coordinate. */
    private val charCoords = mutableMapOf<Char, Pair<Int, Int>>()

    init {
        buildCharMap(keyboard)
    }

    // ── Public API ──────────────────────────────────────────────────

    fun rebuild(keyboard: KeyboardC) {
        charPositions.clear()
        keyChars.clear()
        charCoords.clear()
        buildCharMap(keyboard)
    }

    /**
     * Get the virtual (x, y) coordinate for a character.
     * Returns (-1, -1) if the character is not on the current layout.
     */
    fun getCoordinates(char: Char): Pair<Int, Int> {
        return charCoords[char.lowercaseChar()] ?: (-1 to -1)
    }

    /**
     * Generate coordinate arrays for a word (for passing to the native LLM).
     * Returns a pair of (xArray, yArray) with one coordinate per character.
     */
    fun getWordCoordinates(word: String): Pair<IntArray, IntArray> {
        val xs = IntArray(word.length)
        val ys = IntArray(word.length)
        for (i in word.indices) {
            val (x, y) = getCoordinates(word[i])
            xs[i] = x
            ys[i] = y
        }
        return xs to ys
    }

    /**
     * Returns characters confusable with [char], ordered by descending confusion weight.
     */
    fun getConfusableChars(char: Char): List<Pair<Char, Float>> {
        val lc = char.lowercaseChar()
        val pos = charPositions[lc] ?: return emptyList()

        val results = mutableListOf<Pair<Char, Float>>()

        // 1. Same-key chars (highest confusion)
        val sameKey = keyChars[pos.row to pos.col] ?: emptyList()
        for (c in sameKey) {
            if (c != lc) results.add(c to WEIGHT_SAME_KEY)
        }

        // 2. Adjacent keys (cross neighbors)
        val crossNeighbors = listOf(
            pos.row - 1 to pos.col,
            pos.row + 1 to pos.col,
            pos.row to pos.col - 1,
            pos.row to pos.col + 1,
        )
        for (neighborKey in crossNeighbors) {
            val chars = keyChars[neighborKey] ?: continue
            for (c in chars) {
                if (c != lc) {
                    val neighborPos = charPositions[c]
                    val weight = if (neighborPos?.swipeDir == null) WEIGHT_ADJACENT_KEY else WEIGHT_ADJACENT_SWIPE
                    results.add(c to weight)
                }
            }
        }

        // 3. Diagonal neighbors
        val diagonalNeighbors = listOf(
            pos.row - 1 to pos.col - 1,
            pos.row - 1 to pos.col + 1,
            pos.row + 1 to pos.col - 1,
            pos.row + 1 to pos.col + 1,
        )
        for (neighborKey in diagonalNeighbors) {
            val chars = keyChars[neighborKey] ?: continue
            for (c in chars) {
                if (c != lc) results.add(c to WEIGHT_DIAGONAL_KEY)
            }
        }

        return results
            .groupBy { it.first }
            .map { (char, entries) -> char to entries.maxOf { it.second } }
            .sortedByDescending { it.second }
    }

    /**
     * Generate alternative words by substituting confusable characters.
     */
    fun generateAlternativeWords(word: String, maxAlternatives: Int = 5): List<String> {
        if (word.length < 2) return emptyList()

        val alternatives = mutableSetOf<String>()
        val lowerWord = word.lowercase()

        for (i in lowerWord.indices) {
            val confusables = getConfusableChars(lowerWord[i])
            for ((replacement, weight) in confusables.take(3)) {
                if (weight < WEIGHT_ADJACENT_SWIPE) break
                val alt = lowerWord.replaceRange(i, i + 1, replacement.toString())
                if (alt != lowerWord) alternatives.add(alt)
                if (alternatives.size >= maxAlternatives) break
            }
            if (alternatives.size >= maxAlternatives) break
        }

        return alternatives.toList()
    }

    fun hasChar(char: Char): Boolean = char.lowercaseChar() in charPositions

    // ── Internal ────────────────────────────────────────────────────

    private fun buildCharMap(keyboard: KeyboardC) {
        for ((rowIdx, row) in keyboard.arr.withIndex()) {
            for ((colIdx, keyItem) in row.withIndex()) {
                extractCharsFromKey(keyItem, rowIdx, colIdx)
            }
        }
    }

    private fun extractCharsFromKey(keyItem: KeyItemC, row: Int, col: Int) {
        val key = row to col

        // Center character
        extractChar(keyItem.center.action, row, col, null)?.let { c ->
            keyChars.getOrPut(key) { mutableListOf() }.add(c)
        }

        // Swipe characters (8 directions)
        val swipeDirs = listOf(
            SwipeDirection.LEFT to keyItem.left,
            SwipeDirection.TOP_LEFT to keyItem.topLeft,
            SwipeDirection.TOP to keyItem.top,
            SwipeDirection.TOP_RIGHT to keyItem.topRight,
            SwipeDirection.RIGHT to keyItem.right,
            SwipeDirection.BOTTOM_RIGHT to keyItem.bottomRight,
            SwipeDirection.BOTTOM to keyItem.bottom,
            SwipeDirection.BOTTOM_LEFT to keyItem.bottomLeft,
        )

        for ((dir, keyC) in swipeDirs) {
            if (keyC == null) continue
            extractChar(keyC.action, row, col, dir)?.let { c ->
                keyChars.getOrPut(key) { mutableListOf() }.add(c)
            }
        }
    }

    private fun extractChar(action: KeyAction, row: Int, col: Int, swipeDir: SwipeDirection?): Char? {
        if (action !is KeyAction.CommitText) return null
        val text = action.text
        if (text.length != 1) return null
        val c = text[0]
        if (!c.isLetter()) return null

        val lc = c.lowercaseChar()
        if (lc !in charPositions) {
            charPositions[lc] = CharPosition(row, col, swipeDir)

            // Compute virtual coordinates
            val centerX = col * CELL_WIDTH + CELL_WIDTH / 2
            val centerY = row * CELL_HEIGHT + CELL_HEIGHT / 2
            val (ox, oy) = swipeOffset(swipeDir)
            charCoords[lc] = (centerX + ox) to (centerY + oy)
        }
        return lc
    }

    /**
     * Calculate an x,y offset from cell center based on swipe direction.
     */
    private fun swipeOffset(dir: SwipeDirection?): Pair<Int, Int> = when (dir) {
        null -> 0 to 0
        SwipeDirection.LEFT -> -SWIPE_OFFSET to 0
        SwipeDirection.RIGHT -> SWIPE_OFFSET to 0
        SwipeDirection.TOP -> 0 to -SWIPE_OFFSET
        SwipeDirection.BOTTOM -> 0 to SWIPE_OFFSET
        SwipeDirection.TOP_LEFT -> -SWIPE_OFFSET to -SWIPE_OFFSET
        SwipeDirection.TOP_RIGHT -> SWIPE_OFFSET to -SWIPE_OFFSET
        SwipeDirection.BOTTOM_LEFT -> -SWIPE_OFFSET to SWIPE_OFFSET
        SwipeDirection.BOTTOM_RIGHT -> SWIPE_OFFSET to SWIPE_OFFSET
    }
}
