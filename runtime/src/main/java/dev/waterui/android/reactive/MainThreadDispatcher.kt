package dev.waterui.android.reactive

import android.os.Handler
import android.os.Looper

internal object MainThreadDispatcher {
    private val handler = Handler(Looper.getMainLooper())

    fun post(task: () -> Unit) {
        handler.post(task)
    }
}
