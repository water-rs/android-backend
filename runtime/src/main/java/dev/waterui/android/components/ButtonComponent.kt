package dev.waterui.android.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.ColorSlot
import dev.waterui.android.runtime.InteractionBridge
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

/**
 * Button style enum values matching WuiButtonStyle in FFI.
 */
private object ButtonStyle {
    const val AUTOMATIC = 0
    const val PLAIN = 1
    const val LINK = 2
    const val BORDERLESS = 3
    const val BORDERED = 4
    const val BORDERED_PROMINENT = 5
}

/**
 * Material 3 button chrome for one WaterUI button style.
 *
 * A WaterUI button label is an arbitrary view, so `MaterialButton` (a
 * `TextView`) cannot host it; the container stays a `FrameLayout` and this
 * chrome reproduces the Compose M3 button spec on it: stadium shape, 40dp
 * min height, `ButtonDefaults.ContentPadding` (24/8; text buttons 12/8),
 * outlined stroke in `colorOutline`, and the M3 disabled tokens
 * (`onSurface` at 12% container / 38% content) instead of whole-view alpha.
 */
private data class ButtonChrome(
    /** Slot filling the container, or null for text-style buttons. */
    val fillSlot: ColorSlot?,
    /** Slot for a 1dp outline stroke, or null. */
    val strokeSlot: ColorSlot?,
    /** Slot tinting the ripple state layer. */
    val rippleSlot: ColorSlot,
    val horizontalPaddingDp: Float,
    /** Contained styles reserve the M3 40dp minimum button height. */
    val hasMinHeight: Boolean,
    val underlineLabel: Boolean = false
) {
    val verticalPaddingDp: Float get() = 8f
}

/**
 * Compose M3 mapping: the default `Button` is filled, so AUTOMATIC and
 * BORDERED_PROMINENT project to the filled button; BORDERED projects to
 * `OutlinedButton` (hairline `colorOutline` stroke, accent label);
 * PLAIN/LINK/BORDERLESS project to `TextButton` chrome.
 */
private fun buttonChrome(style: Int): ButtonChrome = when (style) {
    ButtonStyle.AUTOMATIC,
    ButtonStyle.BORDERED_PROMINENT -> ButtonChrome(
        fillSlot = ColorSlot.Accent,
        strokeSlot = null,
        rippleSlot = ColorSlot.AccentForeground,
        horizontalPaddingDp = 24f,
        hasMinHeight = true
    )
    ButtonStyle.BORDERED -> ButtonChrome(
        fillSlot = null,
        strokeSlot = ColorSlot.Border,
        rippleSlot = ColorSlot.Accent,
        horizontalPaddingDp = 24f,
        hasMinHeight = true
    )
    ButtonStyle.BORDERLESS,
    ButtonStyle.PLAIN -> ButtonChrome(
        fillSlot = null,
        strokeSlot = null,
        rippleSlot = ColorSlot.Accent,
        horizontalPaddingDp = 12f,
        hasMinHeight = false
    )
    ButtonStyle.LINK -> ButtonChrome(
        fillSlot = null,
        strokeSlot = null,
        rippleSlot = ColorSlot.Accent,
        horizontalPaddingDp = 12f,
        hasMinHeight = false,
        underlineLabel = true
    )
    else -> error("unknown button style: $style")
}

private fun labelForegroundSlot(style: Int): ColorSlot = when (style) {
    ButtonStyle.AUTOMATIC,
    ButtonStyle.BORDERED_PROMINENT -> ColorSlot.AccentForeground
    ButtonStyle.LINK,
    ButtonStyle.BORDERLESS,
    ButtonStyle.BORDERED -> ColorSlot.Accent
    ButtonStyle.PLAIN -> ColorSlot.Foreground
    else -> error("unknown button style: $style")
}

private const val DISABLED_CONTAINER_ALPHA = 0.12f
private const val DISABLED_CONTENT_ALPHA = 0.38f
private const val RIPPLE_ALPHA = 0.12f
private const val MIN_BUTTON_HEIGHT_DP = 40f

private val buttonTypeId: WuiTypeId by lazy { NativeBindings.waterui_button_id().toTypeId() }

private val buttonRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_button(node.rawPtr)
    val labelEnv = env.clone()
    ThemeBridge.installColor(
        labelEnv,
        ColorSlot.Foreground,
        NativeBindings.waterui_theme_color(env.raw(), labelForegroundSlot(struct.style).value)
    )
    val labelView = inflateAnyView(context, struct.labelPtr, labelEnv, registry)

    val chrome = buttonChrome(struct.style)
    val container = FrameLayout(context).apply {
        disposeWith(labelEnv)
        isClickable = true
        isFocusable = true

        val horizontal = chrome.horizontalPaddingDp.dp(context).toInt()
        val vertical = chrome.verticalPaddingDp.dp(context).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        if (chrome.hasMinHeight) {
            minimumHeight = MIN_BUTTON_HEIGHT_DP.dp(context).toInt()
        }

        if (chrome.underlineLabel && labelView is TextView) {
            labelView.paintFlags = labelView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        }

        addView(
            labelView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        setOnClickListener {
            NativeBindings.waterui_call_action(struct.actionPtr, env.raw())
        }
    }

    installButtonChrome(container, labelView, chrome, context, env)

    installSemanticAccessibilityLabel(
        target = container,
        content = labelView,
        labelPtr = struct.accessibilityLabelPtr,
        env = env
    )

    container.disposeWith {
        NativeBindings.waterui_drop_action(struct.actionPtr)
    }
    container
}

/**
 * Wires the reactive theme colors and the disabled state into the M3 chrome.
 * All color inputs are observed; the drawable set is rebuilt whenever any of
 * them (or the disabled state) changes.
 */
private fun installButtonChrome(
    container: FrameLayout,
    labelView: View,
    chrome: ButtonChrome,
    context: Context,
    env: WuiEnvironment
) {
    // The M3 button silhouette is a stadium: corners at half the resolved
    // height, whatever that height is.
    val stadium = ShapeAppearanceModel.builder()
        .setAllCornerSizes(RelativeCornerSize(0.5f))
        .build()

    var fillColor: Int? = null
    var strokeColor: Int? = null
    var rippleColor: Int? = null
    var onSurfaceColor: Int? = null
    var isDisabled = false

    fun rebuild() {
        val onSurface = onSurfaceColor ?: return
        val shape = MaterialShapeDrawable(stadium)
        var hasBackground = false

        if (chrome.fillSlot != null) {
            val fill = fillColor ?: return
            shape.fillColor = ColorStateList.valueOf(
                if (isDisabled) adjustAlpha(onSurface, DISABLED_CONTAINER_ALPHA) else fill
            )
            hasBackground = true
        } else {
            shape.fillColor = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        if (chrome.strokeSlot != null) {
            val stroke = strokeColor ?: return
            shape.setStroke(
                1f.dp(context),
                if (isDisabled) adjustAlpha(onSurface, DISABLED_CONTAINER_ALPHA) else stroke
            )
            hasBackground = true
        }
        container.background = if (hasBackground) shape else null

        val ripple = rippleColor ?: return
        val mask = MaterialShapeDrawable(stadium)
        mask.fillColor = ColorStateList.valueOf(Color.WHITE)
        container.foreground = RippleDrawable(
            ColorStateList.valueOf(adjustAlpha(ripple, RIPPLE_ALPHA)),
            null,
            mask
        )

        labelView.alpha = if (isDisabled) DISABLED_CONTENT_ALPHA else 1f
    }

    val onSurface = ThemeBridge.foreground(env)
    onSurface.observe { color ->
        onSurfaceColor = color.toColorInt()
        rebuild()
    }
    onSurface.attachTo(container)

    chrome.fillSlot?.let { slot ->
        val fill = ThemeBridge.color(env, slot)
        fill.observe { color ->
            fillColor = color.toColorInt()
            rebuild()
        }
        fill.attachTo(container)
    }
    chrome.strokeSlot?.let { slot ->
        val stroke = ThemeBridge.color(env, slot)
        stroke.observe { color ->
            strokeColor = color.toColorInt()
            rebuild()
        }
        stroke.attachTo(container)
    }
    val ripple = ThemeBridge.color(env, chrome.rippleSlot)
    ripple.observe { color ->
        rippleColor = color.toColorInt()
        rebuild()
    }
    ripple.attachTo(container)

    val disabled = InteractionBridge.disabled(env)
    disabled.observe { value ->
        isDisabled = value
        container.isEnabled = !value
        container.isClickable = !value
        rebuild()
    }
    disabled.attachTo(container)
}

/**
 * Adjusts the alpha of a color.
 */
private fun adjustAlpha(color: Int, factor: Float): Int {
    val alpha = (Color.alpha(color) * factor).toInt()
    return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

internal fun RegistryBuilder.registerWuiButton() {
    register({ buttonTypeId }, buttonRenderer)
}
