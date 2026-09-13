package dev.waterui.android.runtime

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.waterui.android.ffi.WatcherJni
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the raw JNI boundary against the app crate linked into the test
 * APK: environment cloning, `waterui_app`, type-id projection, composite body
 * expansion, and handle release. Robolectric cannot load the Rust library, so
 * none of this runs on the JVM lane.
 */
@RunWith(AndroidJUnit4::class)
class JniBridgeTest {
    @Test
    fun environmentCloneAppAndViewRoundTrip() {
        ActivityScenario.launch(WaterUiTestActivity::class.java).use { scenario ->
            val activity = Waiters.activity(scenario)
            Waiters.until(description = "initial WaterUI tree to inflate") {
                activity.rootView.childCount > 0
            }

            scenario.onActivity {
                val application = it.application as WaterUiTestApplication
                val env = application.createWaterUiEnvironment()
                // waterui_app consumes the environment and the returned handles
                // must move through the FFI exactly once, on the main thread.
                val app = WatcherJni.app(env.takeRaw())
                val renderEnv = app.takeEnvironment()
                val content = app.takeContent()
                try {
                    val typeId = WatcherJni.viewId(content)
                    assertTrue(
                        "viewId returned the zero type id",
                        typeId.low != 0L || typeId.high != 0L
                    )

                    val body = WatcherJni.viewBody(content, renderEnv)
                    assertTrue("viewBody returned a null handle", body != 0L)
                    WatcherJni.dropAnyView(body)
                } finally {
                    WatcherJni.dropEnv(renderEnv)
                }
            }
        }
    }
}
