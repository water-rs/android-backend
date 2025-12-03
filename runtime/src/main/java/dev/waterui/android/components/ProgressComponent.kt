package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.toTypeId
import kotlin.math.roundToInt

private val progressTypeId: WuiTypeId by lazy { NativeBindings.waterui_progress_id().toTypeId() }
private const val PROGRESS_STYLE_LINEAR = 0
private const val PROGRESS_STYLE_CIRCULAR = 1

private val progressRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_progress(node.rawPtr)
    val computed = struct.valuePtr.takeIf { it != 0L }?.let { WuiComputed.double(it, env) }

    val isLinear = struct.style != PROGRESS_STYLE_CIRCULAR
    
    // Linear progress is axis-expanding: expands width to fill available space
    // Circular progress is fixed-size: uses intrinsic size
    val container = if (isLinear) {
        object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Expand to fill available width (axis-expanding behavior)
                val widthMode = MeasureSpec.getMode(widthMeasureSpec)
                val widthSize = MeasureSpec.getSize(widthMeasureSpec)
                val expandedWidthSpec = if (widthMode == MeasureSpec.AT_MOST || widthMode == MeasureSpec.EXACTLY) {
                    MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY)
                } else {
                    widthMeasureSpec
                }
                super.onMeasure(expandedWidthSpec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
        }
    } else {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
    }

    if (struct.labelPtr != 0L) {
        val label = inflateAnyView(context, struct.labelPtr, env, registry)
        container.addView(label)
    }

    val progressBar = when (struct.style) {
        PROGRESS_STYLE_CIRCULAR -> ProgressBar(context).apply {
            // Circular: fixed size, doesn't expand
            max = 1000
        }
        else -> ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            // Linear: expands width to fill container
            max = 1000
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }
    container.addView(progressBar)

    val valueLabel = if (struct.valueLabelPtr != 0L) {
        inflateAnyView(context, struct.valueLabelPtr, env, registry).also {
            it.visibility = View.GONE
            container.addView(it)
        }
    } else {
        null
    }

    computed?.observe { value ->
        val isFinite = value.isFinite()
        progressBar.isIndeterminate = !isFinite
        if (isFinite) {
            val scaled = (value.coerceIn(0.0, 1.0) * 1000).roundToInt()
            if (progressBar.max != 0) {
                progressBar.progress = scaled
            }
            valueLabel?.visibility = View.VISIBLE
        } else {
            valueLabel?.visibility = View.GONE
        }
    }

    computed?.let { container.disposeWith(it) }
    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        val tint = ColorStateList.valueOf(color.toColorInt())
        progressBar.progressTintList = tint
        progressBar.indeterminateTintList = tint
    }
    accent.attachTo(progressBar)
    val border = ThemeBridge.border(env)
    border.observe { color ->
        progressBar.secondaryProgressTintList = ColorStateList.valueOf(color.toColorInt())
    }
    border.attachTo(progressBar)
    container
}

internal fun RegistryBuilder.registerWuiProgress() {
    register({ progressTypeId }, progressRenderer)
}
