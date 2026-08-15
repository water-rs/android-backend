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
            else -> error("unknown color scheme: $value")
        }
    }
}

/**
 * A reactive theme signal that stores a value and notifies WaterUI watchers when updated.
 *
 * This is the key class for proper reactive theme integration:
 * - Native code creates this signal with an initial value
 * - The computed pointer is installed into the WaterUI environment
 * - When the value changes (e.g., dark mode toggle), call [setValue]
 * - All WaterUI views watching this signal will automatically update
 */
class ReactiveColorSignal(initialArgb: Int) : Closeable {
    private var statePtr = NativeBindings.waterui_create_reactive_color_state(initialArgb)
    private var computedTaken = false

    fun takeComputed(): Long {
        check(!computedTaken) { "reactive color computed signal was already consumed" }
        computedTaken = true
        return NativeBindings.waterui_reactive_color_state_to_computed(requireState())
    }

    fun setValue(argb: Int) {
        NativeBindings.waterui_reactive_color_state_set(requireState(), argb)
    }

    override fun close() {
        NativeBindings.waterui_drop_reactive_color_state(requireState())
        statePtr = 0L
    }

    private fun requireState(): Long = statePtr.also {
        check(it != 0L) { "reactive color state is closed" }
    }
}

class ReactiveColorSchemeSignal(initial: ColorScheme) : Closeable {
    private var statePtr = NativeBindings.waterui_create_reactive_color_scheme_state(initial.value)
    private var computedTaken = false

    fun takeComputed(): Long {
        check(!computedTaken) { "reactive color-scheme computed signal was already consumed" }
        computedTaken = true
        return NativeBindings.waterui_reactive_color_scheme_state_to_computed(requireState())
    }

    fun setValue(scheme: ColorScheme) {
        NativeBindings.waterui_reactive_color_scheme_state_set(requireState(), scheme.value)
    }

    override fun close() {
        NativeBindings.waterui_drop_reactive_color_scheme_state(requireState())
        statePtr = 0L
    }

    private fun requireState(): Long = statePtr.also {
        check(it != 0L) { "reactive color-scheme state is closed" }
    }
}

class ReactiveFontSignal(initial: ResolvedFontStruct) : Closeable {
    private var statePtr: Long
    private var computedTaken = false

    init {
        check(initial.family == null) {
            "Android platform theme font signals must use the system font family"
        }
        statePtr = NativeBindings.waterui_create_reactive_font_state(
            initial.size,
            initial.weight
        )
    }

    fun takeComputed(): Long {
        check(!computedTaken) { "reactive font computed signal was already consumed" }
        computedTaken = true
        return NativeBindings.waterui_reactive_font_state_to_computed(requireState())
    }

    fun setValue(font: ResolvedFontStruct) {
        check(font.family == null) {
            "Android platform theme font signals must use the system font family"
        }
        NativeBindings.waterui_reactive_font_state_set(requireState(), font.size, font.weight)
    }

    override fun close() {
        NativeBindings.waterui_drop_reactive_font_state(requireState())
        statePtr = 0L
    }

    private fun requireState(): Long = statePtr.also {
        check(it != 0L) { "reactive font state is closed" }
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
    AccentForeground(7),
    AccentContainer(8),
    Tertiary(9),
    TertiaryContainer(10)
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
 * This object provides slot-based reactive color, font, and color-scheme installation.
 *
 * ## Usage for Reactive Themes
 *
 * ```kotlin
 * // Create reactive signals for each theme color
 * val bgSignal = ReactiveColorSignal(lightBgColor)
 * val fgSignal = ReactiveColorSignal(lightFgColor)
 *
 * // Install into environment
 * ThemeBridge.installColor(env, ColorSlot.Background, bgSignal.takeComputed())
 * ThemeBridge.installColor(env, ColorSlot.Foreground, fgSignal.takeComputed())
 *
 * // When system appearance changes:
 * if (isDarkMode) {
 *     bgSignal.setValue(darkBgColor)
 *     fgSignal.setValue(darkFgColor)
 * }
 * // Views automatically update!
 * ```
 */
object ThemeBridge {

    /**
     * Installs a color scheme signal into the environment.
     */
    fun installColorScheme(env: WuiEnvironment, signalPtr: Long) {
        NativeBindings.waterui_theme_install_color_scheme(env.raw(), signalPtr)
    }

    fun colorScheme(env: WuiEnvironment): WuiComputed<Int> {
        val ptr = NativeBindings.waterui_theme_color_scheme(env.raw())
        check(ptr != 0L) { "WaterUI environment has no color-scheme signal" }
        return WuiComputed.colorScheme(ptr)
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
        return WuiComputed.colorFromComputed(ptr)
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
        return WuiComputed.fontFromComputed(ptr)
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

    fun accentContainer(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.AccentContainer)

    fun tertiary(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.Tertiary)

    fun tertiaryContainer(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
        color(env, ColorSlot.TertiaryContainer)

    // Non-body slots are consumed on the Rust side through styled text and
    // arrive here already resolved; only the body slot needs a direct
    // accessor, for widgets that set a control font themselves.
    fun bodyFont(env: WuiEnvironment): WuiComputed<ResolvedFontStruct> =
        font(env, FontSlot.Body)
}

fun Closeable.attachTo(view: android.view.View) {
    view.disposeWith(this)
}
