package dev.waterui.android.runtime

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boots the full render path on a real device: GPU runtime creation, the app
 * crate's `waterui_app`, native view inflation, and the visible output of the
 * gesture example's static texts.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeRenderTest {
    @Test
    fun rootViewRendersTheExampleTree() {
        ActivityScenario.launch(WaterUiTestActivity::class.java).use { scenario ->
            val activity = Waiters.activity(scenario)
            Waiters.until(description = "WaterUiRootView to inflate its first child") {
                activity.rootView.childCount > 0
            }

            onView(withText("WaterUI Gesture Examples")).check(matches(isDisplayed()))
            onView(withText("Tap Gesture")).check(matches(isDisplayed()))
            onView(withText(Waiters.textIs("Tap count: 0"))).check(matches(isDisplayed()))
        }
    }
}
