package dev.waterui.android.runtime

import dev.waterui.android.ffi.WatcherJni
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
 * A reactive color scheme signal that stores a value and notifies WaterUI watchers when updated.
 */
class ReactiveColorSchemeSignal(initialScheme: ColorScheme) : Closeable {
    private val statePtr: Long =
        WatcherJni.createReactiveColorSchemeState(initialScheme.value)
    private var computedPtr: Long = 0L

    fun toComputed(): Long {
        if (computedPtr == 0L) {
            computedPtr = WatcherJni.reactiveColorSchemeStateToComputed(statePtr)
        }
        return computedPtr
    }

    fun setValue(scheme: ColorScheme) {
        WatcherJni.reactiveColorSchemeStateSet(statePtr, scheme.value)
    }

    override fun close() {
        // State is owned by the computed signal
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
    /** Native state pointer - holds the value and watcher list */
    private val statePtr: Long = WatcherJni.createReactiveColorState(initialArgb)
    
    /** Computed pointer for installation into WaterUI */
    private var computedPtr: Long = 0L
    
    /**
     * Gets the computed pointer for installation.
     * Call this once to get the pointer, then install it.
     */
    fun toComputed(): Long {
        if (computedPtr == 0L) {
            computedPtr = WatcherJni.reactiveColorStateToComputed(statePtr)
        }
        return computedPtr
    }
    
    /**
     * Updates the color value and notifies all watchers.
     * This triggers reactive updates in WaterUI.
     */
    fun setValue(argb: Int) {
        WatcherJni.reactiveColorStateSet(statePtr, argb)
    }
    
    override fun close() {
        // State is owned by the computed signal; don't double-free
    }
}

/**
 * A reactive font signal that stores a font and notifies WaterUI watchers when updated.
 *
 * Note: WuiResolvedFont only contains size and weight. Family is not currently supported.
 * Weight uses the WuiFontWeight enum index (0=Thin ... 8=Black).
 */
class ReactiveFontSignal(
    size: Float = 16f,
    weight: Int = 3 // WuiFontWeight_Normal
) : Closeable {
    private val statePtr: Long = WatcherJni.createReactiveFontState(size, weight)
    private var computedPtr: Long = 0L
    
    fun toComputed(): Long {
        if (computedPtr == 0L) {
            computedPtr = WatcherJni.reactiveFontStateToComputed(statePtr)
        }
        return computedPtr
    }
    
    // Weight is the WuiFontWeight enum index (0..8), not an Android weight value.
    fun setValue(size: Float, weight: Int = 3) {
        WatcherJni.reactiveFontStateSet(statePtr, size, weight)
    }
    
    override fun close() {
        // State is owned by the computed signal
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
 * - Reactive color and font signal creation
 * - Slot-based color and font installation
 * - System appearance change handling
 *
 * ## Usage for Reactive Themes
 *
 * ```kotlin
 * // Create reactive signals for each theme color
 * val bgSignal = ReactiveColorSignal(lightBgColor)
 * val fgSignal = ReactiveColorSignal(lightFgColor)
 *
 * // Install into environment
 * ThemeBridge.installColor(env, ColorSlot.Background, bgSignal.toComputed())
 * ThemeBridge.installColor(env, ColorSlot.Foreground, fgSignal.toComputed())
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

    // ========== Color Scheme ==========

    /**
     * Creates a constant (non-reactive) color scheme signal.
     * Use for static themes that don't respond to system changes.
     */
    fun createConstantColorSchemeSignal(scheme: ColorScheme): Long {
        return WatcherJni.computedColorSchemeConstant(scheme.value)
    }

    /**
     * Installs a color scheme signal into the environment.
     */
    fun installColorScheme(env: WuiEnvironment, signalPtr: Long) {
        WatcherJni.themeInstallColorScheme(env.raw(), signalPtr)
    }

    /**
     * Returns the current color scheme from the environment.
     */
    fun colorScheme(env: WuiEnvironment): ColorScheme {
        val ptr = WatcherJni.themeColorScheme(env.raw())
        val value = WatcherJni.readComputedColorScheme(ptr)
        WatcherJni.dropComputedColorScheme(ptr)
        return ColorScheme.fromValue(value)
    }

    // ========== Slot-based Color API ==========

    /**
     * Installs a color signal for a specific slot.
     */
    fun installColor(env: WuiEnvironment, slot: ColorSlot, signalPtr: Long) {
        WatcherJni.themeInstallColor(env.raw(), slot.value, signalPtr)
    }

    /**
     * Returns the color signal for a specific slot.
     */
    fun color(env: WuiEnvironment, slot: ColorSlot): WuiComputed<ResolvedColorStruct> {
        val ptr = WatcherJni.themeColor(env.raw(), slot.value)
        return WuiComputed.colorFromComputed(ptr, env)
    }

    // ========== Slot-based Font API ==========

    /**
     * Installs a font signal for a specific slot.
     */
    fun installFont(env: WuiEnvironment, slot: FontSlot, signalPtr: Long) {
        WatcherJni.themeInstallFont(env.raw(), slot.value, signalPtr)
    }

    /**
     * Returns the font signal for a specific slot.
     */
    fun font(env: WuiEnvironment, slot: FontSlot): WuiComputed<ResolvedFontStruct> {
        val ptr = WatcherJni.themeFont(env.raw(), slot.value)
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
