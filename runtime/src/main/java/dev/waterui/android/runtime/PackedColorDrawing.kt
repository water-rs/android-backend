package dev.waterui.android.runtime

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient

/**
 * Drawing entry points that take a packed color
 * ([android.graphics.Color.pack]).
 *
 * WaterUI resolves every color into extended-range linear sRGB, so a color may
 * carry components outside `[0, 1]` when the display has HDR headroom — see
 * [toColorLong]. The `long` and `int` drawing overloads are not
 * interchangeable, which is why these helpers exist rather than the call sites
 * converting to `Int`: the `long` form carries the color's
 * [android.graphics.ColorSpace] and its extended range, the `int` form is
 * 8-bit sRGB and would clamp the headroom away.
 */
/**
 * Paints with a packed color.
 *
 * `Paint#setColor(long)` preserves the color's space and extended range; see
 * the module doc for what the `int` setter would lose.
 */
fun Paint.setPackedColor(packed: Long) {
    setColor(packed)
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
): LinearGradient = LinearGradient(startX, startY, endX, endY, colors, positions, tileMode)

/** [RadialGradient] over packed colors; see [setPackedColor]. */
fun packedRadialGradient(
    centerX: Float,
    centerY: Float,
    radius: Float,
    colors: LongArray,
    positions: FloatArray,
    tileMode: Shader.TileMode
): RadialGradient = RadialGradient(centerX, centerY, radius, colors, positions, tileMode)

/** [SweepGradient] over packed colors; see [setPackedColor]. */
fun packedSweepGradient(
    centerX: Float,
    centerY: Float,
    colors: LongArray,
    positions: FloatArray
): SweepGradient = SweepGradient(centerX, centerY, colors, positions)
