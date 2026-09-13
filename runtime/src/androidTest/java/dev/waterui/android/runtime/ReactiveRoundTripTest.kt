package dev.waterui.android.runtime

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the reactive loop end to end: a real MotionEvent crosses JNI into the
 * Rust gesture action, the action mutates the `Binding`, and the signal's
 * watcher crosses JNI back to update the rendered `TextView`.
 */
@RunWith(AndroidJUnit4::class)
class ReactiveRoundTripTest {
    @Test
    fun tapGestureMutatesTheRustBindingAndUpdatesTheRenderedText() {
        ActivityScenario.launch(WaterUiTestActivity::class.java).use { scenario ->
            val activity = Waiters.activity(scenario)
            Waiters.until(description = "WaterUiRootView to inflate its first child") {
                activity.rootView.childCount > 0
            }
            onView(withText("Tap count: 0")).check(matches(isDisplayed()))

            onView(withText("Tap Me!")).perform(click())

            Waiters.until(description = "the tap to reach the counter binding") {
                Waiters.findTextView(activity.rootView, "Tap count: 1") != null
            }
            onView(withText("Tap count: 1")).check(matches(isDisplayed()))
        }
    }
}
