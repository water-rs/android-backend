package dev.waterui.android.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import dev.waterui.android.layout.GroupAlphaDraw
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.layout.RustLayoutViewGroup
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView

private val metadataOpacityTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_opacity_id().toTypeId()
}

/**
 * Fallback host for `.opacity` when the content is a ViewGroup outside the
 * WaterUI container family (a scroll surface, a platform widget). Those
 * cannot carry the shared [GroupAlphaDraw] mechanism themselves, so they keep
 * this single-child wrapper which composes the group like SwiftUI's opacity:
 * the subtree is composited and alpha applied to the result, without
 * clipping content drawn outside the layout frame.
 */
internal class OpacityLayout(context: Context) : PassThroughFrameLayout(context)

/**
 * `.opacity` is a paint-only modifier: it folds into the child view's alpha.
 * WaterUI's own containers (PassThroughFrameLayout, RustLayoutViewGroup)
 * composite the alpha over their subtree's complete draw; a leaf view's
 * alpha is exact on its own draws. Foreign ViewGroups keep a wrapper so the
 * group composite still holds.
 *
 * A view's alpha is one channel, so a modifier may fold only onto a child
 * whose alpha is unowned: a child already claimed by an inner `.opacity`
 * ([R.id.wui_opacity_host]) or by an elevation shadow
 * ([R.id.wui_shadow_host], which the parent render node projects outside
 * the subtree's compositing) is wrapped instead — the new modifier owns the
 * wrapper's alpha and the effects compose through the hierarchy
 * (`0.5 * 0.5 = 0.25`), static and animated alike. A foreground drawable or
 * a background is no ownership conflict: [GroupAlphaDraw] wraps the host's
 * complete draw, so everything the view paints fades with the group.
 */
internal fun opacityHostFor(context: Context, child: View): View {
    val owned = child.getTag(R.id.wui_opacity_host) != null ||
        child.getTag(R.id.wui_shadow_host) != null
    val host = when {
        owned -> OpacityLayout(context).apply { addView(child) }
        child is PassThroughFrameLayout -> child
        child is RustLayoutViewGroup -> child
        child !is ViewGroup -> child
        else -> OpacityLayout(context).apply { addView(child) }
    }
    host.setTag(R.id.wui_opacity_host, true)
    return host
}

private val metadataOpacityRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_opacity(node.rawPtr)
    val child = inflateAnyView(context, metadata.contentPtr, env, registry)
    val host = opacityHostFor(context, child)

    val opacityComputed = WuiComputed.float(metadata.valuePtr, env)
    opacityComputed.observe { alpha ->
        host.alpha = alpha
    }

    host.disposeWith(opacityComputed)

    host
}

internal fun RegistryBuilder.registerWuiOpacity() {
    registerMetadata({ metadataOpacityTypeId }, metadataOpacityRenderer)
}
