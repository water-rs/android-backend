package dev.waterui.android.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class WaterUiRootViewTest {
    @Test
    fun testContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.waterui.android.runtime.test", appContext.packageName)
    }
}
