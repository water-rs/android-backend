package dev.waterui.android.runtime

import android.app.Activity
import android.app.Application

/**
 * Test-side counterpart of the `WaterUiApplication` class in the generated app
 * template. `waterui_init` may run once per process, so the Application owns
 * the one real environment and hands clones to the activity and to tests.
 */
class WaterUiTestApplication : Application(), WaterUiRuntimeOwner {
    private var runtimeOwner: Long = 0
    private var processEnvironment: WuiEnvironment? = null

    fun acquireRuntime(activity: Activity) {
        if (runtimeOwner == 0L) {
            runtimeOwner = bootstrapWaterUiRuntime(activity)
        }
        if (processEnvironment == null) {
            processEnvironment = WuiEnvironment.create()
        }
    }

    override fun createWaterUiEnvironment(): WuiEnvironment {
        val environment = checkNotNull(processEnvironment) {
            "WaterUiTestApplication.acquireRuntime must run before environments are requested"
        }
        return environment.clone()
    }
}
