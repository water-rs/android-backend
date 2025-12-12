package dev.waterui.android.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
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

    val container = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true

        // Apply style-based appearance following Material Design guidelines
        when (struct.style) {
            ButtonStyle.AUTOMATIC -> applyAutomaticStyle(this, context, env)
            ButtonStyle.PLAIN -> applyPlainStyle(this, context)
            ButtonStyle.LINK -> applyLinkStyle(this, labelView, context)
            ButtonStyle.BORDERLESS -> applyBorderlessStyle(this, context, env)
            ButtonStyle.BORDERED -> applyBorderedStyle(this, context, env)
            ButtonStyle.BORDERED_PROMINENT -> applyBorderedProminentStyle(this, context, env)
            else -> applyAutomaticStyle(this, context, env)
        }

        addView(labelView)
        setOnClickListener {
            NativeBindings.waterui_call_action(struct.actionPtr, env.raw())
        }
    }

    // If label is text, apply appropriate text color based on style
    if (labelView is TextView) {
        when (struct.style) {
            ButtonStyle.LINK -> {
                // Link style uses blue text color
                labelView.setTextColor(Color.parseColor("#1976D2"))
            }
            ButtonStyle.BORDERED_PROMINENT -> {
                // Prominent style uses white text on colored background
                labelView.setTextColor(Color.WHITE)
            }
            else -> {
                // Other styles use accent foreground color
                val contentColor = ThemeBridge.accentForeground(env)
                contentColor.observe { color ->
                    labelView.setTextColor(color.toColorInt())
                }
                contentColor.attachTo(labelView)
            }
        }
    }

    container.disposeWith {
        NativeBindings.waterui_drop_action(struct.actionPtr)
    }
    container
}

/**
 * Automatic style: Standard Material button with ripple effect.
 * Uses accent color for ripple, standard padding.
 */
private fun applyAutomaticStyle(view: FrameLayout, context: Context, env: WuiEnvironment) {
    val horizontal = 16f.dp(context).toInt()
    val vertical = 8f.dp(context).toInt()
    view.setPadding(horizontal, vertical, horizontal, vertical)

    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        val colorInt = color.toColorInt()
        val rippleColor = ColorStateList.valueOf(adjustAlpha(colorInt, 0.2f))
        view.foreground = RippleDrawable(rippleColor, null, null)
    }
    accent.attachTo(view)
}

/**
 * Plain style: No background, minimal padding.
 * Text-only appearance for inline actions.
 */
private fun applyPlainStyle(view: FrameLayout, context: Context) {
    val padding = 8f.dp(context).toInt()
    view.setPadding(padding, padding, padding, padding)
    // No background or foreground effects
}

/**
 * Link style: Blue text, no background, appears as hyperlink.
 * Adds underline effect to TextView labels.
 */
private fun applyLinkStyle(view: FrameLayout, labelView: View, context: Context) {
    val horizontal = 4f.dp(context).toInt()
    val vertical = 4f.dp(context).toInt()
    view.setPadding(horizontal, vertical, horizontal, vertical)

    // Add underline effect for text labels
    if (labelView is TextView) {
        labelView.paintFlags = labelView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
    }

    // Subtle ripple on tap
    val rippleColor = ColorStateList.valueOf(adjustAlpha(Color.parseColor("#1976D2"), 0.1f))
    view.foreground = RippleDrawable(rippleColor, null, null)
}

/**
 * Borderless style: No visible border, but with ripple feedback.
 * Good for toolbar actions and secondary buttons.
 */
private fun applyBorderlessStyle(view: FrameLayout, context: Context, env: WuiEnvironment) {
    val horizontal = 12f.dp(context).toInt()
    val vertical = 8f.dp(context).toInt()
    view.setPadding(horizontal, vertical, horizontal, vertical)

    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        val colorInt = color.toColorInt()
        val rippleColor = ColorStateList.valueOf(adjustAlpha(colorInt, 0.15f))
        view.foreground = RippleDrawable(rippleColor, null, null)
    }
    accent.attachTo(view)
}

/**
 * Bordered style: Outlined button with colored border.
 * Material Design "Outlined Button" style.
 */
private fun applyBorderedStyle(view: FrameLayout, context: Context, env: WuiEnvironment) {
    val horizontal = 16f.dp(context).toInt()
    val vertical = 8f.dp(context).toInt()
    view.setPadding(horizontal, vertical, horizontal, vertical)

    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        val colorInt = color.toColorInt()
        val strokeWidth = 1.5f.dp(context).toInt()
        val cornerRadius = 8f.dp(context)

        val border = GradientDrawable().apply {
            setStroke(strokeWidth, colorInt)
            setCornerRadius(cornerRadius)
            setColor(Color.TRANSPARENT)
        }
        view.background = border

        val rippleColor = ColorStateList.valueOf(adjustAlpha(colorInt, 0.15f))
        val mask = GradientDrawable().apply {
            setCornerRadius(cornerRadius)
            setColor(Color.WHITE)
        }
        view.foreground = RippleDrawable(rippleColor, null, mask)
    }
    accent.attachTo(view)
}

/**
 * Bordered Prominent style: Filled button with accent background.
 * Material Design "Filled Button" or "Primary Button" style.
 */
private fun applyBorderedProminentStyle(view: FrameLayout, context: Context, env: WuiEnvironment) {
    val horizontal = 20f.dp(context).toInt()
    val vertical = 10f.dp(context).toInt()
    view.setPadding(horizontal, vertical, horizontal, vertical)

    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        val colorInt = color.toColorInt()
        val cornerRadius = 8f.dp(context)

        val bg = GradientDrawable().apply {
            setColor(colorInt)
            setCornerRadius(cornerRadius)
        }
        view.background = bg

        // White ripple effect on colored background
        val rippleColor = ColorStateList.valueOf(adjustAlpha(Color.WHITE, 0.25f))
        val mask = GradientDrawable().apply {
            setCornerRadius(cornerRadius)
            setColor(Color.WHITE)
        }
        view.foreground = RippleDrawable(rippleColor, null, mask)
    }
    accent.attachTo(view)
}

/**
 * Adjusts the alpha of a color.
 */
private fun adjustAlpha(color: Int, factor: Float): Int {
    val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
    return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

internal fun RegistryBuilder.registerWuiButton() {
    register({ buttonTypeId }, buttonRenderer)
}
