package dev.waterui.android.runtime

import dev.waterui.android.ffi.PointerHelper
import dev.waterui.android.ffi.WaterUILib
import dev.waterui.android.ffi.WaterUILib.*
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.reactive.WatcherCallback
import dev.waterui.android.reactive.WuiWatcherMetadata
import org.bytedeco.javacpp.Pointer

/**
 * Centralised access to native WaterUI FFI functions.
 * 
 * This module provides a thin wrapper over:
 * - WaterUILib (JavaCPP-generated bindings) for most FFI calls
 * - WatcherJni (manual JNI) for callback-based watcher functions
 * 
 * The native library is loaded via JavaCPP's Loader when WaterUILib
 * is first accessed.
 */
internal object NativeBindings {

    /**
     * Bootstrap the native library. Must be called before any other functions.
     * This triggers JavaCPP's Loader to load libwaterui_app.so and initializes
     * the watcher JNI symbols.
     */
    fun bootstrapNativeBindings() {
        // Access WaterUILib to trigger static initializer which loads the native library
        WaterUILib::class.java
        // Initialize WatcherJni (triggers its static initializer)
        WatcherJni
    }

    // Helper to convert Long to Pointer
    private fun ptr(address: Long): Pointer? = PointerHelper.fromAddress(address)

    // Helper to convert WuiStr to String - delegated to JNI
    // JavaCPP can't properly handle WuiStr's vtable structure

    // ========== Core Functions ==========
    
    fun waterui_init(): Long = WaterUILib.waterui_init().address()
    
    fun waterui_main(): Long = WaterUILib.waterui_main().address()
    
    fun waterui_view_id(anyViewPtr: Long): String =
        WatcherJni.viewId(anyViewPtr)
    
    fun waterui_view_body(anyViewPtr: Long, envPtr: Long): Long =
        WaterUILib.waterui_view_body(ptr(anyViewPtr), ptr(envPtr)).address()
    
    fun waterui_configure_hot_reload_endpoint(host: String, port: Int) {
        WaterUILib.waterui_configure_hot_reload_endpoint(host, port.toShort())
    }
    
    fun waterui_configure_hot_reload_directory(path: String) {
        WaterUILib.waterui_configure_hot_reload_directory(path)
    }

    // ========== Type Identifiers ==========
    
    fun waterui_empty_id(): String = WatcherJni.emptyId()
    fun waterui_text_id(): String = WatcherJni.textId()
    fun waterui_plain_id(): String = WatcherJni.plainId()
    fun waterui_button_id(): String = WatcherJni.buttonId()
    fun waterui_color_id(): String = WatcherJni.colorId()
    fun waterui_text_field_id(): String = WatcherJni.textFieldId()
    fun waterui_stepper_id(): String = WatcherJni.stepperId()
    fun waterui_progress_id(): String = WatcherJni.progressId()
    fun waterui_dynamic_id(): String = WatcherJni.dynamicId()
    fun waterui_scroll_view_id(): String = WatcherJni.scrollViewId()
    fun waterui_spacer_id(): String = WatcherJni.spacerId()
    fun waterui_toggle_id(): String = WatcherJni.toggleId()
    fun waterui_slider_id(): String = WatcherJni.sliderId()
    fun waterui_renderer_view_id(): String = WatcherJni.rendererViewId()
    fun waterui_fixed_container_id(): String = WatcherJni.fixedContainerId()
    fun waterui_picker_id(): String = WatcherJni.pickerId()
    fun waterui_layout_container_id(): String = WatcherJni.layoutContainerId()

    // ========== Theme: Color Scheme ==========
    
    fun waterui_computed_color_scheme_constant(scheme: Int): Long =
        WaterUILib.waterui_computed_color_scheme_constant(scheme).address()
    
    fun waterui_read_computed_color_scheme(ptr: Long): Int =
        WaterUILib.waterui_read_computed_color_scheme(ptr(ptr))
    
    fun waterui_drop_computed_color_scheme(ptr: Long) {
        WaterUILib.waterui_drop_computed_color_scheme(ptr(ptr))
    }
    
    fun waterui_theme_install_color_scheme(envPtr: Long, signalPtr: Long) {
        WaterUILib.waterui_theme_install_color_scheme(ptr(envPtr), ptr(signalPtr))
    }
    
    fun waterui_theme_color_scheme(envPtr: Long): Long =
        WaterUILib.waterui_theme_color_scheme(ptr(envPtr)).address()
    
    fun waterui_watch_computed_color_scheme(computedPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchComputedColorScheme(computedPtr, watcher)

    // ========== Theme: Slot-based Color API ==========
    
    fun waterui_theme_install_color(envPtr: Long, slot: Int, signalPtr: Long) {
        WaterUILib.waterui_theme_install_color(ptr(envPtr), slot, ptr(signalPtr))
    }
    
    fun waterui_theme_color(envPtr: Long, slot: Int): Long =
        WaterUILib.waterui_theme_color(ptr(envPtr), slot).address()

    // ========== Theme: Slot-based Font API ==========
    
    fun waterui_theme_install_font(envPtr: Long, slot: Int, signalPtr: Long) {
        WaterUILib.waterui_theme_install_font(ptr(envPtr), slot, ptr(signalPtr))
    }
    
    fun waterui_theme_font(envPtr: Long, slot: Int): Long =
        WaterUILib.waterui_theme_font(ptr(envPtr), slot).address()

    // ========== Theme: Legacy per-token APIs ==========
    
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

    // ========== Environment ==========
    
    fun waterui_clone_env(envPtr: Long): Long =
        WaterUILib.waterui_clone_env(ptr(envPtr)).address()
    
    fun waterui_env_drop(envPtr: Long) {
        WaterUILib.waterui_drop_env(ptr(envPtr))
    }

    fun waterui_drop_anyview(viewPtr: Long) {
        WaterUILib.waterui_drop_anyview(ptr(viewPtr))
    }

    // ========== Layout bridging ==========
    
    fun waterui_force_as_layout_container(viewPtr: Long): LayoutContainerStruct {
        val container = WaterUILib.waterui_force_as_layout_container(ptr(viewPtr))
        return LayoutContainerStruct(
            layoutPtr = container.layout().address(),
            childrenPtr = container.contents().address()
        )
    }
    
    fun waterui_force_as_fixed_container(viewPtr: Long): FixedContainerStruct {
        val container = WaterUILib.waterui_force_as_fixed_container(ptr(viewPtr))
        val contentsPtr = container.contents()
        val len = WaterUILib.waterui_anyviews_len(contentsPtr).toInt()
        val childPointers = LongArray(len) { i ->
            WaterUILib.waterui_anyviews_get_view(contentsPtr, i.toLong()).address()
        }
        return FixedContainerStruct(
            layoutPtr = container.layout().address(),
            childPointers = childPointers
        )
    }
    
    fun waterui_layout_propose(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>): Array<ProposalStruct> =
        WatcherJni.layoutPropose(layoutPtr, parent, children)
    
    fun waterui_layout_size(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>): SizeStruct =
        WatcherJni.layoutSize(layoutPtr, parent, children)
    
    fun waterui_layout_place(layoutPtr: Long, bounds: RectStruct, parent: ProposalStruct, children: Array<ChildMetadataStruct>): Array<RectStruct> =
        WatcherJni.layoutPlace(layoutPtr, bounds, parent, children)
    
    fun waterui_drop_layout(layoutPtr: Long) {
        WaterUILib.waterui_drop_layout(ptr(layoutPtr))
    }

    // ========== AnyViews ==========
    
    fun waterui_any_views_len(handle: Long): Int =
        WaterUILib.waterui_anyviews_len(ptr(handle)).toInt()
    
    fun waterui_any_views_get_view(handle: Long, index: Int): Long =
        WaterUILib.waterui_anyviews_get_view(ptr(handle), index.toLong()).address()
    
    fun waterui_any_views_get_id(handle: Long, index: Int): Int =
        WaterUILib.waterui_anyviews_get_id(ptr(handle), index.toLong()).inner()
    
    fun waterui_drop_any_views(handle: Long) {
        WaterUILib.waterui_drop_anyviews(ptr(handle))
    }

    // ========== Watcher creation - delegated to WatcherJni ==========
    
    fun waterui_create_bool_watcher(callback: WatcherCallback<Boolean>): WatcherStruct =
        WatcherJni.createBoolWatcher(callback)
    
    fun waterui_create_int_watcher(callback: WatcherCallback<Int>): WatcherStruct =
        WatcherJni.createIntWatcher(callback)
    
    fun waterui_create_double_watcher(callback: WatcherCallback<Double>): WatcherStruct =
        WatcherJni.createDoubleWatcher(callback)
    
    fun waterui_create_string_watcher(callback: WatcherCallback<String>): WatcherStruct =
        WatcherJni.createStringWatcher(callback)
    
    fun waterui_create_any_view_watcher(callback: WatcherCallback<Long>): WatcherStruct =
        WatcherJni.createAnyViewWatcher(callback)
    
    fun waterui_create_styled_str_watcher(callback: WatcherCallback<StyledStrStruct>): WatcherStruct =
        WatcherJni.createStyledStrWatcher(callback)
    
    fun waterui_create_resolved_color_watcher(callback: WatcherCallback<ResolvedColorStruct>): WatcherStruct =
        WatcherJni.createResolvedColorWatcher(callback)
    
    fun waterui_create_resolved_font_watcher(callback: WatcherCallback<ResolvedFontStruct>): WatcherStruct =
        WatcherJni.createResolvedFontWatcher(callback)
    
    fun waterui_create_picker_items_watcher(callback: WatcherCallback<Array<PickerItemStruct>>): WatcherStruct =
        WatcherJni.createPickerItemsWatcher(callback)

    // ========== Watch binding - delegated to WatcherJni ==========
    
    fun waterui_watch_binding_bool(bindingPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchBindingBool(bindingPtr, watcher)
    
    fun waterui_watch_binding_int(bindingPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchBindingInt(bindingPtr, watcher)
    
    fun waterui_watch_binding_double(bindingPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchBindingDouble(bindingPtr, watcher)
    
    fun waterui_watch_binding_str(bindingPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchBindingStr(bindingPtr, watcher)

    // ========== Binding setters/getters ==========
    
    fun waterui_set_binding_bool(bindingPtr: Long, value: Boolean) {
        WaterUILib.waterui_set_binding_bool(ptr(bindingPtr), value)
    }
    
    fun waterui_set_binding_int(bindingPtr: Long, value: Int) {
        WaterUILib.waterui_set_binding_i32(ptr(bindingPtr), value)
    }
    
    fun waterui_set_binding_double(bindingPtr: Long, value: Double) {
        WaterUILib.waterui_set_binding_f64(ptr(bindingPtr), value)
    }
    
    fun waterui_set_binding_str(bindingPtr: Long, bytes: ByteArray) {
        WatcherJni.setBindingStr(bindingPtr, bytes)
    }

    fun waterui_read_binding_bool(bindingPtr: Long): Boolean =
        WaterUILib.waterui_read_binding_bool(ptr(bindingPtr))
    
    fun waterui_read_binding_int(bindingPtr: Long): Int =
        WaterUILib.waterui_read_binding_i32(ptr(bindingPtr))
    
    fun waterui_read_binding_double(bindingPtr: Long): Double =
        WaterUILib.waterui_read_binding_f64(ptr(bindingPtr))
    
    fun waterui_read_binding_str(bindingPtr: Long): ByteArray =
        WatcherJni.readBindingStr(bindingPtr)
    
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

    // ========== Computed accessors ==========
    
    fun waterui_read_computed_f64(computedPtr: Long): Double =
        WaterUILib.waterui_read_computed_f64(ptr(computedPtr))
    
    fun waterui_drop_computed_f64(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_f64(ptr(computedPtr))
    }
    
    fun waterui_watch_computed_f64(computedPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchComputedF64(computedPtr, watcher)
    
    fun waterui_read_computed_i32(computedPtr: Long): Int =
        WaterUILib.waterui_read_computed_i32(ptr(computedPtr))
    
    fun waterui_drop_computed_i32(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_i32(ptr(computedPtr))
    }
    
    fun waterui_watch_computed_i32(computedPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchComputedI32(computedPtr, watcher)
    
    fun waterui_read_computed_styled_str(computedPtr: Long): StyledStrStruct =
        WatcherJni.readComputedStyledStr(computedPtr)
    
    fun waterui_drop_computed_styled_str(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_styled_str(ptr(computedPtr))
    }
    
    fun waterui_watch_computed_styled_str(computedPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchComputedStyledStr(computedPtr, watcher)
    
    fun waterui_read_computed_resolved_font(computedPtr: Long): ResolvedFontStruct {
        val font = WaterUILib.waterui_read_computed_resolved_font(ptr(computedPtr))
        return ResolvedFontStruct(font.size(), font.weight())
    }
    
    fun waterui_drop_computed_resolved_font(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_resolved_font(ptr(computedPtr))
    }
    
    fun waterui_watch_computed_resolved_font(computedPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchComputedResolvedFont(computedPtr, watcher)
    
    fun waterui_read_computed_picker_items(computedPtr: Long): Array<PickerItemStruct> =
        WatcherJni.readComputedPickerItems(computedPtr)
    
    fun waterui_drop_computed_picker_items(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_picker_items(ptr(computedPtr))
    }
    
    fun waterui_watch_computed_picker_items(computedPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchComputedPickerItems(computedPtr, watcher)

    // ========== Color resolution ==========
    
    fun waterui_resolve_color(colorPtr: Long, envPtr: Long): Long =
        WaterUILib.waterui_resolve_color(ptr(colorPtr), ptr(envPtr)).address()
    
    fun waterui_drop_color(colorPtr: Long) {
        WaterUILib.waterui_drop_color(ptr(colorPtr))
    }
    
    fun waterui_read_computed_resolved_color(computedPtr: Long): ResolvedColorStruct {
        val color = WaterUILib.waterui_read_computed_resolved_color(ptr(computedPtr))
        return ResolvedColorStruct(color.red(), color.green(), color.blue(), color.opacity())
    }
    
    fun waterui_drop_computed_resolved_color(computedPtr: Long) {
        WaterUILib.waterui_drop_computed_resolved_color(ptr(computedPtr))
    }
    
    fun waterui_watch_computed_resolved_color(computedPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchComputedResolvedColor(computedPtr, watcher)

    // ========== Font resolution ==========
    
    fun waterui_resolve_font(fontPtr: Long, envPtr: Long): Long =
        WaterUILib.waterui_resolve_font(ptr(fontPtr), ptr(envPtr)).address()
    
    fun waterui_drop_font(fontPtr: Long) {
        WaterUILib.waterui_drop_font(ptr(fontPtr))
    }

    // ========== Action ==========
    
    fun waterui_call_action(actionPtr: Long, envPtr: Long) {
        WaterUILib.waterui_call_action(ptr(actionPtr), ptr(envPtr))
    }
    
    fun waterui_drop_action(actionPtr: Long) {
        WaterUILib.waterui_drop_action(ptr(actionPtr))
    }

    // ========== Watcher guard ==========
    
    fun waterui_drop_watcher_guard(guardPtr: Long) {
        WaterUILib.waterui_drop_box_watcher_guard(ptr(guardPtr))
    }
    
    fun waterui_get_animation(metadataPtr: Long): Int =
        WaterUILib.waterui_get_animation(ptr(metadataPtr))

    // ========== Dynamic ==========
    
    fun waterui_force_as_dynamic(viewPtr: Long): DynamicStruct {
        val dynamic = WaterUILib.waterui_force_as_dynamic(ptr(viewPtr))
        return DynamicStruct(dynamic.address())
    }
    
    fun waterui_drop_dynamic(dynamicPtr: Long) {
        WaterUILib.waterui_drop_dynamic(ptr(dynamicPtr))
    }
    
    fun waterui_dynamic_connect(dynamicPtr: Long, watcher: WatcherStruct) {
        WatcherJni.dynamicConnect(dynamicPtr, watcher)
    }

    // ========== Force-as functions ==========
    
    fun waterui_force_as_plain(viewPtr: Long): PlainStruct =
        WatcherJni.forceAsPlain(viewPtr)
    
    fun waterui_force_as_text(viewPtr: Long): TextStruct {
        val text = WaterUILib.waterui_force_as_text(ptr(viewPtr))
        return TextStruct(text.content().address())
    }
    
    fun waterui_force_as_button(viewPtr: Long): ButtonStruct {
        val button = WaterUILib.waterui_force_as_button(ptr(viewPtr))
        return ButtonStruct(button.label().address(), button.action().address())
    }
    
    fun waterui_force_as_color(viewPtr: Long): ColorStruct {
        val color = WaterUILib.waterui_force_as_color(ptr(viewPtr))
        return ColorStruct(color.address())
    }
    
    fun waterui_force_as_text_field(viewPtr: Long): TextFieldStruct {
        val field = WaterUILib.waterui_force_as_text_field(ptr(viewPtr))
        return TextFieldStruct(
            labelPtr = field.label().address(),
            valuePtr = field.value().address(),
            promptPtr = field.prompt().content().address(),
            keyboardType = field.keyboard()
        )
    }
    
    fun waterui_force_as_toggle(viewPtr: Long): ToggleStruct {
        val toggle = WaterUILib.waterui_force_as_toggle(ptr(viewPtr))
        return ToggleStruct(toggle.label().address(), toggle.toggle().address())
    }
    
    fun waterui_force_as_slider(viewPtr: Long): SliderStruct {
        val slider = WaterUILib.waterui_force_as_slider(ptr(viewPtr))
        return SliderStruct(
            labelPtr = slider.label().address(),
            minLabelPtr = slider.min_value_label().address(),
            maxLabelPtr = slider.max_value_label().address(),
            rangeStart = slider.range().start(),
            rangeEnd = slider.range().end(),
            bindingPtr = slider.value().address()
        )
    }
    
    fun waterui_force_as_stepper(viewPtr: Long): StepperStruct {
        val stepper = WaterUILib.waterui_force_as_stepper(ptr(viewPtr))
        return StepperStruct(
            bindingPtr = stepper.value().address(),
            stepPtr = stepper.step().address(),
            labelPtr = stepper.label().address(),
            rangeStart = stepper.range().start(),
            rangeEnd = stepper.range().end()
        )
    }
    
    fun waterui_force_as_progress(viewPtr: Long): ProgressStruct {
        val progress = WaterUILib.waterui_force_as_progress(ptr(viewPtr))
        return ProgressStruct(
            labelPtr = progress.label().address(),
            valueLabelPtr = progress.value_label().address(),
            valuePtr = progress.value().address(),
            style = progress.style()
        )
    }
    
    fun waterui_force_as_scroll(viewPtr: Long): ScrollStruct {
        val scroll = WaterUILib.waterui_force_as_scroll_view(ptr(viewPtr))
        return ScrollStruct(scroll.axis(), scroll.content().address())
    }
    
    fun waterui_force_as_picker(viewPtr: Long): PickerStruct {
        val picker = WaterUILib.waterui_force_as_picker(ptr(viewPtr))
        return PickerStruct(picker.items().address(), picker.selection().address())
    }

    // ========== Renderer View ==========
    
    fun waterui_force_as_renderer_view(viewPtr: Long): Long =
        WaterUILib.waterui_force_as_renderer_view(ptr(viewPtr)).address()
    
    fun waterui_renderer_view_width(handle: Long): Float =
        WaterUILib.waterui_renderer_view_width(ptr(handle))
    
    fun waterui_renderer_view_height(handle: Long): Float =
        WaterUILib.waterui_renderer_view_height(ptr(handle))
    
    fun waterui_renderer_view_preferred_format(handle: Long): Int =
        WaterUILib.waterui_renderer_view_preferred_format(ptr(handle))
    
    fun waterui_renderer_view_render_cpu(handle: Long, pixels: ByteArray, width: Int, height: Int, stride: Int, format: Int): Boolean {
        val pixelPtr = org.bytedeco.javacpp.BytePointer(*pixels)
        val result = WaterUILib.waterui_renderer_view_render_cpu(
            ptr(handle), pixelPtr, width, height, stride.toLong(), format
        )
        pixelPtr.get(pixels)
        return result
    }
    
    fun waterui_drop_renderer_view(handle: Long) {
        WaterUILib.waterui_drop_renderer_view(ptr(handle))
    }

    // ========== Reactive State Creation (for theme) ==========
    
    fun waterui_create_reactive_color_state(argb: Int): Long =
        WatcherJni.createReactiveColorState(argb)
    
    fun waterui_reactive_color_state_to_computed(statePtr: Long): Long =
        WatcherJni.reactiveColorStateToComputed(statePtr)
    
    fun waterui_reactive_color_state_set(statePtr: Long, argb: Int) {
        WatcherJni.reactiveColorStateSet(statePtr, argb)
    }
    
    fun waterui_create_reactive_font_state(size: Float, weight: Int): Long =
        WatcherJni.createReactiveFontState(size, weight)
    
    fun waterui_reactive_font_state_to_computed(statePtr: Long): Long =
        WatcherJni.reactiveFontStateToComputed(statePtr)
    
    fun waterui_reactive_font_state_set(statePtr: Long, size: Float, weight: Int) {
        WatcherJni.reactiveFontStateSet(statePtr, size, weight)
    }
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


