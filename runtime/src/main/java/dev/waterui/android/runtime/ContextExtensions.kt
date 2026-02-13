package dev.waterui.android.runtime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}

internal fun View.findActivity(): Activity? = context.findActivity()
