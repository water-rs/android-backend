package dev.waterui.android.components

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuFrameSchedulerTest {
    @Test
    fun requestsAreCoalescedAndIdleFramesStop() {
        var posted = 0
        var cancelled = 0
        var renders = 0
        val scheduler = GpuFrameScheduler(
            postFrame = { posted += 1 },
            cancelFrame = { cancelled += 1 },
            renderFrame = {
                renders += 1
                false
            }
        )

        scheduler.resume()
        scheduler.requestFrame()
        scheduler.requestFrame()

        assertEquals(1, posted)
        scheduler.onFrame()
        assertEquals(1, renders)
        assertEquals(1, posted)
        assertEquals(0, cancelled)
    }

    @Test
    fun rendererCanRequestAnotherFrame() {
        var posted = 0
        var renders = 0
        val scheduler = GpuFrameScheduler(
            postFrame = { posted += 1 },
            cancelFrame = {},
            renderFrame = {
                renders += 1
                renders == 1
            }
        )

        scheduler.resume()
        scheduler.requestFrame()
        scheduler.onFrame()
        scheduler.onFrame()

        assertEquals(2, posted)
        assertEquals(2, renders)
    }

    @Test
    fun requestRaisedDuringRenderSurvivesTheFrameResult() {
        var posted = 0
        var renders = 0
        lateinit var scheduler: GpuFrameScheduler
        scheduler = GpuFrameScheduler(
            postFrame = { posted += 1 },
            cancelFrame = {},
            renderFrame = {
                renders += 1
                if (renders == 1) {
                    // A native redraw callback landing while the frame renders.
                    scheduler.requestFrame()
                }
                false
            }
        )

        scheduler.resume()
        scheduler.requestFrame()
        scheduler.onFrame()

        assertEquals(2, posted)
        scheduler.onFrame()
        assertEquals(2, renders)
    }

    @Test
    fun lateFrameCallbackAfterPauseIsDropped() {
        var renders = 0
        val scheduler = GpuFrameScheduler(
            postFrame = {},
            cancelFrame = {},
            renderFrame = {
                renders += 1
                false
            }
        )

        scheduler.resume()
        scheduler.requestFrame()
        scheduler.pause()
        // Choreographer had already dequeued this callback when the cancel ran.
        scheduler.onFrame()

        assertEquals(0, renders)
    }

    @Test
    fun lateFrameCallbackAfterDisposeIsDropped() {
        var renders = 0
        val scheduler = GpuFrameScheduler(
            postFrame = {},
            cancelFrame = {},
            renderFrame = {
                renders += 1
                false
            }
        )

        scheduler.resume()
        scheduler.requestFrame()
        scheduler.dispose()
        scheduler.onFrame()

        assertEquals(0, renders)
    }

    @Test
    fun resumeAfterLateFrameCallbackStillRendersTheOutstandingRequest() {
        var posted = 0
        var renders = 0
        val scheduler = GpuFrameScheduler(
            postFrame = { posted += 1 },
            cancelFrame = {},
            renderFrame = {
                renders += 1
                false
            }
        )

        scheduler.resume()
        scheduler.requestFrame()
        scheduler.pause()
        scheduler.onFrame()
        scheduler.resume()
        scheduler.onFrame()

        assertEquals(2, posted)
        assertEquals(1, renders)
    }

    @Test
    fun pauseCancelsPostedFrameAndResumeRestoresIt() {
        var posted = 0
        var cancelled = 0
        val scheduler = GpuFrameScheduler(
            postFrame = { posted += 1 },
            cancelFrame = { cancelled += 1 },
            renderFrame = { false }
        )

        scheduler.resume()
        scheduler.requestFrame()
        scheduler.pause()
        scheduler.resume()

        assertEquals(2, posted)
        assertEquals(1, cancelled)
    }
}
