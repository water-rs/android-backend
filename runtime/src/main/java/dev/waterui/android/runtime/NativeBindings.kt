package dev.waterui.android.runtime

import dev.waterui.android.ffi.PointerHelper
import dev.waterui.android.ffi.WaterUILib
import dev.waterui.android.reactive.WatcherCallback
import org.bytedeco.javacpp.Pointer

/**
 * Centralised access to native WaterUI FFI functions.
 * 
 * This module uses a hybrid approach:
 * - JavaCPP-generated bindings (WaterUILib) for simple FFI calls
 * - Minimal JNI (via waterui_android.so) for callback/watcher functions
 * 
 * The actual native library is loaded via JavaCPP's Loader when WaterUILib
 * is first accessed.
 */
internal object NativeBindings {

    /**
     * Bootstrap the native library. Must be called before any other functions.
     * This triggers JavaCPP's Loader to load libwaterui_app.so.
     */
    fun bootstrapNativeBindings() {
        // Access WaterUILib to trigger static initializer which loads the native library
        WaterUILib::class.java
        // Also load the JNI helper library for callbacks
        System.loadLibrary("waterui_android")
    }

    // ========== Core Functions (JavaCPP) ==========
    
    fun waterui_init(): Long = WaterUILib.waterui_init().address()
    
    fun waterui_main(): Long = WaterUILib.waterui_main().address()
    
    fun waterui_view_id(anyViewPtr: Long): String {
        val str = WaterUILib.waterui_view_id(ptr(anyViewPtr))
        return wuiStrToString(str)
    }
    
    fun waterui_view_body(anyViewPtr: Long, envPtr: Long): Long =
        WaterUILib.waterui_view_body(ptr(anyViewPtr), ptr(envPtr)).address()
    
    fun waterui_configure_hot_reload_endpoint(host: String, port: Int) {
        WaterUILib.waterui_configure_hot_reload_endpoint(host, port.toShort())
    }
    
    fun waterui_configure_hot_reload_directory(path: String) {
        WaterUILib.waterui_configure_hot_reload_directory(path)
    }

    // ========== Type Identifiers (JavaCPP) ==========
    
    fun waterui_empty_id(): String = wuiStrToString(WaterUILib.waterui_empty_id())
    fun waterui_text_id(): String = wuiStrToString(WaterUILib.waterui_text_id())
    fun waterui_plain_id(): String = wuiStrToString(WaterUILib.waterui_plain_id())
    fun waterui_button_id(): String = wuiStrToString(WaterUILib.waterui_button_id())
    fun waterui_color_id(): String = wuiStrToString(WaterUILib.waterui_color_id())
    fun waterui_text_field_id(): String = wuiStrToString(WaterUILib.waterui_text_field_id())
    fun waterui_stepper_id(): String = wuiStrToString(WaterUILib.waterui_stepper_id())
    fun waterui_progress_id(): String = wuiStrToString(WaterUILib.waterui_progress_id())
    fun waterui_dynamic_id(): String = wuiStrToString(WaterUILib.waterui_dynamic_id())
    fun waterui_scroll_view_id(): String = wuiStrToString(WaterUILib.waterui_scroll_view_id())
    fun waterui_spacer_id(): String = wuiStrToString(WaterUILib.waterui_spacer_id())
    fun waterui_toggle_id(): String = wuiStrToString(WaterUILib.waterui_toggle_id())
    fun waterui_slider_id(): String = wuiStrToString(WaterUILib.waterui_slider_id())
    fun waterui_renderer_view_id(): String = wuiStrToString(WaterUILib.waterui_renderer_view_id())
    fun waterui_fixed_container_id(): String = wuiStrToString(WaterUILib.waterui_fixed_container_id())
    fun waterui_picker_id(): String = wuiStrToString(WaterUILib.waterui_picker_id())
    fun waterui_layout_container_id(): String = wuiStrToString(WaterUILib.waterui_layout_container_id())

    // ========== Theme: Color Scheme (JavaCPP) ==========
    
    fun waterui_computed_color_scheme_constant(scheme: Int): Long =
        WaterUILib.waterui_computed_color_scheme_constant(scheme).address()
    
    fun waterui_read_computed_color_scheme(ptr: Long): Int =
        WaterUILib.waterui_read_computed_color_scheme(ptr(ptr))
    
    fun waterui_drop_computed_color_scheme(ptr: Long) {
        WaterUILib.waterui_drop_computed_color_scheme(ptr(ptr))
    }
    
    // Watcher functions need JNI for callbacks
    external fun waterui_watch_computed_color_scheme(computed: Long, watcher: WatcherStruct): Long
    external fun waterui_new_watcher_color_scheme(data: Long, call: Long, drop: Long): Long
    
    fun waterui_theme_install_color_scheme(envPtr: Long, signalPtr: Long) {
        WaterUILib.waterui_theme_install_color_scheme(ptr(envPtr), ptr(signalPtr))
    }
    
    fun waterui_theme_color_scheme(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_scheme(ptr(envPtr)).address()

    // ========== Theme: Slot-based Color API (JavaCPP) ==========
    
    fun waterui_theme_install_color(envPtr: Long, slot: Int, signalPtr: Long) {
        WaterUILib.waterui_theme_install_color(ptr(envPtr), slot, ptr(signalPtr))
    }
    
    fun waterui_theme_color(envPtr: Long, slot: Int): Long =
        WaterUILib.waterui_theme_color(ptr(envPtr), slot).address()

    // ========== Theme: Slot-based Font API (JavaCPP) ==========
    
    fun waterui_theme_install_font(envPtr: Long, slot: Int, signalPtr: Long) {
        WaterUILib.waterui_theme_install_font(ptr(envPtr), slot, ptr(signalPtr))
    }
    
    fun waterui_theme_font(envPtr: Long, slot: Int): Long =
        WaterUILib.waterui_theme_font(ptr(envPtr), slot).address()

    // ========== Theme: Legacy per-token APIs (JavaCPP) ==========
    
    fun waterui_theme_color_background(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_background(ptr(envPtr)).address()
    fun waterui_theme_color_surface(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_surface(ptr(envPtr)).address()
    fun waterui_theme_color_surface_variant(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_surface_variant(ptr(envPtr)).address()
    fun waterui_theme_color_border(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_border(ptr(envPtr)).address()
    fun waterui_theme_color_foreground(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_foreground(ptr(envPtr)).address()
    fun waterui_theme_color_muted_foreground(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_muted_foreground(ptr(envPtr)).address()
    fun waterui_theme_color_accent(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_accent(ptr(envPtr)).address()
    fun waterui_theme_color_accent_foreground(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_accent_foreground(ptr(envPtr)).address()
    fun waterui_theme_font_body(envPtr: Long): Long =
        WaterUILib.waterui_theme_font_body(ptr(envPtr)).address()
    fun waterui_theme_font_title(envPtr: Long): Long =
        WaterUILib.waterui_theme_font_title(ptr(envPtr)).address()
    fun waterui_theme_font_headline(envPtr: Long): Long =
        WaterUILib.waterui_theme_font_headline(ptr(envPtr)).address()
    fun waterui_theme_font_subheadline(envPtr: Long): Long =
        WaterUILib.waterui_theme_font_subheadline(ptr(envPtr)).address()
    fun waterui_theme_font_caption(envPtr: Long): Long =
        WaterUILib.waterui_theme_font_caption(ptr(envPtr)).address()
    fun waterui_theme_font_footnote(envPtr: Long): Long =
        WaterUILib.waterui_theme_font_footnote(ptr(envPtr)).address()

    // ========== Environment (JavaCPP) ==========
    
    fun waterui_clone_env(envPtr: Long): Long =
        WaterUILib.waterui_clone_env(ptr(envPtr)).address()
    
    fun waterui_env_drop(envPtr: Long) {
        WaterUILib.waterui_drop_env(ptr(envPtr))
    }

    fun waterui_drop_anyview(viewPtr: Long) {
        WaterUILib.waterui_drop_anyview(ptr(viewPtr))
    }

    // ========== Layout bridging (Hybrid - JNI for complex structs) ==========
    
    external fun waterui_force_as_layout_container(viewPtr: Long): LayoutContainerStruct
    external fun waterui_force_as_fixed_container(viewPtr: Long): FixedContainerStruct
    external fun waterui_layout_propose(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>): Array<ProposalStruct>
    external fun waterui_layout_size(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>): SizeStruct
    external fun waterui_layout_place(layoutPtr: Long, bounds: RectStruct, proposal: ProposalStruct, children: Array<ChildMetadataStruct>): Array<RectStruct>

    // ========== AnyViews (JavaCPP) ==========
    
    fun waterui_any_views_len(handle: Long): Int =
        WaterUILib.waterui_anyviews_len(ptr(handle)).toInt()
    
    fun waterui_any_views_get_view(handle: Long, index: Int): Long =
        WaterUILib.waterui_anyviews_get_view(ptr(handle), index.toLong()).address()
    
    fun waterui_any_views_get_id(handle: Long, index: Int): Int =
        WaterUILib.waterui_anyviews_get_id(ptr(handle), index.toLong()).inner()
    
    fun waterui_drop_any_views(handle: Long) {
        WaterUILib.waterui_drop_anyviews(ptr(handle))
    }

    // ========== Reactive bindings (JNI for callbacks) ==========
    
    external fun waterui_watch_binding_bool(bindingPtr: Long, watcher: WatcherStruct): Long
    external fun waterui_watch_binding_int(bindingPtr: Long, watcher: WatcherStruct): Long
    external fun waterui_watch_binding_double(bindingPtr: Long, watcher: WatcherStruct): Long
    external fun waterui_watch_binding_str(bindingPtr: Long, watcher: WatcherStruct): Long

    external fun waterui_create_bool_watcher(callback: WatcherCallback<Boolean>): WatcherStruct
    external fun waterui_create_int_watcher(callback: WatcherCallback<Int>): WatcherStruct
    external fun waterui_create_double_watcher(callback: WatcherCallback<Double>): WatcherStruct
    external fun waterui_create_string_watcher(callback: WatcherCallback<String>): WatcherStruct
    external fun waterui_create_any_view_watcher(callback: WatcherCallback<Long>): WatcherStruct
    external fun waterui_create_styled_str_watcher(callback: WatcherCallback<StyledStrStruct>): WatcherStruct
    external fun waterui_create_resolved_color_watcher(callback: WatcherCallback<ResolvedColorStruct>): WatcherStruct
    external fun waterui_create_resolved_font_watcher(callback: WatcherCallback<ResolvedFontStruct>): WatcherStruct
    external fun waterui_create_picker_items_watcher(callback: WatcherCallback<Array<PickerItemStruct>>): WatcherStruct

    // ========== Binding setters/getters (JavaCPP) ==========
    
    fun waterui_set_binding_bool(bindingPtr: Long, value: Boolean) {
        WaterUILib.waterui_set_binding_bool(ptr(bindingPtr), value)
    }
    
    fun waterui_set_binding_int(bindingPtr: Long, value: Int) {
        WaterUILib.waterui_set_binding_i32(ptr(bindingPtr), value)
    }
    
    fun waterui_set_binding_double(bindingPtr: Long, value: Double) {
        WaterUILib.waterui_set_binding_f64(ptr(bindingPtr), value)
    }
    
    // String binding needs JNI for byte array handling
    external fun waterui_set_binding_str(bindingPtr: Long, bytes: ByteArray)

    fun waterui_read_binding_bool(bindingPtr: Long): Boolean =
        WaterUILib.waterui_read_binding_bool(ptr(bindingPtr))
    
    fun waterui_read_binding_int(bindingPtr: Long): Int =
        WaterUILib.waterui_read_binding_i32(ptr(bindingPtr))
    
    fun waterui_read_binding_double(bindingPtr: Long): Double =
        WaterUILib.waterui_read_binding_f64(ptr(bindingPtr))
    
    // String binding needs JNI for byte array handling
    external fun waterui_read_binding_str(bindingPtr: Long): ByteArray

    fun waterui_drop_binding_bool(bindingPtr: Long) {
        WaterUILib.waterui_drop_binding_bool(ptr(bindingPtr))
    }
    
    fun waterui_drop_binding_int(bindingPtr: Long) {
        WaterUILib.waterui_drop_binding_i32(ptr(bindingPtr))
    }
    
    fun waterui_drop_binding_double(bindingPtr: Long) {
        WaterUILib.waterui_drop_binding_f64(ptr(bindingPtr))
    }
    
    fun waterui_drop_binding_str(bindingPtr: Long) {
        WaterUILib.waterui_drop_binding_str(ptr(bindingPtr))
    }

    // ========== Watcher utilities (Hybrid) ==========
    
    fun waterui_drop_watcher_guard(guardPtr: Long) {
        WaterUILib.waterui_drop_box_watcher_guard(ptr(guardPtr))
    }
    
    fun waterui_get_animation(metadataPtr: Long): Int =
        WaterUILib.waterui_get_animation(ptr(metadataPtr))
    
    // Dynamic connect needs JNI for callbacks
    external fun waterui_dynamic_connect(dynamicPtr: Long, watcher: WatcherStruct)

    // ========== Computed helpers (Hybrid) ==========
    
    fun waterui_read_computed_f64(computedPtr: Long): Double =
        WaterUILib.waterui_read_computed_f64(ptr(computedPtr))
    
    external fun waterui_watch_computed_f64(computedPtr: Long, watcher: WatcherStruct): Long
    
    fun waterui_drop_computed_f64(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_f64(ptr(computedPtr))
    }

    fun waterui_read_computed_i32(computedPtr: Long): Int =
        WaterUILib.waterui_read_computed_i32(ptr(computedPtr))
    
    external fun waterui_watch_computed_i32(computedPtr: Long, watcher: WatcherStruct): Long
    
    fun waterui_drop_computed_i32(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_i32(ptr(computedPtr))
    }

    // StyledStr needs JNI for struct conversion
    external fun waterui_read_computed_styled_str(computedPtr: Long): StyledStrStruct
    external fun waterui_watch_computed_styled_str(computedPtr: Long, watcher: WatcherStruct): Long
    
    fun waterui_drop_computed_styled_str(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_styled_str(ptr(computedPtr))
    }
    
    external fun waterui_watch_computed_resolved_font(computedPtr: Long, watcher: WatcherStruct): Long
    
    // Picker items need JNI for array handling
    external fun waterui_read_computed_picker_items(computedPtr: Long): Array<PickerItemStruct>
    external fun waterui_watch_computed_picker_items(computedPtr: Long, watcher: WatcherStruct): Long
    
    fun waterui_drop_computed_picker_items(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_picker_items(ptr(computedPtr))
    }

    // ========== Font (JavaCPP) ==========
    
    fun waterui_drop_font(fontPtr: Long) {
        WaterUILib.waterui_drop_font(ptr(fontPtr))
    }
    
    fun waterui_resolve_font(fontPtr: Long, envPtr: Long): Long =
        WaterUILib.waterui_resolve_font(ptr(fontPtr), ptr(envPtr)).address()
    
    fun waterui_read_computed_resolved_font(computedPtr: Long): ResolvedFontStruct {
        val font = WaterUILib.waterui_read_computed_resolved_font(ptr(computedPtr))
        return ResolvedFontStruct(
            size = font.size(),
            weight = font.weight()
        )
    }
    
    fun waterui_drop_computed_resolved_font(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_resolved_font(ptr(computedPtr))
    }

    // ========== Color (JavaCPP) ==========
    
    fun waterui_drop_color(colorPtr: Long) {
        WaterUILib.waterui_drop_color(ptr(colorPtr))
    }
    
    fun waterui_resolve_color(colorPtr: Long, envPtr: Long): Long =
        WaterUILib.waterui_resolve_color(ptr(colorPtr), ptr(envPtr)).address()
    
    fun waterui_read_computed_resolved_color(computedPtr: Long): ResolvedColorStruct {
        val color = WaterUILib.waterui_read_computed_resolved_color(ptr(computedPtr))
        return ResolvedColorStruct(
            red = color.red(),
            green = color.green(),
            blue = color.blue(),
            opacity = color.opacity()
        )
    }
    
    external fun waterui_watch_computed_resolved_color(computedPtr: Long, watcher: WatcherStruct): Long
    
    fun waterui_drop_computed_resolved_color(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_resolved_color(ptr(computedPtr))
    }

    // ========== Layout/Action/Dynamic (JavaCPP) ==========
    
    fun waterui_drop_layout(layoutPtr: Long) {
        WaterUILib.waterui_drop_layout(ptr(layoutPtr))
    }

    fun waterui_drop_action(actionPtr: Long) {
        WaterUILib.waterui_drop_action(ptr(actionPtr))
    }
    
    fun waterui_call_action(actionPtr: Long, envPtr: Long) {
        WaterUILib.waterui_call_action(ptr(actionPtr), ptr(envPtr))
    }
    
    fun waterui_drop_dynamic(dynamicPtr: Long) {
        WaterUILib.waterui_drop_dynamic(ptr(dynamicPtr))
    }

    // ========== Force-as converters (JNI for complex structs) ==========
    
    external fun waterui_force_as_button(anyViewPtr: Long): ButtonStruct
    external fun waterui_force_as_text(anyViewPtr: Long): TextStruct
    external fun waterui_force_as_plain(anyViewPtr: Long): PlainStruct
    external fun waterui_force_as_color(anyViewPtr: Long): ColorStruct
    external fun waterui_force_as_text_field(anyViewPtr: Long): TextFieldStruct
    external fun waterui_force_as_toggle(anyViewPtr: Long): ToggleStruct
    external fun waterui_force_as_slider(anyViewPtr: Long): SliderStruct
    external fun waterui_force_as_stepper(anyViewPtr: Long): StepperStruct
    external fun waterui_force_as_progress(anyViewPtr: Long): ProgressStruct
    external fun waterui_force_as_scroll(anyViewPtr: Long): ScrollStruct
    external fun waterui_force_as_dynamic(anyViewPtr: Long): DynamicStruct
    external fun waterui_force_as_renderer_view(anyViewPtr: Long): Long
    external fun waterui_force_as_picker(anyViewPtr: Long): PickerStruct

    // ========== Renderer View (Hybrid) ==========
    
    fun waterui_renderer_view_width(handle: Long): Float =
        WaterUILib.waterui_renderer_view_width(ptr(handle))
    
    fun waterui_renderer_view_height(handle: Long): Float =
        WaterUILib.waterui_renderer_view_height(ptr(handle))
    
    fun waterui_renderer_view_preferred_format(handle: Long): Int =
        WaterUILib.waterui_renderer_view_preferred_format(ptr(handle))
    
    // CPU rendering needs JNI for byte array handling
    external fun waterui_renderer_view_render_cpu(
        handle: Long,
        pixels: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        format: Int,
    ): Boolean
    
    fun waterui_drop_renderer_view(handle: Long) {
        WaterUILib.waterui_drop_renderer_view(ptr(handle))
    }

    // ========== Reactive Theme Signals (JNI for callbacks) ==========
    
    external fun waterui_create_reactive_color_state(argb: Int): Long
    external fun waterui_reactive_color_state_to_computed(statePtr: Long): Long
    external fun waterui_reactive_color_state_set(statePtr: Long, argb: Int)
    external fun waterui_create_reactive_font_state(size: Float, weight: Int): Long
    external fun waterui_reactive_font_state_to_computed(statePtr: Long): Long
    external fun waterui_reactive_font_state_set(statePtr: Long, size: Float, weight: Int)

    // ========== Helper functions ==========
    
    private fun ptr(address: Long): Pointer = PointerHelper.fromAddress(address)
    
    private fun wuiStrToString(str: WaterUILib.WuiStr): String {
        // For now, use JNI helper for string conversion
        return jniWuiStrToString(str.address())
    }
    
    // JNI helper for WuiStr conversion
    private external fun jniWuiStrToString(strPtr: Long): String
}

fun bootstrapWaterUiRuntime() {
    NativeBindings.bootstrapNativeBindings()
}

fun configureHotReloadEndpoint(host: String, port: Int) {
    NativeBindings.waterui_configure_hot_reload_endpoint(host, port)
}

fun configureHotReloadDirectory(path: String) {
    NativeBindings.waterui_configure_hot_reload_directory(path)
}

// --- Native struct mirrors (data-only skeletons) ---

const val RENDERER_BUFFER_FORMAT_RGBA8888: Int = 0

data class LayoutContainerStruct(
    val layoutPtr: Long,
    val childrenPtr: Long
)

data class FixedContainerStruct(
    val layoutPtr: Long,
    val childPointers: LongArray
)

data class ProposalStruct(
    val width: Float,
    val height: Float
)

data class SizeStruct(
    val width: Float,
    val height: Float
)

data class RectStruct(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class ChildMetadataStruct(
    val proposal: ProposalStruct,
    val priority: Int,
    val stretch: Boolean
) {
    fun isStretch(): Boolean = stretch
}

/**
 * Common watcher envelope for bindings/computed values.
 */
data class WatcherStruct(
    val dataPtr: Long,
    val callPtr: Long,
    val dropPtr: Long
)

data class ButtonStruct(
    val labelPtr: Long,
    val actionPtr: Long
)

data class TextStruct(
    val contentPtr: Long
)

data class PlainStruct(
    val textBytes: ByteArray
)

data class ColorStruct(
    val colorPtr: Long
)

data class StyledStrStruct(
    val chunks: Array<StyledChunkStruct>
)

data class StyledChunkStruct(
    val text: String,
    val style: TextStyleStruct
)

data class TextStyleStruct(
    val fontPtr: Long,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
    val foregroundPtr: Long,
    val backgroundPtr: Long
)

data class TextFieldStruct(
    val labelPtr: Long,
    val valuePtr: Long,
    val promptPtr: Long,
    val keyboardType: Int
)

data class ToggleStruct(
    val labelPtr: Long,
    val bindingPtr: Long
)

data class SliderStruct(
    val labelPtr: Long,
    val minLabelPtr: Long,
    val maxLabelPtr: Long,
    val rangeStart: Double,
    val rangeEnd: Double,
    val bindingPtr: Long
)

data class StepperStruct(
    val bindingPtr: Long,
    val stepPtr: Long,
    val labelPtr: Long,
    val rangeStart: Int,
    val rangeEnd: Int
)

data class ProgressStruct(
    val labelPtr: Long,
    val valueLabelPtr: Long,
    val valuePtr: Long,
    val style: Int
)

data class ScrollStruct(
    val axis: Int,
    val contentPtr: Long
)

data class DynamicStruct(
    val dynamicPtr: Long
)

data class PickerStruct(
    val itemsPtr: Long,
    val selectionPtr: Long
)

data class PickerItemStruct(
    val tag: Int,
    val label: StyledStrStruct
)

data class ResolvedColorStruct(
    val red: Float,
    val green: Float,
    val blue: Float,
    val opacity: Float
)

data class ResolvedFontStruct(
    val size: Float,
    val weight: Int
)
