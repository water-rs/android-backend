package dev.waterui.android.components

import android.view.View
import android.widget.LinearLayout
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import dev.waterui.android.layout.AxisExpandingLinearLayout
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

import kotlin.math.roundToInt

private val progressTypeId: WuiTypeId by lazy { NativeBindings.waterui_progress_id().toTypeId() }
private const val PROGRESS_STYLE_LINEAR = 0
private const val PROGRESS_STYLE_CIRCULAR = 1
private const val PROGRESS_STYLE_LOADING = 2

private val progressRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_progress(node.rawPtr)
    val computed = WuiComputed.double(struct.valuePtr)

    val isLinear = when (struct.style) {
        PROGRESS_STYLE_LINEAR -> true
        PROGRESS_STYLE_CIRCULAR, PROGRESS_STYLE_LOADING -> false
        else -> error("unknown progress style: ${struct.style}")
    }

    val container = if (isLinear) {
        AxisExpandingLinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    } else {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
    }

    val label = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(label)

    // The loading indicator is Material's own view, not a progress indicator:
    // it is always indeterminate, so it reports no value and has no track.
    val indicator: View = when (struct.style) {
        PROGRESS_STYLE_LINEAR -> LinearProgressIndicator(context).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        PROGRESS_STYLE_CIRCULAR -> CircularProgressIndicator(context).apply {
            max = 1000
        }
        PROGRESS_STYLE_LOADING -> LoadingIndicator(context)
        else -> error("unknown progress style: ${struct.style}")
    }
    val progressBar = indicator as? BaseProgressIndicator<*>
    container.addView(indicator)

    val valueLabel = inflateAnyView(context, struct.valueLabelPtr, env, registry).also {
        it.visibility = View.GONE
        container.addView(it)
    }

    computed.observe { value ->
        if (progressBar == null) {
            // A loading indicator never reports a reading, so it never shows a
            // value label — but the value still has to be well formed.
            require(value.isFinite() && value in 0.0..1.0 || value == Double.POSITIVE_INFINITY) {
                "progress value must be finite within 0...1 or infinite"
            }
            valueLabel.visibility = View.GONE
            return@observe
        }
        progressBar.isIndeterminate = value == Double.POSITIVE_INFINITY
        if (value == Double.POSITIVE_INFINITY) {
            valueLabel.visibility = View.GONE
        } else {
            require(value.isFinite() && value in 0.0..1.0) {
                "progress value must be finite within 0...1 or infinite"
            }
            progressBar.setProgressCompat((value * progressBar.max).roundToInt(), true)
            valueLabel.visibility = View.VISIBLE
        }
    }

    container.disposeWith(computed)
    val indicatorSignals = if (struct.fourColor) {
        listOf(
            ThemeBridge.accent(env),
            ThemeBridge.accentContainer(env),
            ThemeBridge.tertiary(env),
            ThemeBridge.tertiaryContainer(env)
        )
    } else {
        listOf(ThemeBridge.accent(env))
    }
    val indicatorColors = IntArray(indicatorSignals.size)
    val resolvedIndicatorColors = BooleanArray(indicatorSignals.size)
    indicatorSignals.forEachIndexed { index, signal ->
        signal.observe { color ->
            indicatorColors[index] = color.toColorInt()
            resolvedIndicatorColors[index] = true
            if (resolvedIndicatorColors.all { it }) {
                when (indicator) {
                    is BaseProgressIndicator<*> ->
                        indicator.setResolvedIndicatorColors(indicatorColors)
                    is LoadingIndicator -> indicator.setIndicatorColor(*indicatorColors)
                    else -> error("unknown indicator view: $indicator")
                }
            }
        }
        signal.attachTo(indicator)
    }
    // The track keeps its Widget.Material3 indicator default
    // (secondaryContainer-family); colorOutline is a stroke color and made
    // the track darker and more saturated than Compose's progress track.
    container
}

private fun BaseProgressIndicator<*>.setResolvedIndicatorColors(colors: IntArray) {
    when (colors.size) {
        1 -> setIndicatorColor(colors[0])
        4 -> setIndicatorColor(colors[0], colors[1], colors[2], colors[3])
        else -> error("progress indicator palette must contain one or four colors")
    }
}

internal fun RegistryBuilder.registerWuiProgress() {
    register({ progressTypeId }, progressRenderer)
}
