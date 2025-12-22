package dev.waterui.android.runtime

import android.content.Context
import android.view.View
import android.widget.TextView

/**
 * Tag key for storing stretch axis on inflated views.
 * Uses a unique value to avoid collision with other tags.
 */
const val TAG_STRETCH_AXIS = 0x57554901 // "WUI\x01" as int

/**
 * Entry point that inflates an opaque `AnyView` from the Rust view tree into a
 * concrete Android [android.view.View].
 *
 * The returned View will have its stretch axis stored as a tag (TAG_STRETCH_AXIS).
 *
 * The returned view uses the provided environment for rendering and layout.
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

    return MissingComponentView(context, typeId)
}

/**
 * Gets the stretch axis stored on a view during inflation.
 * Returns NONE if the view wasn't inflated by WaterUI or is missing the tag.
 */
fun View.getWuiStretchAxis(): StretchAxis {
    return getTag(TAG_STRETCH_AXIS) as? StretchAxis ?: StretchAxis.NONE
}

private class MissingComponentView(
    context: Context,
    typeId: WuiTypeId
) : TextView(context) {
    init {
        text = "Missing component for typeId=${'$'}typeId"
    }
}
