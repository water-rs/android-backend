package dev.waterui.android.components

import android.os.Build
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.toColorInt

private val metadataShadowTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_shadow_id().toTypeId()
}

/**
 * Renderer for Metadata<Shadow>.
 *
 * Applies the platform elevation shadow to the wrapped view.
 */
private val metadataShadowRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_shadow(node.rawPtr)

    val container = PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)

    container.elevation = metadata.radius.dp(context)

    WuiComputed.colorFromComputed(metadata.colorPtr).also { color ->
        color.observe { resolvedColor ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val shadowColor = resolvedColor.toColorInt()
                container.outlineAmbientShadowColor = shadowColor
                container.outlineSpotShadowColor = shadowColor
            }
        }
        color.attachTo(container)
    }

    container
}

internal fun RegistryBuilder.registerWuiShadow() {
    registerMetadata({ metadataShadowTypeId }, metadataShadowRenderer)
}
