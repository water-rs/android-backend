package dev.waterui.android.components

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

private val colorPickerTypeId: WuiTypeId by lazy { NativeBindings.waterui_color_picker_id().toTypeId() }

private data class EditableColor(
    val redSrgb: Float,
    val greenSrgb: Float,
    val blueSrgb: Float,
    val alpha: Float,
    val headroom: Float,
)

private val colorPickerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_color_picker(node.rawPtr)
    val binding = WuiBinding.color(struct.valuePtr)

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    val button = MaterialButton(context)

    container.addView(
        labelView,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    container.addView(button)
    installSemanticAccessibilityLabel(
        target = button,
        content = labelView,
        labelPtr = struct.accessibilityLabelPtr,
        env = env
    )

    var resolvedColor: WuiComputed<ResolvedColorStruct>? = null
    var latestColor: EditableColor? = null

    binding.observe { colorPtr ->
        resolvedColor?.close()
        resolvedColor = WuiComputed.resolvedColor(colorPtr, env).also { computed ->
            computed.observe { resolved ->
                latestColor = resolved.toEditableColor().also { editable ->
                    applyButtonPreview(button, editable)
                }
            }
        }
    }

    button.setOnClickListener {
        val initial = requireNotNull(latestColor) { "color picker value did not resolve" }
        showColorDialog(
            context = context,
            initial = initial,
            supportAlpha = struct.supportAlpha,
            supportHdr = struct.supportHdr,
        ) { edited ->
            val ptr = NativeBindings.waterui_color_from_linear_rgba_headroom(
                srgbToLinear(edited.redSrgb),
                srgbToLinear(edited.greenSrgb),
                srgbToLinear(edited.blueSrgb),
                edited.alpha,
                edited.headroom,
            )
            binding.set(ptr)
        }
    }

    container.disposeWith {
        resolvedColor?.close()
        binding.close()
    }
    container
}

private fun showColorDialog(
    context: android.content.Context,
    initial: EditableColor,
    supportAlpha: Boolean,
    supportHdr: Boolean,
    onPicked: (EditableColor) -> Unit
) {
    var current = initial
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = 24f.dp(context).toInt()
        setPadding(inset, inset, inset, 0)
    }
    val preview = HdrColorPreviewView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            48f.dp(context).roundToInt(),
        )
    }
    root.addView(preview)

    val red = colorSlider(context, R.string.wui_color_red, current.redSrgb, 0f, 1f) {
        current = current.copy(redSrgb = it)
        updatePreview(preview, current)
    }
    val green = colorSlider(context, R.string.wui_color_green, current.greenSrgb, 0f, 1f) {
        current = current.copy(greenSrgb = it)
        updatePreview(preview, current)
    }
    val blue = colorSlider(context, R.string.wui_color_blue, current.blueSrgb, 0f, 1f) {
        current = current.copy(blueSrgb = it)
        updatePreview(preview, current)
    }
    root.addView(red)
    root.addView(green)
    root.addView(blue)

    if (supportAlpha) {
        root.addView(colorSlider(context, R.string.wui_color_alpha, current.alpha, 0f, 1f) {
            current = current.copy(alpha = it)
            updatePreview(preview, current)
        })
    }

    if (supportHdr) {
        root.addView(colorSlider(context, R.string.wui_color_hdr, current.headroom.coerceAtLeast(0f), 0f, 4f) {
            current = current.copy(headroom = it)
            updatePreview(preview, current)
        })
    }

    updatePreview(preview, current)

    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.wui_select_color)
        .setView(root)
        .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(current) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun colorSlider(
    context: android.content.Context,
    label: Int,
    initial: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
): LinearLayout {
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    val title = TextView(context).apply {
        text = context.getString(label)
        // The dialog helper has no theme-bridge env; the M3 body-medium
        // appearance keeps the label on Material typography (and sp).
        val attributes = context.obtainStyledAttributes(
            intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)
        )
        val appearance = attributes.getResourceId(0, 0)
        attributes.recycle()
        check(appearance != 0) { "WaterUI Material theme is missing textAppearanceBodyMedium" }
        setTextAppearance(appearance)
    }
    val slider = Slider(context).apply {
        valueFrom = min
        valueTo = max
        stepSize = 0.01f
        value = initial.coerceIn(min, max)
        addOnChangeListener { _, value, _ -> onChange(value) }
    }
    layout.addView(title)
    layout.addView(slider)
    return layout
}

private fun applyButtonPreview(button: MaterialButton, color: EditableColor) {
    button.backgroundTintList = ColorStateList.valueOf(color.toColorInt())
    button.text = color.toHexLabel()
}

private fun updatePreview(view: HdrColorPreviewView, color: EditableColor) {
    view.setColor(color.toColorLong())
}

private class HdrColorPreviewView(context: android.content.Context) : View(context) {
    private var color: Long = Color.pack(Color.TRANSPARENT)

    fun setColor(color: Long) {
        this.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(color)
    }
}

private fun EditableColor.toColorInt(): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    val r = (redSrgb.coerceIn(0f, 1f) * 255f).roundToInt()
    val g = (greenSrgb.coerceIn(0f, 1f) * 255f).roundToInt()
    val b = (blueSrgb.coerceIn(0f, 1f) * 255f).roundToInt()
    return Color.argb(a, r, g, b)
}

private fun EditableColor.toColorLong(): Long {
    val scale = if (headroom.isFinite() && headroom > 0f) 1f + headroom else 1f
    return Color.pack(
        srgbToLinear(redSrgb) * scale,
        srgbToLinear(greenSrgb) * scale,
        srgbToLinear(blueSrgb) * scale,
        alpha.coerceIn(0f, 1f),
        ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB),
    )
}

private fun EditableColor.toHexLabel(): String {
    return if (alpha < 1f) {
        String.format(
            Locale.ROOT,
            "#%02X%02X%02X%02X",
            (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
            (redSrgb.coerceIn(0f, 1f) * 255f).roundToInt(),
            (greenSrgb.coerceIn(0f, 1f) * 255f).roundToInt(),
            (blueSrgb.coerceIn(0f, 1f) * 255f).roundToInt(),
        )
    } else {
        String.format(
            Locale.ROOT,
            "#%02X%02X%02X",
            (redSrgb.coerceIn(0f, 1f) * 255f).roundToInt(),
            (greenSrgb.coerceIn(0f, 1f) * 255f).roundToInt(),
            (blueSrgb.coerceIn(0f, 1f) * 255f).roundToInt(),
        )
    }
}

private fun ResolvedColorStruct.toEditableColor(): EditableColor {
    return EditableColor(
        redSrgb = linearToSrgb(red).coerceIn(0f, 1f),
        greenSrgb = linearToSrgb(green).coerceIn(0f, 1f),
        blueSrgb = linearToSrgb(blue).coerceIn(0f, 1f),
        alpha = opacity.coerceIn(0f, 1f),
        headroom = headroom.coerceAtLeast(0f),
    )
}

private fun linearToSrgb(value: Float): Float {
    return if (value <= 0.003_130_8f) {
        value * 12.92f
    } else {
        1.055f * value.pow(1f / 2.4f) - 0.055f
    }
}

private fun srgbToLinear(value: Float): Float {
    return if (value <= 0.04045f) {
        value / 12.92f
    } else {
        ((value + 0.055f) / 1.055f).pow(2.4f)
    }
}

internal fun RegistryBuilder.registerWuiColorPicker() {
    register({ colorPickerTypeId }, colorPickerRenderer)
}
