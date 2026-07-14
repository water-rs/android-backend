package dev.waterui.android.components

internal class GpuFrameScheduler(
    private val postFrame: () -> Unit,
    private val cancelFrame: () -> Unit,
    private val renderFrame: () -> Boolean
) {
    private var active = false
    private var framePosted = false
    private var renderRequested = false

    fun resume() {
        active = true
        postFrameIfNeeded()
    }

    fun pause() {
        active = false
        if (framePosted) {
            cancelFrame()
            framePosted = false
        }
    }

    fun requestFrame() {
        renderRequested = true
        postFrameIfNeeded()
    }

    fun onFrame() {
        check(framePosted) { "GpuSurface received an unscheduled frame callback" }
        framePosted = false
        if (!active || !renderRequested) {
            return
        }
        renderRequested = renderFrame()
        postFrameIfNeeded()
    }

    fun dispose() {
        pause()
        renderRequested = false
    }

    private fun postFrameIfNeeded() {
        if (active && renderRequested && !framePosted) {
            framePosted = true
            postFrame()
        }
    }
}
