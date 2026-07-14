package dev.waterui.android.components

import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId

private val appliedFilterTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_applied_filter_id().toTypeId()
}

private val viewEffectTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_view_effect_id().toTypeId()
}

private val unsupportedAppliedFilter = WuiRenderer { _, _, _, _ ->
    error("AppliedFilter is unsupported on Android because native view capture is not implemented")
}

private val unsupportedViewEffect = WuiRenderer { _, _, _, _ ->
    error("ViewEffect is unsupported on Android because native view capture is not implemented")
}

internal fun RegistryBuilder.registerUnsupportedGraphics() {
    registerMetadata({ appliedFilterTypeId }, unsupportedAppliedFilter)
    register({ viewEffectTypeId }, unsupportedViewEffect)
}
