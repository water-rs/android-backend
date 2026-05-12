package dev.waterui.android.runtime

import android.view.View
import java.io.Closeable

fun View.disposeWith(closeable: Closeable) {
    disposeWith { closeable.close() }
}

fun View.disposeWith(action: () -> Unit) {
    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}

        override fun onViewDetachedFromWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            action()
        }
    }
    addOnAttachStateChangeListener(listener)
}
