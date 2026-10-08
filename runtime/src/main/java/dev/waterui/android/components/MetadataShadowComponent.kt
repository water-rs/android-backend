package dev.waterui.android.components

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
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

    val density = context.resources.displayMetrics.density

    // The elevation shadow follows the view outline; the default provider is
    // the rectangular bounds, so without this a non-rectangular caster throws
    // a square-cornered shadow. The silhouette arrives as the same (kind,
    // commands) pair a clip shape carries, resolved by the shared builder.
    // `clipToOutline` stays off — the outline shapes the shadow only, children
    // may still draw outside it.
    //
    // The shadow is pure view state — elevation, an outline provider, the
    // shadow colors — so `.shadow` folds onto the content view and claims no
    // wrapper. Those three slots are single-owner: a second `.shadow` on the
    // same view would overwrite the first instead of doubling it, so a child
    // already claimed by an inner `.shadow` ([R.id.wui_shadow_host]) is
    // wrapped and the outer shadow claims the wrapper's slots.
    val child = inflateAnyView(context, metadata.contentPtr, env, registry)
    val host = shadowHostFor(context, child)
    host.elevation = metadata.radius.dp(context)
    host.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (view.width == 0 || view.height == 0) return
            outline.setPath(
                buildShapePath(
                    metadata.silhouetteKind,
                    metadata.silhouetteCommands,
                    view.width.toFloat(),
                    view.height.toFloat(),
                    density
                )
            )
        }
    }
    WuiComputed.colorFromComputed(resolvedPtr, env).also { color ->
        color.observe { resolvedColor ->
            val shadowColor = resolvedColor.toColorInt()
            host.outlineAmbientShadowColor = shadowColor
            host.outlineSpotShadowColor = shadowColor
        }
        color.attachTo(host)
    }
    host
}

/**
 * The view whose elevation/outline/shadow-color slots this shadow owns.
 *
 * A `.shadow` is the *outer* effect: it evaluates the silhouette in the
 * frame the modifier occupies, so folding it onto the child is only valid
 * while the child carries no inner effect that would transform or fade that
 * shadow — no folded or native transform (`wui_folded_transform`, a set
 * rotation/scale/translation), no owned alpha (`wui_opacity_host`, `alpha`),
 * and no shadow state of its own (a prior `.shadow`'s tag, its own
 * elevation/Z, or a custom outline provider the assignment would
 * overwrite). Any of those earns a [PassThroughFrameLayout] wrapper whose
 * slots the outer shadow claims instead.
 */
internal fun shadowHostFor(context: Context, child: View): View {
    val owned = child.getTag(R.id.wui_shadow_host) != null ||
        child.getTag(R.id.wui_opacity_host) != null ||
        child.getTag(R.id.wui_folded_transform) != null ||
        child.alpha != 1f ||
        child.elevation != 0f ||
        child.z != 0f ||
        child.translationZ != 0f ||
        child.rotation != 0f ||
        child.rotationX != 0f ||
        child.rotationY != 0f ||
        child.scaleX != 1f ||
        child.scaleY != 1f ||
        child.translationX != 0f ||
        child.translationY != 0f ||
        (
            child.outlineProvider != null &&
                child.outlineProvider != ViewOutlineProvider.BACKGROUND &&
                child.outlineProvider != ViewOutlineProvider.BOUNDS
            )
    val host = if (owned) {
        PassThroughFrameLayout(context).apply { addView(child) }
    } else {
        child
    }
    host.setTag(R.id.wui_shadow_host, true)
    return host
}

internal fun RegistryBuilder.registerWuiShadow() {
    registerMetadata({ metadataShadowTypeId }, metadataShadowRenderer)
}
