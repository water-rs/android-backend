package dev.waterui.android.runtime

import android.content.Context
import android.widget.TextView

/**
 * Entry point that inflates an opaque `AnyView` from the Rust view tree into a
 * concrete Android [android.view.View].
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
        return renderer.createView(context, node, environment, registry)
    }

    val fallbackPtr = NativeBindings.waterui_view_body(pointer, environment.raw())
    if (fallbackPtr != 0L) {
        return inflateAnyView(context, fallbackPtr, environment, registry)
    }

    return MissingComponentView(context, typeId)
}

private class MissingComponentView(
    context: Context,
    typeId: WuiTypeId
) : TextView(context) {
    init {
        text = "Missing component for typeId=${'$'}typeId"
    }
}
