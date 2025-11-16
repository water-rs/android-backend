package dev.waterui.android.runtime

import dev.waterui.android.reactive.WuiComputed
import java.io.Closeable

object ThemeBridge {
    fun background(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        WuiComputed.colorFromComputed(NativeBindings.waterui_theme_color_background(env.raw()), env)

    fun surface(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        WuiComputed.colorFromComputed(NativeBindings.waterui_theme_color_surface(env.raw()), env)

    fun surfaceVariant(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        WuiComputed.colorFromComputed(NativeBindings.waterui_theme_color_surface_variant(env.raw()), env)

    fun border(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        WuiComputed.colorFromComputed(NativeBindings.waterui_theme_color_border(env.raw()), env)

    fun foreground(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        WuiComputed.colorFromComputed(NativeBindings.waterui_theme_color_foreground(env.raw()), env)

    fun mutedForeground(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        WuiComputed.colorFromComputed(NativeBindings.waterui_theme_color_muted_foreground(env.raw()), env)

    fun accent(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        WuiComputed.colorFromComputed(NativeBindings.waterui_theme_color_accent(env.raw()), env)

    fun accentForeground(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        WuiComputed.colorFromComputed(NativeBindings.waterui_theme_color_accent_foreground(env.raw()), env)

    fun bodyFont(env: WuiEnvironment): WuiComputed<ResolvedFontStruct> =
        WuiComputed.fontFromComputed(NativeBindings.waterui_theme_font_body(env.raw()), env)
}

fun Closeable.attachTo(view: android.view.View) {
    view.disposeWith(this)
}
