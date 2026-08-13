package dev.waterui.android.runtime

import android.graphics.Color
import android.graphics.ColorSpace
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
    val a = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
    val r = (linearToSrgb(red).coerceIn(0f, 1f) * 255f).roundToInt()
    val g = (linearToSrgb(green).coerceIn(0f, 1f) * 255f).roundToInt()
    val b = (linearToSrgb(blue).coerceIn(0f, 1f) * 255f).roundToInt()
    return Color.argb(a, r, g, b)
}

fun ResolvedColorStruct.toColorLong(): Long {
    val scale = if (headroom.isFinite() && headroom > 0f) 1f + headroom else 1f
    return Color.pack(
        red * scale,
        green * scale,
        blue * scale,
        opacity.coerceIn(0f, 1f),
        ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB),
    )
}

fun Int.withAlpha(alpha: Float): Int {
    val clamped = alpha.coerceIn(0f, 1f)
    val a = (Color.alpha(this) * clamped).roundToInt()
    return Color.argb(a, Color.red(this), Color.green(this), Color.blue(this))
}
