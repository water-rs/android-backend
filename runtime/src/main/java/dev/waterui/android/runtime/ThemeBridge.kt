package dev.waterui.android.runtime

import dev.waterui.android.reactive.WuiComputed
import java.io.Closeable

/**
 * Color scheme preference (Light/Dark).
 *
 * Maps to WaterUI's `ColorScheme` enum in Rust.
 */
enum class ColorScheme(val value: Int) {
    Light(0),
    Dark(1);

    companion object {
        fun fromValue(value: Int): ColorScheme = when (value) {
            0 -> Light
            1 -> Dark
            else -> Light
        }
    }
}

/**
 * Color slot identifiers for theme colors.
 *
 * Maps to WaterUI's `WuiColorSlot` enum in Rust FFI.
 */
enum class ColorSlot(val value: Int) {
    Background(0),
    Surface(1),
    SurfaceVariant(2),
    Border(3),
    Foreground(4),
    MutedForeground(5),
    Accent(6),
    AccentForeground(7)
}

/**
 * Font slot identifiers for theme fonts.
 *
 * Maps to WaterUI's `WuiFontSlot` enum in Rust FFI.
 */
enum class FontSlot(val value: Int) {
    Body(0),
    Title(1),
    Headline(2),
    Subheadline(3),
    Caption(4),
    Footnote(5)
}

/**
 * Bridge for theme-related operations between Android and WaterUI.
 *
 * This object provides:
 * - Color scheme installation and querying
 * - Slot-based color and font installation
 * - Helper methods for querying theme values
 */
object ThemeBridge {

    // ========== Color Scheme ==========

    /**
     * Creates a constant color scheme signal.
     */
    fun createColorSchemeSignal(scheme: ColorScheme): Long {
        return NativeBindings.waterui_computed_color_scheme_constant(scheme.value)
    }

    /**
     * Installs a color scheme signal into the environment.
     */
    fun installColorScheme(env: WuiEnvironment, signalPtr: Long) {
        NativeBindings.waterui_theme_install_color_scheme(env.raw(), signalPtr)
    }

    /**
     * Returns the current color scheme from the environment.
     */
    fun colorScheme(env: WuiEnvironment): WuiComputed<ColorScheme> {
        val ptr = NativeBindings.waterui_theme_color_scheme(env.raw())
        return WuiComputed(
            ptr = ptr,
            read = { NativeBindings.waterui_read_computed_color_scheme(it).let(ColorScheme::fromValue) },
            drop = { NativeBindings.waterui_drop_computed_color_scheme(it) },
            watch = null, // Color scheme watching not implemented yet
            env = env
        )
    }

    // ========== Slot-based Color API ==========

    /**
     * Installs a color signal for a specific slot.
     */
    fun installColor(env: WuiEnvironment, slot: ColorSlot, signalPtr: Long) {
        NativeBindings.waterui_theme_install_color(env.raw(), slot.value, signalPtr)
    }

    /**
     * Returns the color signal for a specific slot.
     */
    fun color(env: WuiEnvironment, slot: ColorSlot): WuiComputed<ResolvedColorStruct> {
        val ptr = NativeBindings.waterui_theme_color(env.raw(), slot.value)
        return WuiComputed.colorFromComputed(ptr, env)
    }

    // ========== Slot-based Font API ==========

    /**
     * Installs a font signal for a specific slot.
     */
    fun installFont(env: WuiEnvironment, slot: FontSlot, signalPtr: Long) {
        NativeBindings.waterui_theme_install_font(env.raw(), slot.value, signalPtr)
    }

    /**
     * Returns the font signal for a specific slot.
     */
    fun font(env: WuiEnvironment, slot: FontSlot): WuiComputed<ResolvedFontStruct> {
        val ptr = NativeBindings.waterui_theme_font(env.raw(), slot.value)
        return WuiComputed.fontFromComputed(ptr, env)
    }

    // ========== Convenience accessors (use slot-based API internally) ==========

    fun background(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.Background)

    fun surface(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.Surface)

    fun surfaceVariant(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.SurfaceVariant)

    fun border(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.Border)

    fun foreground(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.Foreground)

    fun mutedForeground(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.MutedForeground)

    fun accent(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.Accent)

    fun accentForeground(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.AccentForeground)

    fun bodyFont(env: WuiEnvironment): WuiComputed<ResolvedFontStruct> =
        font(env, FontSlot.Body)

    fun titleFont(env: WuiEnvironment): WuiComputed<ResolvedFontStruct> =
        font(env, FontSlot.Title)

    fun headlineFont(env: WuiEnvironment): WuiComputed<ResolvedFontStruct> =
        font(env, FontSlot.Headline)

    fun subheadlineFont(env: WuiEnvironment): WuiComputed<ResolvedFontStruct> =
        font(env, FontSlot.Subheadline)

    fun captionFont(env: WuiEnvironment): WuiComputed<ResolvedFontStruct> =
        font(env, FontSlot.Caption)

    fun footnoteFont(env: WuiEnvironment): WuiComputed<ResolvedFontStruct> =
        font(env, FontSlot.Footnote)
}

fun Closeable.attachTo(view: android.view.View) {
    view.disposeWith(this)
}
