package dev.waterui.android.runtime

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `Metadata<Focused>` hands a `Binding<bool>` to exactly one platform anchor in
 * the wrapped subtree. These tests pin the two halves of that contract:
 * [requireSingleWuiFocusTarget] finding and counting anchors, and
 * [WuiFocusedBindingController] keeping the binding and the platform's focus
 * state equal in both directions without echoing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusTargetTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    /** A focus target that records requests and emits focus changes itself. */
    private class FakeFocusTarget(override val view: View) : WuiFocusTarget {
        var platformFocus = false
            private set
        var requestCount = 0
            private set
        var clearCount = 0
            private set
        private val observers = LinkedHashMap<Int, (Boolean) -> Unit>()
        private var nextObserverId = 0

        val observerCount: Int get() = observers.size

        override fun requestPlatformFocus() {
            requestCount += 1
            setPlatformFocus(true)
        }

        override fun clearPlatformFocus() {
            clearCount += 1
            setPlatformFocus(false)
        }

        override fun observePlatformFocusChanges(onChange: (Boolean) -> Unit): Closeable {
            val observerId = nextObserverId
            nextObserverId += 1
            observers[observerId] = onChange
            return Closeable { observers.remove(observerId) }
        }

        fun setPlatformFocus(hasFocus: Boolean) {
            if (platformFocus == hasFocus) return
            platformFocus = hasFocus
            observers.values.toList().forEach { it(hasFocus) }
        }
    }

    /** A `FocusStateBinding` that behaves like the native side: writes echo. */
    private class FakeFocusBinding(initial: Boolean) : FocusStateBinding {
        var current = initial
        val writes = mutableListOf<Boolean>()
        var closed = false
        private var observer: ((Boolean) -> Unit)? = null

        override fun observe(onChange: (Boolean) -> Unit) {
            observer = onChange
            onChange(current)
        }

        override fun set(focused: Boolean) {
            writes += focused
            emit(focused)
        }

        /** Simulates a write that originated on the Rust side. */
        fun emit(focused: Boolean) {
            current = focused
            observer?.invoke(focused)
        }

        override fun close() {
            closed = true
            observer = null
        }
    }

    private data class Harness(
        val container: FrameLayout,
        val target: FakeFocusTarget,
        val binding: FakeFocusBinding,
        val controller: WuiFocusedBindingController
    )

    // -- requireSingleWuiFocusTarget ----------------------------------------

    @Test
    fun subtreeWithoutAnchorFails() {
        val container = FrameLayout(context()).apply {
            addView(View(context()))
        }

        try {
            container.requireSingleWuiFocusTarget()
            fail("expected an anchor-count failure")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("found 0"))
        }
    }

    @Test
    fun anchorNestedBelowChildrenResolves() {
        val anchor = EditText(context())
        val target = WuiTextInputFocusTarget(anchor)
        val container = FrameLayout(context()).apply {
            addView(FrameLayout(context()).apply {
                addView(anchor.apply { installWuiFocusTarget(target) })
            })
        }

        assertSame(target, container.requireSingleWuiFocusTarget())
    }

    @Test
    fun subtreeWithTwoAnchorsFails() {
        val container = FrameLayout(context()).apply {
            addView(EditText(context()).apply {
                installWuiFocusTarget(WuiTextInputFocusTarget(this))
            })
            addView(FrameLayout(context()).apply {
                addView(EditText(context()).apply {
                    installWuiFocusTarget(WuiTextInputFocusTarget(this))
                })
            })
        }

        try {
            container.requireSingleWuiFocusTarget()
            fail("expected an anchor-count failure")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("found 2"))
        }
    }

    // -- WuiTextInputFocusTarget ---------------------------------------------

    @Test
    fun platformFocusChangesReachEveryObserver() {
        val activity = activity()
        val editText = EditText(activity)
        activity.setContentView(editText)

        val target = WuiTextInputFocusTarget(editText)
        val seen = mutableListOf<MutableList<Boolean>>()
        seen.add(mutableListOf())
        seen.add(mutableListOf())
        val closers = seen.map { values ->
            target.observePlatformFocusChanges { value -> values.add(value) }
        }

        target.onFocusChange(editText, true)
        target.onFocusChange(editText, false)

        assertEquals(listOf(true, false), seen[0])
        assertEquals(seen[0], seen[1])

        closers[0].close()
        target.onFocusChange(editText, true)
        assertEquals(listOf(true, false, true), seen[1])
        assertEquals(listOf(true, false), seen[0])
    }

    // -- WuiFocusedBindingController -----------------------------------------

    private fun newHarness(activity: Activity, initialBinding: Boolean = false): Harness {
        val anchor = EditText(activity)
        val target = FakeFocusTarget(anchor)
        val container = FrameLayout(activity).apply { addView(anchor) }
        val binding = FakeFocusBinding(initialBinding)
        val controller = WuiFocusedBindingController(container, target, binding)
        return Harness(container, target, binding, controller)
    }

    @Test
    fun bindingTrueRequestsPlatformFocusOnceAttached() {
        val activity = activity()
        val harness = newHarness(activity, initialBinding = true)

        assertEquals(
            "detached container must not request focus", 0, harness.target.requestCount
        )

        activity.setContentView(harness.container)

        assertEquals(1, harness.target.requestCount)
        assertTrue(harness.target.platformFocus)
    }

    @Test
    fun bindingChangeWhileAttachedReachesPlatform() {
        val activity = activity()
        val harness = newHarness(activity)
        activity.setContentView(harness.container)

        assertFalse(harness.target.platformFocus)
        harness.binding.emit(true)
        assertTrue(harness.target.platformFocus)
        harness.binding.emit(false)
        assertFalse(harness.target.platformFocus)
    }

    @Test
    fun platformFocusGainWritesBindingWithoutEchoing() {
        val activity = activity()
        val harness = newHarness(activity)
        activity.setContentView(harness.container)

        harness.target.setPlatformFocus(true)

        assertEquals(listOf(true), harness.binding.writes)
        // The write echoes back through the observer; the converged state must
        // not trigger a second platform request.
        assertEquals(1, harness.target.requestCount)
    }

    @Test
    fun platformFocusLossWritesBindingFalse() {
        val activity = activity()
        val harness = newHarness(activity, initialBinding = true)
        activity.setContentView(harness.container)

        assertTrue(harness.target.platformFocus)
        harness.target.setPlatformFocus(false)

        assertEquals(listOf(false), harness.binding.writes)
        assertEquals(1, harness.target.clearCount)
    }

    @Test
    fun closeDetachesBothDirections() {
        val activity = activity()
        val harness = newHarness(activity)
        activity.setContentView(harness.container)

        harness.controller.close()

        assertTrue(harness.binding.closed)
        assertEquals(0, harness.target.observerCount)

        harness.target.setPlatformFocus(true)
        harness.binding.emit(false)
        assertEquals(emptyList<Boolean>(), harness.binding.writes)
        assertEquals(0, harness.target.requestCount)
    }
}
