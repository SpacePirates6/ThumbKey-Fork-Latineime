package org.futo.inputmethod.keyboard

object ProximityInfo {
    @JvmStatic
    private external fun setProximityInfoNative(
        displayWidth: Int,
        displayHeight: Int,
        gridWidth: Int,
        gridHeight: Int,
        mostCommonKeyWidth: Int,
        mostCommonKeyHeight: Int,
        proximityCharsArray: IntArray,
        keyCount: Int,
        keyXCoordinates: IntArray,
        keyYCoordinates: IntArray,
        keyWidths: IntArray,
        keyHeights: IntArray,
        keyCharCodes: IntArray,
        sweetSpotCenterXs: FloatArray,
        sweetSpotCenterYs: FloatArray,
        sweetSpotRadii: FloatArray,
    ): Long

    @JvmStatic
    private external fun releaseProximityInfoNative(nativeProximityInfo: Long)

    fun release(handle: Long) {
        if (handle != 0L) releaseProximityInfoNative(handle)
    }

    fun create(
        displayWidth: Int,
        displayHeight: Int,
        gridWidth: Int,
        gridHeight: Int,
        mostCommonKeyWidth: Int,
        mostCommonKeyHeight: Int,
        proximityCharsArray: IntArray,
        keyCount: Int,
        keyXCoordinates: IntArray,
        keyYCoordinates: IntArray,
        keyWidths: IntArray,
        keyHeights: IntArray,
        keyCharCodes: IntArray,
        sweetSpotCenterXs: FloatArray,
        sweetSpotCenterYs: FloatArray,
        sweetSpotRadii: FloatArray,
    ): Long = setProximityInfoNative(
        displayWidth,
        displayHeight,
        gridWidth,
        gridHeight,
        mostCommonKeyWidth,
        mostCommonKeyHeight,
        proximityCharsArray,
        keyCount,
        keyXCoordinates,
        keyYCoordinates,
        keyWidths,
        keyHeights,
        keyCharCodes,
        sweetSpotCenterXs,
        sweetSpotCenterYs,
        sweetSpotRadii,
    )
}
