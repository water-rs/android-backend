package dev.waterui.android.runtime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail

/** Shared waiting and inspection helpers for the instrumentation tests. */
object Waiters {
    private const val POLL_INTERVAL_MS = 50L
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /**
     * Re-evaluates [condition] until it holds or [timeoutMs] elapses.
     *
     * Readiness here crosses threads the instrumentation framework cannot idle
     * on: the Rust GPU runtime finishes on its own thread and posts the first
     * rendered view back to the main thread, so Espresso's built-in
     * synchronization would return before the tree exists. Polling the real
     * condition — a concrete view property — is the only observable signal.
     */
    fun until(timeoutMs: Long = DEFAULT_TIMEOUT_MS, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) {
                return
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                fail("timed out after ${timeoutMs}ms waiting for: $description")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /** Returns the launched activity once `onCreate` has run. */
    fun activity(scenario: ActivityScenario<WaterUiTestActivity>): WaterUiTestActivity {
        var activity: WaterUiTestActivity? = null
        scenario.onActivity { activity = it }
        return checkNotNull(activity)
    }

    /** Depth-first search for the first [TextView] whose text equals [text]. */
    fun findTextView(root: View, text: String): TextView? {
        if (root is TextView && root.text.toString() == text) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findTextView(root.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }
}
