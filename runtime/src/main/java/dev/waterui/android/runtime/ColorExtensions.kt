package dev.waterui.android.runtime

import android.graphics.Color
import kotlin.math.roundToInt

fun ResolvedColorStruct.toColorInt(): Int {
    val a = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
    val r = (red.coerceIn(0f, 1f) * 255f).roundToInt()
    val g = (green.coerceIn(0f, 1f) * 255f).roundToInt()
    val b = (blue.coerceIn(0f, 1f) * 255f).roundToInt()
    return Color.argb(a, r, g, b)
}
