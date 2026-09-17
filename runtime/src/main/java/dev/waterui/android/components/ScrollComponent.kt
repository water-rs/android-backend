package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.doOnLayout
import dev.waterui.android.layout.ViewportClipLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.ProbeMemos
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.SizeStruct
import dev.waterui.android.runtime.ViewDimensionsStruct
import dev.waterui.android.runtime.WuiMeasurableLayout
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiSafeAreaManaging
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView


private val scrollTypeId: WuiTypeId by lazy { NativeBindings.waterui_scroll_view_id().toTypeId() }

/**
 * A scroll viewport that owns the window edges it is handed.
 *
 * The surface reaches under the system bars while its content keeps clearing
 * them at rest — iOS spells this `contentInset` on `UIScrollView`, Android
 * spells it padding with `clipToPadding` off: the padding is the resting
 * clearance, and unclipped padding is what lets rows draw into the bar area
 * once they scroll. Which axis host receives which edges follows the scroll
 * direction: a single-axis host takes all four insets, while a bidirectional
 * scroll splits them so neither axis double-applies.
 */
@SuppressLint("ViewConstructor")
internal class SafeAreaScrollViewport(
    context: Context,
    content: View,
    private val verticalHost: ScrollView?,
    private val horizontalHost: HorizontalScrollView?
) : ViewportClipLayout(context, content), WuiSafeAreaManaging, WuiMeasurableLayout {
    private val density: Float = context.resources.displayMetrics.density
    override fun applySafeArea(insets: Insets) {
        verticalHost?.apply {
            clipToPadding = false
            setPadding(
                if (horizontalHost == null) insets.left else 0,
                insets.top,
                if (horizontalHost == null) insets.right else 0,
                insets.bottom
            )
        }
        horizontalHost?.apply {
            clipToPadding = false
            setPadding(
                insets.left,
                if (verticalHost == null) insets.top else 0,
                insets.right,
                if (verticalHost == null) insets.bottom else 0
            )
        }
    }

    /**
     * Answers a Rust layout probe the way the Apple bridge's `scrollMinSize`
     * does: a scroll claims the whole offer — every axis that names a bound is
     * the viewport's extent — and only a min-size (zero) query looks at the
     * content, reporting its intrinsic extent on the cross axis and zero on the
     * scroll axis. Routing the probe through `MeasureSpec` cannot express that
     * claim: under AT_MOST a `MATCH_PARENT` child is itself offered AT_MOST and
     * answers with its natural size, so a parent that allocates
     * `min(measured, bounds)` — an overlay's base, a centred stack cell —
     * shrinks the viewport to the content instead of letting the content
     * centre inside it.
     */
    override fun measureForLayout(proposal: ProposalStruct, memos: ProbeMemos): ViewDimensionsStruct {
        val minWidthQuery = proposal.width == 0f
        val minHeightQuery = proposal.height == 0f
        val offeredWidth = if (proposal.width.isNaN()) 0f else proposal.width
        val offeredHeight = if (proposal.height.isNaN()) 0f else proposal.height
        if (!minWidthQuery && !minHeightQuery) {
            return ViewDimensionsStruct(
                size = SizeStruct(offeredWidth, offeredHeight),
                horizontalGuides = emptyArray(),
                verticalGuides = emptyArray()
            )
        }
        val host = checkNotNull(verticalHost ?: horizontalHost)
        host.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val intrinsicWidth = host.measuredWidth / density
        val intrinsicHeight = host.measuredHeight / density
        val size = when {
            verticalHost != null && horizontalHost != null -> SizeStruct(
                if (minWidthQuery) 0f else offeredWidth,
                if (minHeightQuery) 0f else offeredHeight
            )
            verticalHost != null -> SizeStruct(
                if (minWidthQuery) intrinsicWidth else offeredWidth,
                if (minHeightQuery) 0f else offeredHeight
            )
            else -> SizeStruct(
                if (minWidthQuery) 0f else offeredWidth,
                if (minHeightQuery) intrinsicHeight else offeredHeight
            )
        }
        return ViewDimensionsStruct(
            size = size,
            horizontalGuides = emptyArray(),
            verticalGuides = emptyArray()
        )
    }
}

private const val AXIS_HORIZONTAL = 0
private const val AXIS_VERTICAL = 1
private const val AXIS_ALL = 2

private val scrollRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_scroll(node.rawPtr)
    val content = inflateAnyView(context, struct.contentPtr, env, registry)

    // Layout decisions (including centering) are made by Rust layout engine.
    // Android only measures and places children.
    //
    // The scroll axis stays content-sized while the cross axis takes the
    // viewport's full extent, matching the Apple bridge: a vertical scroll
    // proposes the viewport width to its content and a horizontal scroll the
    // viewport height, so a centred column spans the window instead of
    // wrapping to its widest row. A bidirectional scroll offers neither —
    // content is measured on both axes.
    var verticalHost: ScrollView? = null
    var horizontalHost: HorizontalScrollView? = null
    val viewport: View = when (struct.axis) {
        AXIS_HORIZONTAL -> HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            horizontalHost = this
        }
        AXIS_VERTICAL -> ScrollView(context).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            verticalHost = this
        }
        AXIS_ALL -> ScrollView(context).apply {
            val horizontal = HorizontalScrollView(context)
            horizontal.addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                horizontal,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            verticalHost = this
            horizontalHost = horizontal
        }
        else -> error("unknown scroll axis: ${struct.axis}")
    }

    // The surrounding WaterUI containers let their children draw outside their
    // own bounds, which a viewport cannot afford; it brings its own clip.
    val root: View = SafeAreaScrollViewport(context, viewport, verticalHost, horizontalHost)

    val controlled = struct.scrollGenerationPtr != 0L
    check(
        (struct.targetXPtr != 0L) == controlled &&
            (struct.targetYPtr != 0L) == controlled
    ) {
        "WaterUI ScrollView controller pointers must be either all null or all non-null"
    }
    if (controlled) {
        val targetX = WuiComputed.float(struct.targetXPtr)
        val targetY = WuiComputed.float(struct.targetYPtr)
        val generation = WuiComputed.int(struct.scrollGenerationPtr)
        var x = 0f
        var y = 0f
        targetX.observe { x = it }
        targetY.observe { y = it }
        generation.observe { request ->
            if (request == 0) return@observe
            check(x.isFinite() && y.isFinite()) {
                "WaterUI ScrollView target must contain finite coordinates"
            }
            root.doOnLayout {
                horizontalHost?.scrollTo(x.toInt(), 0)
                verticalHost?.scrollTo(0, y.toInt())
            }
        }
        root.disposeWith(targetX)
        root.disposeWith(targetY)
        root.disposeWith(generation)
    }
    root
}

internal fun RegistryBuilder.registerWuiScroll() {
    register({ scrollTypeId }, scrollRenderer)
}
