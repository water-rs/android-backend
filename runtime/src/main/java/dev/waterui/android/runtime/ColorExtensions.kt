package dev.waterui.android.runtime

import android.graphics.Color
import android.graphics.ColorSpace
import android.os.Build
import kotlin.math.pow
import kotlin.math.roundToInt

fun ResolvedColorStruct.toColorInt(): Int {
    val scale = if (headroom.isFinite() && headroom > 0f) {
        1f + headroom
    } else {
        1f
    }
    val r = linearToSrgb(red * scale)
    val g = linearToSrgb(green * scale)
    val b = linearToSrgb(blue * scale)
    val a = opacity.coerceIn(0f, 1f)
    val r8 = (r.coerceIn(0f, 1f) * 255f).roundToInt()
    val g8 = (g.coerceIn(0f, 1f) * 255f).roundToInt()
    val b8 = (b.coerceIn(0f, 1f) * 255f).roundToInt()
    val a8 = (a * 255f).roundToInt()
    return Color.argb(a8, r8, g8, b8)
}

fun Int.withAlpha(alpha: Float): Int {
    val clamped = alpha.coerceIn(0f, 1f)
    val a = (Color.alpha(this) * clamped).roundToInt()
    return Color.argb(a, Color.red(this), Color.green(this), Color.blue(this))
}

fun ResolvedColorStruct.toLinearColorLong(): Long {
    val scale = if (headroom.isFinite() && headroom > 0f) {
        1f + headroom
    } else {
        1f
    }
    val r = red * scale
    val g = green * scale
    val b = blue * scale
    val a = opacity.coerceIn(0f, 1f)
    val useExtended = headroom > 0f || r < 0f || r > 1f || g < 0f || g > 1f || b < 0f || b > 1f
    val colorSpace = ColorSpace.get(
        if (useExtended) ColorSpace.Named.LINEAR_EXTENDED_SRGB else ColorSpace.Named.LINEAR_SRGB
    )
    val rr = if (useExtended) r else r.coerceIn(0f, 1f)
    val gg = if (useExtended) g else g.coerceIn(0f, 1f)
    val bb = if (useExtended) b else b.coerceIn(0f, 1f)
    return Color.pack(rr, gg, bb, a, colorSpace)
}

fun android.graphics.Paint.setResolvedColor(color: ResolvedColorStruct) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setColor(color.toLinearColorLong())
    } else {
        setColor(color.toColorInt())
    }
}

fun srgbToLinear(srgb: Float): Float {
    return if (srgb <= 0.04045f) {
        srgb / 12.92f
    } else {
        ((srgb + 0.055f) / 1.055f).pow(2.4f)
    }
}

private fun linearToSrgb(linear: Float): Float {
    return if (linear <= 0.0031308f) {
        linear * 12.92f
    } else {
        1.055f * linear.pow(1f / 2.4f) - 0.055f
    }
}
