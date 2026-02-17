package dev.waterui.android.components

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal object SharedGpuRenderExecutor {
    private val lock = Any()
    private var executor: ExecutorService? = null
    private var refCount: Int = 0

    fun acquire(): ExecutorService {
        synchronized(lock) {
            val current = executor
            if (current == null || current.isShutdown || current.isTerminated) {
                val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                executor = Executors.newFixedThreadPool(threadCount) { runnable ->
                    Thread(runnable, "WaterUI-GpuRenderer").apply {
                        isDaemon = true
                        priority = Thread.NORM_PRIORITY
                    }
                }
            }
            refCount += 1
            return executor!!
        }
    }

    fun release() {
        synchronized(lock) {
            if (refCount > 0) {
                refCount -= 1
            }
            if (refCount == 0) {
                executor?.shutdown()
                executor = null
            }
        }
    }
}
