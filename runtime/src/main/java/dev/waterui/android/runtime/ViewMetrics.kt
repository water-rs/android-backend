package dev.waterui.android.runtime

import android.content.Context

fun Float.dp(context: Context): Float = this * context.resources.displayMetrics.density
