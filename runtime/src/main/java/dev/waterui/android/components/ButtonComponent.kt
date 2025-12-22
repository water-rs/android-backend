package dev.waterui.android.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.dp

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

private val buttonTypeId: WuiTypeId by lazy { NativeBindings.waterui_button_id().toTypeId() }

private val buttonRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_button(node.rawPtr)
    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)

    if (labelView is TextView) {
        labelView.includeFontPadding = false
        labelView.setLineSpacing(0f, 1f)
    }
    labelView.isClickable = false
    labelView.isFocusable = false

    val container = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        clipToPadding = false

        // Apply style-based appearance matching WaterUI's cross-platform defaults.
        when (struct.style) {
            ButtonStyle.AUTOMATIC -> applyAutomaticStyle(this, context, env)
            ButtonStyle.PLAIN -> applyPlainStyle(this, context, env)
            ButtonStyle.LINK -> applyLinkStyle(this, labelView, context, env)
            ButtonStyle.BORDERLESS -> applyBorderlessStyle(this, context, env)
            ButtonStyle.BORDERED -> applyBorderedStyle(this, context, env)
            ButtonStyle.BORDERED_PROMINENT -> applyBorderedProminentStyle(this, context, env)
            else -> applyAutomaticStyle(this, context, env)
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        addView(labelView, params)
        setOnClickListener {
            NativeBindings.waterui_call_action(struct.actionPtr, env.raw())
        }
    }

    // If label is text, apply appropriate text color based on style
    if (labelView is TextView) {
        val contentColor = when (struct.style) {
            ButtonStyle.AUTOMATIC -> ThemeBridge.foreground(env)
            ButtonStyle.BORDERED_PROMINENT -> ThemeBridge.accentForeground(env)
            ButtonStyle.BORDERED -> ThemeBridge.accent(env)
            ButtonStyle.BORDERLESS -> ThemeBridge.accent(env)
            ButtonStyle.PLAIN -> ThemeBridge.accent(env)
            ButtonStyle.LINK -> ThemeBridge.accent(env)
            else -> ThemeBridge.accent(env)
        }
        contentColor.observe { color ->
            labelView.setTextColor(color.toColorInt())
        }
        contentColor.attachTo(labelView)
    }

    container.disposeWith {
        NativeBindings.waterui_drop_action(struct.actionPtr)
    }
    container
}

/**
 * Automatic style: Material 3 filled tonal button with ripple feedback.
 * Layout metrics align with iOS sizing.
 */
private fun applyAutomaticStyle(view: FrameLayout, context: Context, env: WuiEnvironment) {
    applyButtonMetrics(view, context, horizontalDp = 8f, verticalDp = 4f)
    val cornerRadius = 20f.dp(context)
    val shape = createShapeDrawable(cornerRadius)
    view.background = shape

    val background = ThemeBridge.surfaceVariant(env)
    background.observe { color ->
        shape.fillColor = ColorStateList.valueOf(color.toColorInt())
    }
    background.attachTo(view)

    applyRipple(view, ThemeBridge.foreground(env), cornerRadius, 0.12f)
}

/**
 * Plain style: No background, minimal padding.
 * Text-only appearance for inline actions.
 */
private fun applyPlainStyle(view: FrameLayout, context: Context, env: WuiEnvironment) {
    applyButtonMetrics(view, context, horizontalDp = 8f, verticalDp = 4f)
    applyRipple(view, ThemeBridge.accent(env), 20f.dp(context), 0.12f)
}

/**
 * Link style: Blue text, no background, appears as hyperlink.
 * Adds underline effect to TextView labels.
 */
private fun applyLinkStyle(
    view: FrameLayout,
    labelView: View,
    context: Context,
    env: WuiEnvironment
) {
    applyButtonMetrics(view, context, horizontalDp = 0f, verticalDp = 0f)

    // Add underline effect for text labels
    if (labelView is TextView) {
        labelView.paintFlags = labelView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
    }

    applyRipple(view, ThemeBridge.accent(env), 20f.dp(context), 0.1f)
}

/**
 * Borderless style: No visible border, but with ripple feedback.
 * Good for toolbar actions and secondary buttons.
 */
private fun applyBorderlessStyle(view: FrameLayout, context: Context, env: WuiEnvironment) {
    applyButtonMetrics(view, context, horizontalDp = 8f, verticalDp = 4f)
    applyRipple(view, ThemeBridge.accent(env), 20f.dp(context), 0.12f)
}

/**
 * Bordered style: Outlined button with colored border.
 * Material Design "Outlined Button" style.
 */
private fun applyBorderedStyle(view: FrameLayout, context: Context, env: WuiEnvironment) {
    applyButtonMetrics(view, context, horizontalDp = 8f, verticalDp = 4f)
    val cornerRadius = 20f.dp(context)
    val strokeWidth = 1f.dp(context)
    val shape = createShapeDrawable(cornerRadius).apply {
        fillColor = ColorStateList.valueOf(Color.TRANSPARENT)
    }
    view.background = shape

    val border = ThemeBridge.border(env)
    border.observe { color ->
        shape.setStroke(strokeWidth, color.toColorInt())
    }
    border.attachTo(view)

    applyRipple(view, ThemeBridge.accent(env), cornerRadius, 0.12f)
}

/**
 * Bordered Prominent style: Filled button with accent background.
 * Material Design "Filled Button" or "Primary Button" style.
 */
private fun applyBorderedProminentStyle(view: FrameLayout, context: Context, env: WuiEnvironment) {
    applyButtonMetrics(view, context, horizontalDp = 8f, verticalDp = 4f)
    val cornerRadius = 20f.dp(context)
    val shape = createShapeDrawable(cornerRadius)
    view.background = shape

    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        shape.fillColor = ColorStateList.valueOf(color.toColorInt())
    }
    accent.attachTo(view)

    applyRipple(view, ThemeBridge.accentForeground(env), cornerRadius, 0.2f)
}

/**
 * Adjusts the alpha of a color.
 */
private fun adjustAlpha(color: Int, factor: Float): Int {
    val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
    return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

private fun applyButtonMetrics(
    view: FrameLayout,
    context: Context,
    horizontalDp: Float,
    verticalDp: Float
) {
    val horizontal = horizontalDp.dp(context).toInt()
    val vertical = verticalDp.dp(context).toInt()
    view.setPadding(horizontal, vertical, horizontal, vertical)
}

private fun createShapeDrawable(cornerRadius: Float): MaterialShapeDrawable {
    val shapeModel = ShapeAppearanceModel.builder()
        .setAllCornerSizes(cornerRadius)
        .build()
    return MaterialShapeDrawable(shapeModel)
}

private fun applyRipple(
    view: FrameLayout,
    colorSignal: WuiComputed<ResolvedColorStruct>,
    cornerRadius: Float,
    alpha: Float
) {
    val mask = GradientDrawable().apply {
        setCornerRadius(cornerRadius)
        setColor(Color.WHITE)
    }
    colorSignal.observe { color ->
        val colorInt = color.toColorInt()
        val rippleColor = ColorStateList.valueOf(adjustAlpha(colorInt, alpha))
        view.foreground = RippleDrawable(rippleColor, null, mask)
    }
    colorSignal.attachTo(view)
}

internal fun RegistryBuilder.registerWuiButton() {
    register({ buttonTypeId }, buttonRenderer)
}
