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

/**
 * `AppliedFilter` and `ViewEffect` both filter the *rendered pixels* of a subtree,
 * so a backend can only implement them once it can capture a native view hierarchy
 * into a GPU texture and feed that texture to the filter pipeline. The Apple backend
 * has that capture and implements both; the Android backend has no view capture yet,
 * so the two views are an explicitly asymmetric primitive rather than something to
 * approximate — a silently unfiltered subtree would be a wrong picture, not a
 * degraded one.
 *
 * Implementing them here means capturing the subtree (`RenderEffect` for the effects
 * Android's own render pipeline can express, otherwise a rendered-to-texture capture
 * fenced against the filter pass) and handing the result to the shared filter
 * pipeline, matching what the Apple backend's capture path already does.
 */
private const val VIEW_CAPTURE_UNSUPPORTED =
    "Android has no native view capture yet, so the subtree cannot be rendered to a " +
        "texture for the filter pipeline; Apple implements this via native view capture"

private val unsupportedAppliedFilter = WuiRenderer { _, _, _, _ ->
    error("AppliedFilter is unsupported on Android: $VIEW_CAPTURE_UNSUPPORTED")
}

private val unsupportedViewEffect = WuiRenderer { _, _, _, _ ->
    error("ViewEffect is unsupported on Android: $VIEW_CAPTURE_UNSUPPORTED")
}

internal fun RegistryBuilder.registerUnsupportedGraphics() {
    registerMetadata({ appliedFilterTypeId }, unsupportedAppliedFilter)
    register({ viewEffectTypeId }, unsupportedViewEffect)
}
