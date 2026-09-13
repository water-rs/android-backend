package dev.waterui.android.runtime

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal host activity mirroring the generated app template: it acquires the
 * process runtime, then attaches a [WaterUiRootView] whose render readiness is
 * observable through its child count.
 */
class WaterUiTestActivity : AppCompatActivity() {
    lateinit var rootView: WaterUiRootView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as WaterUiTestApplication).acquireRuntime(this)
        rootView = WaterUiRootView(this)
        setContentView(rootView)
    }
}
