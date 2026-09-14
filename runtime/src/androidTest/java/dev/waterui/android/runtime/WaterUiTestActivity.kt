package dev.waterui.android.runtime

import android.app.KeyguardManager
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
        // A freshly booted emulator sits on the keyguard: without these the
        // activity's window never takes focus and Espresso refuses to interact
        // with it.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        super.onCreate(savedInstanceState)
        (application as WaterUiTestApplication).acquireRuntime(this)
        rootView = WaterUiRootView(this)
        setContentView(rootView)
    }
}
