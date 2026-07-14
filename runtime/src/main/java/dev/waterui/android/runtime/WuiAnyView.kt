package dev.waterui.android.runtime

import android.content.Context
import android.view.View

/**
 * Tag key for storing stretch axis on inflated views.
 * Uses a unique value to avoid collision with other tags.
 */
val TAG_STRETCH_AXIS: Int get() = R.id.wui_stretch_axis
val TAG_LAYOUT_PRIORITY: Int get() = R.id.wui_layout_priority
val TAG_DYNAMIC_RANGE: Int get() = R.id.wui_dynamic_range

enum class SurfaceDynamicRange {
    STANDARD,
    HIGH
}

/**
 * Entry point that inflates an opaque `AnyView` from the Rust view tree into a
 * concrete Android [android.view.View].
 *
 * The returned View will have its stretch axis stored as a tag (TAG_STRETCH_AXIS).
 *
 * When the first non-metadata component is encountered, its environment is captured
 * by the owning [WaterUiRootView] so its color scheme can drive the Activity.
 */
fun inflateAnyView(
    context: Context,
    pointer: Long,
    environment: WuiEnvironment,
    registry: RenderRegistry = RenderRegistry.default()
): android.view.View {
    val typeId = NativeBindings.waterui_view_id(pointer).toTypeId()
    val node = WuiNode(pointer, typeId)
    val renderer = registry.resolve(typeId)

    if (renderer != null) {
        // Get stretch axis BEFORE createView - the pointer is consumed/invalidated by createView!
        // Metadata types don't implement NativeView, they propagate stretch axis from content.
        val isMetadata = registry.isMetadata(typeId)
        val stretchAxis = if (!isMetadata) {
            StretchAxis.fromInt(NativeBindings.waterui_view_stretch_axis(pointer))
        } else {
            null
        }

        // If this is the first non-metadata component, capture its env for root theme
        if (!isMetadata) {
            context.findWaterUiContext()?.captureRootEnvironment(environment)
        }

        // Create the view (this consumes the pointer via force_as_* FFI functions)
        val view = renderer.createView(context, node, environment, registry)

        // Apply stretch axis if we got one
        if (stretchAxis != null) {
            view.setTag(TAG_STRETCH_AXIS, stretchAxis)
        }
        return view
    }

    val fallbackPtr = NativeBindings.waterui_view_body(pointer, environment.raw())
    if (fallbackPtr != 0L) {
        return inflateAnyView(context, fallbackPtr, environment, registry)
    }

    error("Android backend has no renderer or body for WaterUI view type $typeId")
}

/**
 * Gets the stretch axis stored on a view during inflation.
 */
fun View.getWuiStretchAxis(): StretchAxis {
    return getTag(TAG_STRETCH_AXIS) as? StretchAxis
        ?: error("WaterUI view ${javaClass.name} is missing a valid stretch-axis tag")
}

fun View.getWuiLayoutPriority(): Int = getTag(TAG_LAYOUT_PRIORITY) as? Int ?: 0
