package dev.waterui.android.runtime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

internal fun Context.requireActivity(): Activity {
    var current = this
    while (true) {
        when (current) {
            is Activity -> return current
            is ContextWrapper -> {
                val base = current.baseContext
                check(base !== current) { "ContextWrapper cannot wrap itself" }
                current = base
            }
            else -> error("WaterUI requires a Context backed by an Activity, got ${current::class.java.name}")
        }
    }
}

internal class WaterUiContext(base: Context) : ContextWrapper(base) {
    private var rootEnvironmentConsumer: ((WuiEnvironment) -> Unit)? = null

    fun setRootEnvironmentConsumer(consumer: (WuiEnvironment) -> Unit) {
        check(rootEnvironmentConsumer == null) { "WaterUiContext root consumer was already installed" }
        rootEnvironmentConsumer = consumer
    }

    fun captureRootEnvironment(env: WuiEnvironment) {
        requireNotNull(rootEnvironmentConsumer) {
            "WaterUiRootView did not install its root environment consumer"
        }(env)
    }
}

internal fun Context.findWaterUiContext(): WaterUiContext? {
    var current = this
    while (current is ContextWrapper) {
        if (current is WaterUiContext) {
            return current
        }
        val base = current.baseContext
        check(base !== current) { "ContextWrapper cannot wrap itself" }
        current = base
    }
    return null
}
