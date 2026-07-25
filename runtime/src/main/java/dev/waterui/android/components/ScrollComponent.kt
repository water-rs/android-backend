package dev.waterui.android.components

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView


private val scrollTypeId: WuiTypeId by lazy { NativeBindings.waterui_scroll_view_id().toTypeId() }

private const val AXIS_HORIZONTAL = 0
private const val AXIS_VERTICAL = 1
private const val AXIS_ALL = 2

private val scrollRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_scroll(node.rawPtr)
    val content = inflateAnyView(context, struct.contentPtr, env, registry)

    // Layout decisions (including centering) are made by Rust layout engine.
    // Android only measures and places children.
    var verticalHost: ScrollView? = null
    var horizontalHost: HorizontalScrollView? = null
    val root: View = when (struct.axis) {
        AXIS_HORIZONTAL -> HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(content)
            horizontalHost = this
        }
        AXIS_VERTICAL -> ScrollView(context).apply {
            addView(content)
            verticalHost = this
        }
        AXIS_ALL -> ScrollView(context).apply {
            val horizontal = HorizontalScrollView(context)
            horizontal.addView(content)
            addView(horizontal)
            verticalHost = this
            horizontalHost = horizontal
        }
        else -> error("unknown scroll axis: ${struct.axis}")
    }

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
