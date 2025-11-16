package dev.waterui.android.components

import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId
import kotlin.math.roundToInt

private val progressTypeId: WuiTypeId by lazy { NativeBindings.waterui_progress_id().toTypeId() }
private const val PROGRESS_STYLE_LINEAR = 0
private const val PROGRESS_STYLE_CIRCULAR = 1

private val progressRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_progress(node.rawPtr)
    val computed = struct.valuePtr.takeIf { it != 0L }?.let { WuiComputed.double(it, env) }

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    if (struct.labelPtr != 0L) {
        val label = inflateAnyView(context, struct.labelPtr, env, registry)
        container.addView(label)
    }

    val progressBar = when (struct.style) {
        PROGRESS_STYLE_CIRCULAR -> ProgressBar(context)
        else -> ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    }.apply { max = 1000 }
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
    container
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiProgress() {
    register({ progressTypeId }, progressRenderer)
}
