package dev.waterui.android.runtime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isEmpty
import dev.waterui.android.components.WuiEmptyView
import dev.waterui.android.layout.RustLayoutViewGroup

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
 * The dynamic range an enclosing scope asks this view's surface to present in.
 *
 * A GPU surface's format is chosen from it, so it is read by walking up to the
 * nearest ancestor carrying a dynamic-range scope; `null` means no ancestor
 * declares one and the surface presents in standard range.
 */
fun View.inheritedSurfaceDynamicRange(): SurfaceDynamicRange? {
    var view: View? = this
    while (view != null) {
        val range = view.getTag(TAG_DYNAMIC_RANGE) as? SurfaceDynamicRange
        if (range != null) {
            return range
        }
        view = view.parent as? View
    }
    return null
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
 * The stretch axis this view reports to a WaterUI parent, answered live.
 *
 * A view carrying [WuiLiveSlotTraits] — a transparent wrapper forwarding to
 * its content, or a `RustLayoutViewGroup` recomputing over its current
 * children — is asked every time, so the answer can never go stale the way a
 * tag copied at attach time does. Anything else answers with the tag inflation
 * stamped on it.
 */
fun View.getWuiStretchAxis(): StretchAxis {
    return (this as? WuiLiveSlotTraits)?.resolveWuiStretchAxis()
        ?: getTag(TAG_STRETCH_AXIS) as? StretchAxis
        ?: error("WaterUI view ${javaClass.name} is missing a valid stretch-axis tag")
}

/**
 * The layout priority this view reports to a WaterUI parent.
 *
 * An explicit tag — stamped by `layoutPriority` metadata or a view with an
 * intrinsic priority such as `Spacer` — always wins; only without one does
 * the live answer matter, so an explicit override can never be shadowed by a
 * stale copy or by the content behind a wrapper.
 */
fun View.getWuiLayoutPriority(): Int {
    return getTag(TAG_LAYOUT_PRIORITY) as? Int
        ?: (this as? WuiLiveSlotTraits)?.resolveWuiLayoutPriority()
        ?: 0
}

/**
 * Whether this view carries a WaterUI slot identity — a live slot-traits
 * implementation, a Rust layout it can measure, or the stretch tag inflation
 * stamps.
 *
 * This is what a transparent wrapper looks for when it picks the child whose
 * slot it stands in: exactly the children that participate in the WaterUI
 * layout contract. Auxiliary views a host adds for itself — media or capture
 * surfaces — carry no identity and are never the content.
 */
internal fun View.hasWuiSlotIdentity(): Boolean {
    return this is WuiLiveSlotTraits || this is WuiMeasurableLayout ||
        getTag(TAG_STRETCH_AXIS) != null
}

/**
 * Whether this view is WaterUI's empty view `()`, possibly under
 * layout-transparent wrappers or hosted by a `Dynamic`.
 *
 * This is a semantic answer, not a measured size: a `Color` or `Spacer`
 * squeezed to zero still renders and still answers false, and so does a
 * `RustLayoutViewGroup` (a frame or nested stack explicitly claims its
 * slot — e.g. `().size(w, h)`). Transparent hosts forward the child's
 * answer. A stack treats a view answering true as a non-member (§4.4: no
 * slot, no spacing), and the same answer drives the navigation bar's
 * "no subtitle" check.
 */
internal fun View.rendersNothing(): Boolean {
    if (this is WuiEmptyView) {
        return true
    }
    if (this is RustLayoutViewGroup) {
        return false
    }
    val group = this as? ViewGroup ?: return false
    if (group.isEmpty()) {
        return false
    }
    for (index in 0 until group.childCount) {
        if (!group.getChildAt(index).rendersNothing()) {
            return false
        }
    }
    return true
}
