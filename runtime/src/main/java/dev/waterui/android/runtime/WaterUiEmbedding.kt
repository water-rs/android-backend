package dev.waterui.android.runtime

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Embedding surface for hosts that own their `Activity` and lifecycle: bootstraps
 * the WaterUI runtime for [activity] and hands out environments.
 *
 * The generated app performs this bootstrap in its `WaterUiApplication`; a host
 * that only mounts a [WaterUiRootView] has no such `Application`, so it creates
 * one embedding per host activity:
 *
 * ```kotlin
 * val waterui = WaterUiEmbedding(this)
 * setContentView(WaterUiRootView(this, waterui))
 * ```
 *
 * The native runtime permits one live generation per process: a second
 * `WaterUiEmbedding` constructed while the first activity is still alive throws
 * from the native bootstrap — it never swaps. Constructing one after the
 * previous activity's `ON_DESTROY` is fine, because the lifecycle observer
 * below has released that generation by then.
 *
 * All methods are confined to the main thread. Everything the embedding owns is
 * released by a lifecycle observer on the activity's `ON_DESTROY`; the host
 * neither closes nor releases the embedding itself.
 */
class WaterUiEmbedding(activity: ComponentActivity) : WaterUiRuntimeOwner {
    private var owner: Long
    private var templateEnvironment: WuiEnvironment? = null
    private var released = false

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WaterUiEmbedding must be created on the main thread"
        }
        check(!activity.isDestroyed) {
            "WaterUiEmbedding requires an activity that is not destroyed"
        }
        installWaterUiProcessEnvironment(activity)
        owner = bootstrapWaterUiRuntime(activity)
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    release()
                }
            }
        )
    }

    /**
     * Clones the embedding's `WuiEnvironment` template for one mounted root.
     *
     * The template is created lazily on first mount so a host can construct the
     * embedding before any view is attached without paying `waterui_init` twice.
     */
    override fun createWaterUiEnvironment(): WuiEnvironment {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WaterUiEmbedding.createWaterUiEnvironment must run on the main thread"
        }
        check(!released) {
            "WaterUiEmbedding cannot create an environment after its activity was destroyed"
        }
        val template = templateEnvironment ?: WuiEnvironment.create().also {
            templateEnvironment = it
        }
        return template.clone()
    }

    private fun release() {
        if (released) return
        released = true
        templateEnvironment?.close()
        templateEnvironment = null
        releaseWaterUiRuntime(owner)
        owner = 0
    }
}
