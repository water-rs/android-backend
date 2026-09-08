package dev.waterui.android.runtime

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import androidx.core.graphics.toColorInt

/**
 * Drawing entry points that take a packed color ([Color.pack]).
 *
 * WaterUI resolves every color into extended-range linear sRGB, so a color may
 * carry components outside `[0, 1]` when the display has HDR headroom — see
 * [toColorLong]. The drawing overloads that preserve that information,
 * `Paint#setColor(long)` and the `long[]` gradient constructors, all arrived in
 * API 29, while this module supports API 26.
 *
 * The `long` and `int` overloads are not interchangeable, which is why these
 * helpers branch on the platform version instead of simply calling the older
 * one: the `long` form carries the color's [android.graphics.ColorSpace] and
 * its extended range, the `int` form is 8-bit sRGB. API 26-28 has no drawing
 * API that accepts a color space at all, so there the packed color is converted
 * with [toColorInt], which converts to sRGB and clamps. Colors inside the
 * sRGB gamut are unchanged by that conversion; HDR headroom is exactly the part
 * the older pipeline cannot represent.
 */
/**
 * Paints with a packed color.
 *
 * `Paint#setColor(long)` arrived in API 29; below it the color is converted
 * with [toColorInt] (see the module doc for what that loses).
 */
fun Paint.setPackedColor(packed: Long) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        setColor(packed)
    } else {
        color = packed.toColorInt()
    }
}

/** [LinearGradient] over packed colors; see [setPackedColor]. */
fun packedLinearGradient(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    colors: LongArray,
    positions: FloatArray,
    tileMode: Shader.TileMode
): LinearGradient {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        LinearGradient(startX, startY, endX, endY, colors, positions, tileMode)
    } else {
        LinearGradient(startX, startY, endX, endY, colors.toArgbArray(), positions, tileMode)
    }
}

/** [RadialGradient] over packed colors; see [setPackedColor]. */
fun packedRadialGradient(
    centerX: Float,
    centerY: Float,
    radius: Float,
    colors: LongArray,
    positions: FloatArray,
    tileMode: Shader.TileMode
): RadialGradient {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        RadialGradient(centerX, centerY, radius, colors, positions, tileMode)
    } else {
        RadialGradient(centerX, centerY, radius, colors.toArgbArray(), positions, tileMode)
    }
}

/** [SweepGradient] over packed colors; see [setPackedColor]. */
fun packedSweepGradient(
    centerX: Float,
    centerY: Float,
    colors: LongArray,
    positions: FloatArray
): SweepGradient {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        SweepGradient(centerX, centerY, colors, positions)
    } else {
        SweepGradient(centerX, centerY, colors.toArgbArray(), positions)
    }
}

private fun LongArray.toArgbArray(): IntArray = IntArray(size) { index -> this[index].toColorInt() }
