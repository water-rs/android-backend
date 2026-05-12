package dev.waterui.android.runtime

import android.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

private fun linearToSrgb(linear: Float): Float {
    return if (linear <= 0.003_130_8f) {
        linear * 12.92f
    } else {
        1.055f * linear.pow(1f / 2.4f) - 0.055f
    }
}

fun ResolvedColorStruct.toColorInt(): Int {
    val scale = if (headroom.isFinite() && headroom > 0f) 1f + headroom else 1f
    val a = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
    val r = (linearToSrgb(red * scale).coerceIn(0f, 1f) * 255f).roundToInt()
    val g = (linearToSrgb(green * scale).coerceIn(0f, 1f) * 255f).roundToInt()
    val b = (linearToSrgb(blue * scale).coerceIn(0f, 1f) * 255f).roundToInt()
    return Color.argb(a, r, g, b)
}

fun Int.withAlpha(alpha: Float): Int {
    val clamped = alpha.coerceIn(0f, 1f)
    val a = (Color.alpha(this) * clamped).roundToInt()
    return Color.argb(a, Color.red(this), Color.green(this), Color.blue(this))
}
