package dev.waterui.android.components

import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
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

    // `colorPtr` is an unresolved WuiColor, not a computed. It has to be
    // resolved against this environment before it can be read as one; handing
    // the raw colour to `colorFromComputed` makes Rust reinterpret it as a
    // signal and dereference whatever the misread layout points at.
    val resolvedPtr = NativeBindings.waterui_resolve_color(metadata.colorPtr, env.raw())
    NativeBindings.waterui_drop_color(metadata.colorPtr)

    val container = PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)

    container.elevation = metadata.radius.dp(context)

    // The elevation shadow follows the view outline; the default provider is
    // the rectangular bounds, so without this a non-rectangular caster throws
    // a square-cornered shadow. The silhouette arrives as the same (kind,
    // commands) pair a clip shape carries, resolved by the shared builder.
    // `clipToOutline` stays off — the outline shapes the shadow only, children
    // may still draw outside it.
    val density = context.resources.displayMetrics.density
    container.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (view.width == 0 || view.height == 0) return
            val path = buildShapePath(
                metadata.silhouetteKind,
                metadata.silhouetteCommands,
                view.width.toFloat(),
                view.height.toFloat(),
                density
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                outline.setPath(path)
            } else {
                // Outlines are convex-only before API 30; a concave silhouette
                // falls back to its bounding rect there.
                try {
                    @Suppress("DEPRECATION")
                    outline.setConvexPath(path)
                } catch (_: IllegalArgumentException) {
                    outline.setRect(0, 0, view.width, view.height)
                }
            }
        }
    }

    WuiComputed.colorFromComputed(resolvedPtr).also { color ->
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
