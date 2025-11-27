package dev.waterui.android.runtime

import android.view.View
import dev.waterui.android.reactive.WuiComputed

/**
 * Helper extension to observe theme colors and apply them to views.
 * Reduces boilerplate in components that need to observe theme colors.
 *
 * Example usage:
 * ```
 * editText.observeThemeColor(env, ThemeBridge::foreground, editText::setTextColor)
 * ```
 */
fun View.observeThemeColor(
    env: WuiEnvironment,
    colorProvider: (WuiEnvironment) -> WuiComputed<ResolvedColorStruct>,
    applier: (Int) -> Unit
) {
    val computed = colorProvider(env)
    computed.observe { color -> applier(color.toColorInt()) }
    computed.attachTo(this)
}

/**
 * Helper extension to observe theme colors with animation support.
 */
fun View.observeThemeColorWithAnimation(
    env: WuiEnvironment,
    colorProvider: (WuiEnvironment) -> WuiComputed<ResolvedColorStruct>,
    applier: (Int, WuiAnimation) -> Unit
) {
    val computed = colorProvider(env)
    computed.observeWithAnimation { color, animation -> applier(color.toColorInt(), animation) }
    computed.attachTo(this)
}
