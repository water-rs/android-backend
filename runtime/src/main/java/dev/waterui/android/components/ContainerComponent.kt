package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import dev.waterui.android.layout.ChildDescriptor
import dev.waterui.android.layout.RustLayoutViewGroup
import dev.waterui.android.reactive.WatcherGuard
import dev.waterui.android.runtime.HorizontalAlignment
import dev.waterui.android.runtime.NativeAnyViews
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.VerticalAlignment
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.getWuiLayoutPriority
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.disposeAndRemoveView
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.disposeWuiTree
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val layoutContainerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_layout_container_id().toTypeId()
}

private val fixedContainerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_fixed_container_id().toTypeId()
}

private const val LAZY_STACK_VERTICAL = 1
private const val LAZY_STACK_HORIZONTAL = 2
private const val NOT_LAZY_STACK = 0

private data class LazyStackConfig(
    val axis: Int,
    val layoutPtr: Long
)

private data class MeasuredChild(
    val view: View,
    val stretchAxis: StretchAxis,
    val widthPx: Int,
    val heightPx: Int
)

private data class VisibleWindow(
    val start: Int,
    val end: Int,
    val leadingOffsetPx: Int
)

private fun readLazyStackConfig(layoutPtr: Long): LazyStackConfig? {
    return when (val axis = NativeBindings.waterui_layout_lazy_stack_axis(layoutPtr)) {
        LAZY_STACK_VERTICAL, LAZY_STACK_HORIZONTAL -> LazyStackConfig(
            axis = axis,
            layoutPtr = layoutPtr
        )
        NOT_LAZY_STACK -> null
        else -> error("unknown lazy-stack axis descriptor: $axis")
    }
}

@SuppressLint("ViewConstructor")
private class LazyStackLayoutView(
    context: Context,
    private val nativeViews: NativeAnyViews,
    private val env: WuiEnvironment,
    private val registry: RenderRegistry,
    private val config: LazyStackConfig
) : ViewGroup(context) {
    private val density = context.resources.displayMetrics.density
    private val layoutWatcher =
        NativeBindings.waterui_layout_watch_invalidation(config.layoutPtr, this)
    private var spacingPx = 0
    private var horizontalAlignment = HorizontalAlignment.CENTER
    private var verticalAlignment = VerticalAlignment.CENTER

    private val renderedChildren = linkedMapOf<Int, View>()
    private val measuredMainAxisPx = linkedMapOf<Int, Int>()
    private val measuredCrossAxisPx = linkedMapOf<Int, Int>()
    private var itemIds: List<Int> = emptyList()
    private val watcherGuard: WatcherGuard
    private var scrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var lastCrossConstraintPx: Int = -1
    private val activeIds = HashSet<Int>()

    init {
        clipChildren = false
        clipToPadding = false
        watcherGuard = nativeViews.watch { ids ->
            syncIds(ids.toList())
        }
        syncIds(nativeViews.ids().toList())
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (scrollChangedListener != null) {
                    return
                }
                val listener = ViewTreeObserver.OnScrollChangedListener { requestLayout() }
                scrollChangedListener = listener
                viewTreeObserver.addOnScrollChangedListener(listener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                scrollChangedListener?.let { listener ->
                    if (viewTreeObserver.isAlive) {
                        viewTreeObserver.removeOnScrollChangedListener(listener)
                    }
                }
                scrollChangedListener = null
            }
        })
        disposeWith {
            watcherGuard.close()
            nativeViews.close()
            NativeBindings.waterui_layout_watcher_drop(layoutWatcher)
            NativeBindings.waterui_drop_layout(config.layoutPtr)
        }
    }

    private fun syncIds(ids: List<Int>) {
        val seenIds = HashSet<Int>(ids.size)
        ids.forEach { id ->
            check(seenIds.add(id)) { "Duplicate child view id in LazyStackLayoutView: $id" }
        }

        itemIds = ids
        val alive = seenIds
        measuredMainAxisPx.keys.retainAll(alive)
        measuredCrossAxisPx.keys.retainAll(alive)
        renderedChildren.entries.removeIf { (id, view) ->
            if (alive.contains(id)) {
                false
            } else {
                disposeAndRemoveView(view)
                true
            }
        }
        requestLayout()
    }

    private fun crossConstraintPx(widthMeasureSpec: Int, heightMeasureSpec: Int): Int {
        return when (config.axis) {
            LAZY_STACK_VERTICAL -> when (MeasureSpec.getMode(widthMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> measuredWidth
                else -> MeasureSpec.getSize(widthMeasureSpec)
            }
            LAZY_STACK_HORIZONTAL -> when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> measuredHeight
                else -> MeasureSpec.getSize(heightMeasureSpec)
            }
            else -> error("unsupported lazy stack axis: ${config.axis}")
        }
    }

    private fun invalidateMeasurementsIfNeeded(crossConstraintPx: Int) {
        if (crossConstraintPx != lastCrossConstraintPx) {
            lastCrossConstraintPx = crossConstraintPx
            measuredMainAxisPx.clear()
            measuredCrossAxisPx.clear()
            renderedChildren.values.toList().forEach(::disposeAndRemoveView)
            renderedChildren.clear()
        }
    }

    private fun stretchesCrossAxis(stretchAxis: StretchAxis): Boolean {
        return when (config.axis) {
            LAZY_STACK_VERTICAL -> {
                stretchAxis == StretchAxis.HORIZONTAL ||
                    stretchAxis == StretchAxis.BOTH ||
                    stretchAxis == StretchAxis.CROSS_AXIS
            }
            LAZY_STACK_HORIZONTAL -> {
                stretchAxis == StretchAxis.VERTICAL ||
                    stretchAxis == StretchAxis.BOTH ||
                    stretchAxis == StretchAxis.CROSS_AXIS
            }
            else -> error("unsupported lazy stack axis: ${config.axis}")
        }
    }

    private fun requireSupportedStretch(stretchAxis: StretchAxis) {
        when (config.axis) {
            LAZY_STACK_VERTICAL -> check(
                stretchAxis != StretchAxis.VERTICAL &&
                    stretchAxis != StretchAxis.BOTH &&
                    stretchAxis != StretchAxis.MAIN_AXIS
            ) {
                "Lazy vertical stack does not support children stretching on the main axis"
            }
            LAZY_STACK_HORIZONTAL -> check(
                stretchAxis != StretchAxis.HORIZONTAL &&
                    stretchAxis != StretchAxis.BOTH &&
                    stretchAxis != StretchAxis.MAIN_AXIS
            ) {
                "Lazy horizontal stack does not support children stretching on the main axis"
            }
            else -> error("unsupported lazy stack axis: ${config.axis}")
        }
    }

    private fun measureChild(
        index: Int,
        crossConstraintPx: Int,
        existing: View? = null
    ): MeasuredChild {
        val view = existing ?: run {
            val ptr = nativeViews.viewAt(index)
            check(ptr != 0L) { "Lazy stack failed to materialize child at index $index" }
            inflateAnyView(context, ptr, env, registry)
        }
        val stretchAxis = view.getWuiStretchAxis()
        requireSupportedStretch(stretchAxis)

        return when (config.axis) {
            LAZY_STACK_VERTICAL -> {
                val crossSpec = if (crossConstraintPx <= 0) {
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                } else if (stretchesCrossAxis(stretchAxis)) {
                    MeasureSpec.makeMeasureSpec(crossConstraintPx, MeasureSpec.EXACTLY)
                } else {
                    MeasureSpec.makeMeasureSpec(crossConstraintPx, MeasureSpec.AT_MOST)
                }
                view.measure(crossSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val widthPx = if (crossConstraintPx > 0 && stretchesCrossAxis(stretchAxis)) {
                    crossConstraintPx
                } else {
                    if (crossConstraintPx > 0) min(view.measuredWidth, crossConstraintPx) else view.measuredWidth
                }
                val heightPx = view.measuredHeight
                if (view.measuredWidth != widthPx) {
                    view.measure(
                        MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY)
                    )
                }
                MeasuredChild(view, stretchAxis, widthPx, heightPx)
            }
            LAZY_STACK_HORIZONTAL -> {
                val crossSpec = if (crossConstraintPx <= 0) {
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                } else if (stretchesCrossAxis(stretchAxis)) {
                    MeasureSpec.makeMeasureSpec(crossConstraintPx, MeasureSpec.EXACTLY)
                } else {
                    MeasureSpec.makeMeasureSpec(crossConstraintPx, MeasureSpec.AT_MOST)
                }
                view.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), crossSpec)
                val widthPx = view.measuredWidth
                val heightPx = if (crossConstraintPx > 0 && stretchesCrossAxis(stretchAxis)) {
                    crossConstraintPx
                } else {
                    if (crossConstraintPx > 0) min(view.measuredHeight, crossConstraintPx) else view.measuredHeight
                }
                if (view.measuredHeight != heightPx) {
                    view.measure(
                        MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY)
                    )
                }
                MeasuredChild(view, stretchAxis, widthPx, heightPx)
            }
            else -> error("unsupported lazy stack axis: ${config.axis}")
        }
    }

    private fun estimateMainAxisPx(crossConstraintPx: Int): Int {
        if (measuredMainAxisPx.isEmpty() && itemIds.isNotEmpty()) {
            val sample = measureChild(index = 0, crossConstraintPx = crossConstraintPx)
            val sampleId = itemIds[0]
            measuredMainAxisPx[sampleId] =
                if (config.axis == LAZY_STACK_VERTICAL) sample.heightPx else sample.widthPx
            measuredCrossAxisPx[sampleId] =
                if (config.axis == LAZY_STACK_VERTICAL) sample.widthPx else sample.heightPx
            sample.view.disposeWuiTree()
        }
        return measuredMainAxisPx.values.average().takeIf { it.isFinite() }?.roundToInt()
            ?: 0
    }

    private fun contentSizePx(crossConstraintPx: Int): Pair<Int, Int> {
        if (itemIds.isEmpty()) {
            return 0 to 0
        }
        val estimatePx = estimateMainAxisPx(crossConstraintPx)
        val totalMainAxisPx = itemIds.foldIndexed(0) { index, acc, id ->
            acc + (measuredMainAxisPx[id] ?: estimatePx) + if (index + 1 < itemIds.size) spacingPx else 0
        }
        val crossAxisPx = when (config.axis) {
            LAZY_STACK_VERTICAL -> if (crossConstraintPx > 0) {
                crossConstraintPx
            } else {
                measuredCrossAxisPx.values.maxOrNull() ?: 0
            }
            LAZY_STACK_HORIZONTAL -> if (crossConstraintPx > 0) {
                crossConstraintPx
            } else {
                measuredCrossAxisPx.values.maxOrNull() ?: 0
            }
            else -> error("unsupported lazy stack axis: ${config.axis}")
        }
        return when (config.axis) {
            LAZY_STACK_VERTICAL -> crossAxisPx to totalMainAxisPx
            LAZY_STACK_HORIZONTAL -> totalMainAxisPx to crossAxisPx
            else -> error("unsupported lazy stack axis: ${config.axis}")
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        spacingPx = (
            NativeBindings.waterui_layout_lazy_stack_spacing(config.layoutPtr) * density
            ).roundToInt()
        horizontalAlignment = HorizontalAlignment.fromInt(
            NativeBindings.waterui_layout_lazy_stack_horizontal_alignment(config.layoutPtr)
        )
        verticalAlignment = VerticalAlignment.fromInt(
            NativeBindings.waterui_layout_lazy_stack_vertical_alignment(config.layoutPtr)
        )
        val crossConstraintPx = crossConstraintPx(widthMeasureSpec, heightMeasureSpec)
        invalidateMeasurementsIfNeeded(crossConstraintPx)
        val (contentWidthPx, contentHeightPx) = contentSizePx(crossConstraintPx)
        setMeasuredDimension(
            resolveSize(contentWidthPx, widthMeasureSpec),
            resolveSize(contentHeightPx, heightMeasureSpec)
        )
    }

    private fun viewportRangePx(): IntRange {
        return when (config.axis) {
            LAZY_STACK_VERTICAL -> {
                val scroll = findVerticalScrollParent()
                if (scroll == null) {
                    0..height
                } else {
                    scroll.scrollY..(scroll.scrollY + scroll.height)
                }
            }
            LAZY_STACK_HORIZONTAL -> {
                val scroll = findHorizontalScrollParent()
                if (scroll == null) {
                    0..width
                } else {
                    scroll.scrollX..(scroll.scrollX + scroll.width)
                }
            }
            else -> error("unsupported lazy stack axis: ${config.axis}")
        }
    }

    private fun findVerticalScrollParent(): ScrollView? {
        var parentRef = parent
        while (parentRef is View) {
            if (parentRef is ScrollView) {
                return parentRef
            }
            parentRef = parentRef.parent
        }
        return null
    }

    private fun findHorizontalScrollParent(): HorizontalScrollView? {
        var parentRef = parent
        while (parentRef is View) {
            if (parentRef is HorizontalScrollView) {
                return parentRef
            }
            parentRef = parentRef.parent
        }
        return null
    }

    private fun resolveVisibleWindow(viewport: IntRange, crossConstraintPx: Int): VisibleWindow {
        val estimatePx = estimateMainAxisPx(crossConstraintPx)
        val overscanPx = max(estimatePx, 1) * 2
        val targetStart = max(viewport.first - overscanPx, 0)
        val targetEnd = max(viewport.last + overscanPx, targetStart)
        var offsetPx = 0
        var index = 0
        while (index < itemIds.size) {
            val id = itemIds[index]
            val extentPx = measuredMainAxisPx[id] ?: estimatePx
            if (offsetPx + extentPx > targetStart) {
                break
            }
            offsetPx += extentPx + if (index + 1 < itemIds.size) spacingPx else 0
            index += 1
        }
        val start = index
        val leadingOffsetPx = offsetPx
        while (index < itemIds.size && offsetPx < targetEnd) {
            val id = itemIds[index]
            offsetPx += (measuredMainAxisPx[id] ?: estimatePx) + if (index + 1 < itemIds.size) spacingPx else 0
            index += 1
        }
        return VisibleWindow(start = start, end = index, leadingOffsetPx = leadingOffsetPx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (itemIds.isEmpty()) {
            return
        }
        val crossConstraintPx = if (config.axis == LAZY_STACK_VERTICAL) width else height
        val visibleWindow = resolveVisibleWindow(viewportRangePx(), crossConstraintPx)
        activeIds.clear()
        var cursorPx = visibleWindow.leadingOffsetPx
        var requiresRelayout = false

        for (index in visibleWindow.start until visibleWindow.end) {
            val id = itemIds[index]
            val existing = renderedChildren[id]
            val measured = measureChild(index, crossConstraintPx, existing)
            val mainAxisPx = if (config.axis == LAZY_STACK_VERTICAL) measured.heightPx else measured.widthPx
            val crossAxisPx = if (config.axis == LAZY_STACK_VERTICAL) measured.widthPx else measured.heightPx
            if (measuredMainAxisPx[id] != mainAxisPx || measuredCrossAxisPx[id] != crossAxisPx) {
                measuredMainAxisPx[id] = mainAxisPx
                measuredCrossAxisPx[id] = crossAxisPx
                requiresRelayout = true
            }

            if (existing == null) {
                renderedChildren[id] = measured.view
                addView(measured.view)
            }
            activeIds.add(id)

            when (config.axis) {
                LAZY_STACK_VERTICAL -> {
                    val childLeft = when (horizontalAlignment) {
                        HorizontalAlignment.LEADING -> 0
                        HorizontalAlignment.TRAILING -> width - measured.widthPx
                        HorizontalAlignment.CENTER -> (width - measured.widthPx) / 2
                    }
                    measured.view.layout(
                        childLeft,
                        cursorPx,
                        childLeft + measured.widthPx,
                        cursorPx + measured.heightPx
                    )
                    cursorPx += measured.heightPx + if (index + 1 < itemIds.size) spacingPx else 0
                }
                LAZY_STACK_HORIZONTAL -> {
                    val childTop = when (verticalAlignment) {
                        VerticalAlignment.TOP -> 0
                        VerticalAlignment.BOTTOM -> height - measured.heightPx
                        VerticalAlignment.CENTER, VerticalAlignment.FIRST_BASELINE, VerticalAlignment.LAST_BASELINE ->
                            (height - measured.heightPx) / 2
                    }
                    measured.view.layout(
                        cursorPx,
                        childTop,
                        cursorPx + measured.widthPx,
                        childTop + measured.heightPx
                    )
                    cursorPx += measured.widthPx + if (index + 1 < itemIds.size) spacingPx else 0
                }
                else -> error("unsupported lazy stack axis: ${config.axis}")
            }
        }

        renderedChildren.entries.removeIf { (id, view) ->
            if (activeIds.contains(id)) {
                false
            } else {
                disposeAndRemoveView(view)
                true
            }
        }

        if (requiresRelayout) {
            requestLayout()
        }
    }
}

private val layoutContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_layout_container(node.rawPtr)
    val lazyStack = readLazyStackConfig(struct.layoutPtr)
    if (struct.childrenPtr != 0L && lazyStack != null) {
        return@WuiRenderer LazyStackLayoutView(
            context = context,
            nativeViews = NativeAnyViews(struct.childrenPtr),
            env = env,
            registry = registry,
            config = lazyStack
        )
    }

    val group = RustLayoutViewGroup(context, layoutPtr = struct.layoutPtr, descriptors = emptyList())
    val contentsPtr = struct.childrenPtr
    if (contentsPtr != 0L) {
        val nativeViews = NativeAnyViews(contentsPtr)
        // id -> already-inflated child view, so a membership change reuses the
        // native views of unchanged ids (preserving their animations/focus/a11y)
        // and only inflates the ids that actually joined the collection.
        val renderedChildren = linkedMapOf<Int, View>()

        fun syncChildren(ids: IntArray) {
            val seen = HashSet<Int>(ids.size)
            val ordered = ArrayList<View>(ids.size)
            val descriptors = ArrayList<ChildDescriptor>(ids.size)
            ids.forEachIndexed { index, id ->
                check(seen.add(id)) { "Duplicate child view id in container: $id" }
                val view = renderedChildren[id] ?: run {
                    val ptr = nativeViews.viewAt(index)
                    check(ptr != 0L) { "container failed to materialize child at index $index" }
                    inflateAnyView(context, ptr, env, registry)
                }
                renderedChildren[id] = view
                ordered.add(view)
                descriptors.add(
                    ChildDescriptor(
                        stretchAxis = view.getWuiStretchAxis(),
                        priority = view.getWuiLayoutPriority()
                    )
                )
            }
            renderedChildren.keys.retainAll(seen)
            group.reconcileChildren(ordered, descriptors)
        }

        val watcherGuard = nativeViews.watch(::syncChildren)
        syncChildren(nativeViews.ids())

        group.disposeWith {
            watcherGuard.close()
            nativeViews.close()
        }
    }

    group
}

private val fixedContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_fixed_container(node.rawPtr)
    val childPointers = struct.childPointers.toList()
    val inflatedChildren = childPointers.map { childPtr ->
        inflateAnyView(context, childPtr, env, registry)
    }
    val descriptors = inflatedChildren.map { child ->
        ChildDescriptor(
            stretchAxis = child.getWuiStretchAxis(),
            priority = child.getWuiLayoutPriority()
        )
    }
    val group = RustLayoutViewGroup(context, layoutPtr = struct.layoutPtr, descriptors = descriptors)
    inflatedChildren.forEach { child ->
        group.addView(child)
    }
    group
}

internal fun RegistryBuilder.registerWuiContainers() {
    register({ layoutContainerTypeId }, layoutContainerRenderer)
    register({ fixedContainerTypeId }, fixedContainerRenderer)
}
