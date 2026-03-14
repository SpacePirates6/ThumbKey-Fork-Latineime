package com.dessalines.thumbkey.prediction

data class GestureResult(
    val decodedWord: String,
    val pathXCoordinates: IntArray,
    val pathYCoordinates: IntArray,
    val cellsVisited: List<Pair<Int, Int>>,
    val pathLength: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GestureResult) return false
        if (decodedWord != other.decodedWord) return false
        if (!pathXCoordinates.contentEquals(other.pathXCoordinates)) return false
        if (!pathYCoordinates.contentEquals(other.pathYCoordinates)) return false
        if (cellsVisited != other.cellsVisited) return false
        if (pathLength != other.pathLength) return false
        return true
    }

    override fun hashCode(): Int {
        var result = decodedWord.hashCode()
        result = 31 * result + pathXCoordinates.contentHashCode()
        result = 31 * result + pathYCoordinates.contentHashCode()
        result = 31 * result + cellsVisited.hashCode()
        result = 31 * result + pathLength
        return result
    }
}

class GestureDecoder {

    private var keyWidth: Float = 0f
    private var keyHeight: Float = 0f
    private var rows: Int = 3
    private var cols: Int = 3
    private var charMap: Map<Pair<Int, Int>, Char> = emptyMap()

    private val pathPoints = mutableListOf<Pair<Float, Float>>()
    private var gestureActive: Boolean = false

    fun startGesture(x: Float, y: Float) {
        pathPoints.clear()
        pathPoints.add(x to y)
        gestureActive = true
    }

    fun addPoint(x: Float, y: Float) {
        if (!gestureActive) return
        pathPoints.add(x to y)
    }

    fun endGesture(): GestureResult? {
        if (!gestureActive) return null
        gestureActive = false

        if (pathPoints.size < 5) return null
        if (keyWidth <= 0f || keyHeight <= 0f) return null
        if (charMap.isEmpty()) return null

        val cellsVisited = mutableListOf<Pair<Int, Int>>()
        var lastCell: Pair<Int, Int>? = null

        for ((x, y) in pathPoints) {
            val row = (y / keyHeight).toInt().coerceIn(0, rows - 1)
            val col = (x / keyWidth).toInt().coerceIn(0, cols - 1)
            val cell = row to col
            if (cell != lastCell) {
                cellsVisited.add(cell)
                lastCell = cell
            }
        }

        if (cellsVisited.size < 3) return null

        val firstPoint = pathPoints.first()
        val lastPoint = pathPoints.last()
        val spanDist = kotlin.math.sqrt(
            (lastPoint.first - firstPoint.first) * (lastPoint.first - firstPoint.first) +
                (lastPoint.second - firstPoint.second) * (lastPoint.second - firstPoint.second)
        )
        if (spanDist < 2.5f * keyWidth) return null

        val decodedWord = buildString {
            for (cell in cellsVisited) {
                charMap[cell]?.let { append(it) }
            }
        }

        val pathXCoordinates = IntArray(cellsVisited.size) { i ->
            val (_, col) = cellsVisited[i]
            (col * keyWidth + keyWidth / 2).toInt()
        }
        val pathYCoordinates = IntArray(cellsVisited.size) { i ->
            val (row, _) = cellsVisited[i]
            (row * keyHeight + keyHeight / 2).toInt()
        }

        return GestureResult(
            decodedWord = decodedWord,
            pathXCoordinates = pathXCoordinates,
            pathYCoordinates = pathYCoordinates,
            cellsVisited = cellsVisited,
            pathLength = pathPoints.size,
        )
    }

    fun isGestureActive(): Boolean = gestureActive

    fun cancelGesture() {
        pathPoints.clear()
        gestureActive = false
    }

    val keyWidthPx: Float get() = keyWidth
    val keyHeightPx: Float get() = keyHeight

    fun setGridDimensions(keyWidth: Float, keyHeight: Float, rows: Int = 3, cols: Int = 3) {
        this.keyWidth = keyWidth
        this.keyHeight = keyHeight
        this.rows = rows
        this.cols = cols
    }

    fun setKeyCharMap(charMap: Map<Pair<Int, Int>, Char>) {
        this.charMap = charMap
    }
}
