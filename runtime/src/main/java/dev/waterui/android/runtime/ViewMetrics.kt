package dev.waterui.android.runtime

import android.content.Context
import android.util.TypedValue

fun Float.dp(context: Context): Float = this * context.resources.displayMetrics.density

/**
 * Pixels per sp unit, honoring the user's font-scale accessibility setting.
 * Text sizes cross the theme bridge in sp; every conversion to pixels (and
 * back) must go through this factor so the UI scales with the system setting
 * the way Compose's sp-based typography does.
 */
fun Context.pxPerSp(): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
