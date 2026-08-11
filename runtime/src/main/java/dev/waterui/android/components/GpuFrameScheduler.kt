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

    /**
     * Runs the frame the scheduler asked for, if it is still wanted.
     *
     * A callback may arrive after [pause] or [dispose]: `Choreographer` dispatches
     * its callback lists per phase, so a callback already dequeued for the current
     * frame (`CALLBACK_ANIMATION`) still fires even though `removeFrameCallback`
     * ran during an earlier phase of that same frame. `Choreographer` exposes no
     * way to tell that late callback apart from a genuinely unscheduled one, so
     * this cannot fast-fail on `framePosted`; a callback with no posted frame
     * behind it simply carries no work and is dropped.
     */
    fun onFrame() {
        if (!framePosted) {
            return
        }
        framePosted = false
        if (!active || !renderRequested) {
            return
        }
        // Cleared before rendering so that a redraw request raised *during* the
        // render (the renderer itself, or a native redraw callback delivered to the
        // main looper) survives instead of being overwritten by this frame's result.
        renderRequested = false
        val rendererWantsAnotherFrame = renderFrame()
        renderRequested = rendererWantsAnotherFrame || renderRequested
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
