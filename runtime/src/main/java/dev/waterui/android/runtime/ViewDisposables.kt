package dev.waterui.android.runtime

import android.view.View
import android.view.ViewGroup
import java.io.Closeable

private class ViewDisposables {
    private val actions = mutableListOf<() -> Unit>()
    private var disposed = false

    fun add(action: () -> Unit) {
        check(!disposed) { "cannot register a resource on a disposed WaterUI view" }
        actions += action
    }

    fun dispose() {
        check(!disposed) { "WaterUI view resources were disposed more than once" }
        disposed = true
        actions.asReversed().forEach { it() }
    }
}

fun View.disposeWith(closeable: Closeable) {
    disposeWith { closeable.close() }
}

fun View.disposeWith(action: () -> Unit) {
    val disposables = getTag(R.id.wui_disposables) as? ViewDisposables ?: ViewDisposables().also {
        setTag(R.id.wui_disposables, it)
    }
    disposables.add(action)
}

/** Releases one semantic WaterUI view tree without treating temporary detach as destruction. */
fun View.disposeWuiTree() {
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).disposeWuiTree()
        }
    }
    (getTag(R.id.wui_disposables) as? ViewDisposables)?.dispose()
}

fun ViewGroup.disposeAndRemoveView(view: View) {
    view.disposeWuiTree()
    removeView(view)
}

fun ViewGroup.disposeAndRemoveAllViews() {
    for (index in 0 until childCount) {
        getChildAt(index).disposeWuiTree()
    }
    removeAllViews()
}
