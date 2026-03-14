package com.dessalines.thumbkey.prediction

enum class CapsMode { NONE, FIRST_LETTER, ALL_CAPS, AUTO }

class WordComposer {
    private val word = StringBuilder()
    private var xCoords = IntArray(MAX_WORD_LENGTH)
    private var yCoords = IntArray(MAX_WORD_LENGTH)

    private var _cursorPosition = 0
    var previousWord: String? = null

    fun addChar(char: Char, x: Int = -1, y: Int = -1) {
        if (word.length >= MAX_WORD_LENGTH) return
        word.append(char)
        val idx = word.length - 1
        xCoords[idx] = x
        yCoords[idx] = y
        _cursorPosition = word.length
    }

    fun deleteChar(): Char? {
        if (word.isEmpty()) return null
        val idx = word.length - 1
        val ch = word[idx]
        word.deleteCharAt(idx)
        if (_cursorPosition > word.length) _cursorPosition = word.length
        return ch
    }

    fun reset() {
        word.clear()
        _cursorPosition = 0
        previousWord = null
    }

    fun setComposingWord(text: String) {
        word.clear()
        _cursorPosition = 0
        val take = minOf(text.length, MAX_WORD_LENGTH)
        for (i in 0 until take) addChar(text[i], -1, -1)
    }

    val typedWord: String get() = word.toString()
    val length: Int get() = word.length
    val isEmpty: Boolean get() = word.isEmpty()
    val isNotEmpty: Boolean get() = word.isNotEmpty()

    fun getXCoordinates(): IntArray = xCoords.copyOf(word.length)
    fun getYCoordinates(): IntArray = yCoords.copyOf(word.length)

    var capsMode: CapsMode = CapsMode.NONE
        private set

    fun setCapsMode(mode: CapsMode) {
        capsMode = mode
    }

    val hasDigits: Boolean get() = word.any { it.isDigit() }
    val hasDashes: Boolean get() = word.any { it == '-' || it == '–' || it == '—' }

    val cursorPosition: Int get() = _cursorPosition

    fun moveCursor(delta: Int): Boolean {
        val newPos = _cursorPosition + delta
        if (newPos < 0 || newPos > word.length) return false
        _cursorPosition = newPos
        return true
    }

    fun insertCharAt(pos: Int, char: Char, x: Int = -1, y: Int = -1) {
        if (pos < 0 || pos > word.length || word.length >= MAX_WORD_LENGTH) return
        word.insert(pos, char)
        for (i in word.length - 1 downTo pos + 1) {
            xCoords[i] = xCoords[i - 1]
            yCoords[i] = yCoords[i - 1]
        }
        xCoords[pos] = x
        yCoords[pos] = y
        if (_cursorPosition >= pos) _cursorPosition++
    }

    fun getCharAt(pos: Int): Char? =
        if (pos in 0 until word.length) word[pos] else null

    companion object {
        private const val MAX_WORD_LENGTH = 50
    }
}