package dev.waterui.android.runtime

import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.reactive.WatcherCallback

/**
 * Centralised access to native WaterUI FFI functions.
 * 
 * This module provides a thin wrapper over WatcherJni which handles all
 * native library loading and FFI calls via JNI with dlopen/dlsym.
 */
internal object NativeBindings {

    /**
     * Bootstrap the native library. Must be called before any other functions.
     * This loads libwaterui_app.so via dlopen and resolves all symbols.
     */
    fun bootstrapNativeBindings() {
        // Initialize WatcherJni - this loads libwaterui_app.so via dlopen
        WatcherJni
    }

    // ========== Core Functions ==========
    
    fun waterui_init(): Long = WatcherJni.init()
    fun waterui_main(): Long = WatcherJni.main()
    fun waterui_view_id(anyViewPtr: Long): String = WatcherJni.viewId(anyViewPtr)
    fun waterui_view_body(anyViewPtr: Long, envPtr: Long): Long = WatcherJni.viewBody(anyViewPtr, envPtr)
    fun waterui_configure_hot_reload_endpoint(host: String, port: Int) = WatcherJni.configureHotReloadEndpoint(host, port)
    fun waterui_configure_hot_reload_directory(path: String) = WatcherJni.configureHotReloadDirectory(path)

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
    
    fun waterui_computed_color_scheme_constant(scheme: Int): Long = WatcherJni.computedColorSchemeConstant(scheme)
    fun waterui_read_computed_color_scheme(ptr: Long): Int = WatcherJni.readComputedColorScheme(ptr)
    fun waterui_drop_computed_color_scheme(ptr: Long) = WatcherJni.dropComputedColorScheme(ptr)
    fun waterui_theme_install_color_scheme(envPtr: Long, signalPtr: Long) = WatcherJni.themeInstallColorScheme(envPtr, signalPtr)
    fun waterui_theme_color_scheme(envPtr: Long): Long = WatcherJni.themeColorScheme(envPtr)
    fun waterui_watch_computed_color_scheme(computedPtr: Long, watcher: WatcherStruct): Long =
        WatcherJni.watchComputedColorScheme(computedPtr, watcher)

    // ========== Theme: Slot-based Color API ==========
    
    fun waterui_theme_install_color(envPtr: Long, slot: Int, signalPtr: Long) = WatcherJni.themeInstallColor(envPtr, slot, signalPtr)
    fun waterui_theme_color(envPtr: Long, slot: Int): Long = WatcherJni.themeColor(envPtr, slot)

    // ========== Theme: Slot-based Font API ==========
    
    fun waterui_theme_install_font(envPtr: Long, slot: Int, signalPtr: Long) = WatcherJni.themeInstallFont(envPtr, slot, signalPtr)
    fun waterui_theme_font(envPtr: Long, slot: Int): Long = WatcherJni.themeFont(envPtr, slot)

    // ========== Theme: Legacy per-token APIs ==========
    
    fun waterui_theme_color_background(envPtr: Long): Long = WatcherJni.themeColorBackground(envPtr)
    fun waterui_theme_color_surface(envPtr: Long): Long = WatcherJni.themeColorSurface(envPtr)
    fun waterui_theme_color_surface_variant(envPtr: Long): Long = WatcherJni.themeColorSurfaceVariant(envPtr)
    fun waterui_theme_color_border(envPtr: Long): Long = WatcherJni.themeColorBorder(envPtr)
    fun waterui_theme_color_foreground(envPtr: Long): Long = WatcherJni.themeColorForeground(envPtr)
    fun waterui_theme_color_muted_foreground(envPtr: Long): Long = WatcherJni.themeColorMutedForeground(envPtr)
    fun waterui_theme_color_accent(envPtr: Long): Long = WatcherJni.themeColorAccent(envPtr)
    fun waterui_theme_color_accent_foreground(envPtr: Long): Long = WatcherJni.themeColorAccentForeground(envPtr)
    fun waterui_theme_font_body(envPtr: Long): Long = WatcherJni.themeFontBody(envPtr)
    fun waterui_theme_font_title(envPtr: Long): Long = WatcherJni.themeFontTitle(envPtr)
    fun waterui_theme_font_headline(envPtr: Long): Long = WatcherJni.themeFontHeadline(envPtr)
    fun waterui_theme_font_subheadline(envPtr: Long): Long = WatcherJni.themeFontSubheadline(envPtr)
    fun waterui_theme_font_caption(envPtr: Long): Long = WatcherJni.themeFontCaption(envPtr)
    fun waterui_theme_font_footnote(envPtr: Long): Long = WatcherJni.themeFontFootnote(envPtr)

    // ========== Environment ==========
    
    fun waterui_clone_env(envPtr: Long): Long = WatcherJni.cloneEnv(envPtr)
    fun waterui_env_drop(envPtr: Long) = WatcherJni.dropEnv(envPtr)
    fun waterui_drop_anyview(viewPtr: Long) = WatcherJni.dropAnyview(viewPtr)

    // ========== Layout bridging ==========
    
    fun waterui_force_as_layout_container(viewPtr: Long): LayoutContainerStruct = WatcherJni.forceAsLayoutContainer(viewPtr)
    fun waterui_force_as_fixed_container(viewPtr: Long): FixedContainerStruct = WatcherJni.forceAsFixedContainer(viewPtr)
    fun waterui_layout_propose(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>, context: LayoutContextStruct = LayoutContextStruct.EMPTY): Array<ProposalStruct> =
        WatcherJni.layoutPropose(layoutPtr, parent, children, context)
    fun waterui_layout_size(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>, context: LayoutContextStruct = LayoutContextStruct.EMPTY): SizeStruct =
        WatcherJni.layoutSize(layoutPtr, parent, children, context)
    fun waterui_layout_place(layoutPtr: Long, bounds: RectStruct, parent: ProposalStruct, children: Array<ChildMetadataStruct>, context: LayoutContextStruct = LayoutContextStruct.EMPTY): Array<ChildPlacementStruct> =
        WatcherJni.layoutPlace(layoutPtr, bounds, parent, children, context)
    fun waterui_drop_layout(layoutPtr: Long) = WatcherJni.dropLayout(layoutPtr)

    // ========== AnyViews ==========
    
    fun waterui_any_views_len(handle: Long): Int = WatcherJni.anyViewsLen(handle)
    fun waterui_any_views_get_view(handle: Long, index: Int): Long = WatcherJni.anyViewsGetView(handle, index)
    fun waterui_any_views_get_id(handle: Long, index: Int): Int = WatcherJni.anyViewsGetId(handle, index)
    fun waterui_drop_any_views(handle: Long) = WatcherJni.dropAnyViews(handle)

    // ========== Watcher creation ==========
    
    fun waterui_create_bool_watcher(callback: WatcherCallback<Boolean>): WatcherStruct = WatcherJni.createBoolWatcher(callback)
    fun waterui_create_int_watcher(callback: WatcherCallback<Int>): WatcherStruct = WatcherJni.createIntWatcher(callback)
    fun waterui_create_double_watcher(callback: WatcherCallback<Double>): WatcherStruct = WatcherJni.createDoubleWatcher(callback)
    fun waterui_create_string_watcher(callback: WatcherCallback<String>): WatcherStruct = WatcherJni.createStringWatcher(callback)
    fun waterui_create_any_view_watcher(callback: WatcherCallback<Long>): WatcherStruct = WatcherJni.createAnyViewWatcher(callback)
    fun waterui_create_styled_str_watcher(callback: WatcherCallback<StyledStrStruct>): WatcherStruct = WatcherJni.createStyledStrWatcher(callback)
    fun waterui_create_resolved_color_watcher(callback: WatcherCallback<ResolvedColorStruct>): WatcherStruct = WatcherJni.createResolvedColorWatcher(callback)
    fun waterui_create_resolved_font_watcher(callback: WatcherCallback<ResolvedFontStruct>): WatcherStruct = WatcherJni.createResolvedFontWatcher(callback)
    fun waterui_create_picker_items_watcher(callback: WatcherCallback<Array<PickerItemStruct>>): WatcherStruct = WatcherJni.createPickerItemsWatcher(callback)

    // ========== Watch binding ==========
    
    fun waterui_watch_binding_bool(bindingPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchBindingBool(bindingPtr, watcher)
    fun waterui_watch_binding_int(bindingPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchBindingInt(bindingPtr, watcher)
    fun waterui_watch_binding_double(bindingPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchBindingDouble(bindingPtr, watcher)
    fun waterui_watch_binding_str(bindingPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchBindingStr(bindingPtr, watcher)

    // ========== Binding setters/getters ==========
    
    fun waterui_set_binding_bool(bindingPtr: Long, value: Boolean) = WatcherJni.setBindingBool(bindingPtr, value)
    fun waterui_set_binding_int(bindingPtr: Long, value: Int) = WatcherJni.setBindingInt(bindingPtr, value)
    fun waterui_set_binding_double(bindingPtr: Long, value: Double) = WatcherJni.setBindingDouble(bindingPtr, value)
    fun waterui_set_binding_str(bindingPtr: Long, bytes: ByteArray) = WatcherJni.setBindingStr(bindingPtr, bytes)
    fun waterui_read_binding_bool(bindingPtr: Long): Boolean = WatcherJni.readBindingBool(bindingPtr)
    fun waterui_read_binding_int(bindingPtr: Long): Int = WatcherJni.readBindingInt(bindingPtr)
    fun waterui_read_binding_double(bindingPtr: Long): Double = WatcherJni.readBindingDouble(bindingPtr)
    fun waterui_read_binding_str(bindingPtr: Long): ByteArray = WatcherJni.readBindingStr(bindingPtr)
    fun waterui_drop_binding_bool(bindingPtr: Long) = WatcherJni.dropBindingBool(bindingPtr)
    fun waterui_drop_binding_int(bindingPtr: Long) = WatcherJni.dropBindingInt(bindingPtr)
    fun waterui_drop_binding_double(bindingPtr: Long) = WatcherJni.dropBindingDouble(bindingPtr)
    fun waterui_drop_binding_str(bindingPtr: Long) = WatcherJni.dropBindingStr(bindingPtr)

    // ========== Computed accessors ==========
    
    fun waterui_read_computed_f64(computedPtr: Long): Double = WatcherJni.readComputedF64(computedPtr)
    fun waterui_drop_computed_f64(computedPtr: Long) = WatcherJni.dropComputedF64(computedPtr)
    fun waterui_watch_computed_f64(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedF64(computedPtr, watcher)
    fun waterui_read_computed_i32(computedPtr: Long): Int = WatcherJni.readComputedI32(computedPtr)
    fun waterui_drop_computed_i32(computedPtr: Long) = WatcherJni.dropComputedI32(computedPtr)
    fun waterui_watch_computed_i32(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedI32(computedPtr, watcher)
    fun waterui_read_computed_styled_str(computedPtr: Long): StyledStrStruct = WatcherJni.readComputedStyledStr(computedPtr)
    fun waterui_drop_computed_styled_str(computedPtr: Long) = WatcherJni.dropComputedStyledStr(computedPtr)
    fun waterui_watch_computed_styled_str(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedStyledStr(computedPtr, watcher)
    fun waterui_read_computed_resolved_font(computedPtr: Long): ResolvedFontStruct = WatcherJni.readComputedResolvedFont(computedPtr)
    fun waterui_drop_computed_resolved_font(computedPtr: Long) = WatcherJni.dropComputedResolvedFont(computedPtr)
    fun waterui_watch_computed_resolved_font(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedResolvedFont(computedPtr, watcher)
    fun waterui_read_computed_picker_items(computedPtr: Long): Array<PickerItemStruct> = WatcherJni.readComputedPickerItems(computedPtr)
    fun waterui_drop_computed_picker_items(computedPtr: Long) = WatcherJni.dropComputedPickerItems(computedPtr)
    fun waterui_watch_computed_picker_items(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedPickerItems(computedPtr, watcher)

    // ========== Color resolution ==========
    
    fun waterui_resolve_color(colorPtr: Long, envPtr: Long): Long = WatcherJni.resolveColor(colorPtr, envPtr)
    fun waterui_drop_color(colorPtr: Long) = WatcherJni.dropColor(colorPtr)
    fun waterui_read_computed_resolved_color(computedPtr: Long): ResolvedColorStruct = WatcherJni.readComputedResolvedColor(computedPtr)
    fun waterui_drop_computed_resolved_color(computedPtr: Long) = WatcherJni.dropComputedResolvedColor(computedPtr)
    fun waterui_watch_computed_resolved_color(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedResolvedColor(computedPtr, watcher)

    // ========== Font resolution ==========
    
    fun waterui_resolve_font(fontPtr: Long, envPtr: Long): Long = WatcherJni.resolveFont(fontPtr, envPtr)
    fun waterui_drop_font(fontPtr: Long) = WatcherJni.dropFont(fontPtr)

    // ========== Action ==========
    
    fun waterui_call_action(actionPtr: Long, envPtr: Long) = WatcherJni.callAction(actionPtr, envPtr)
    fun waterui_drop_action(actionPtr: Long) = WatcherJni.dropAction(actionPtr)

    // ========== Watcher guard ==========
    
    fun waterui_drop_watcher_guard(guardPtr: Long) = WatcherJni.dropWatcherGuard(guardPtr)
    fun waterui_get_animation(metadataPtr: Long): Int = WatcherJni.getAnimation(metadataPtr)

    // ========== Dynamic ==========
    
    fun waterui_force_as_dynamic(viewPtr: Long): DynamicStruct = DynamicStruct(WatcherJni.forceAsDynamic(viewPtr))
    fun waterui_drop_dynamic(dynamicPtr: Long) = WatcherJni.dropDynamic(dynamicPtr)
    fun waterui_dynamic_connect(dynamicPtr: Long, watcher: WatcherStruct) = WatcherJni.dynamicConnect(dynamicPtr, watcher)

    // ========== Force-as functions ==========
    
    fun waterui_force_as_plain(viewPtr: Long): PlainStruct = WatcherJni.forceAsPlain(viewPtr)
    fun waterui_force_as_text(viewPtr: Long): TextStruct = TextStruct(WatcherJni.forceAsText(viewPtr))
    fun waterui_force_as_button(viewPtr: Long): ButtonStruct = WatcherJni.forceAsButton(viewPtr)
    fun waterui_force_as_color(viewPtr: Long): ColorStruct = ColorStruct(WatcherJni.forceAsColor(viewPtr))
    fun waterui_force_as_text_field(viewPtr: Long): TextFieldStruct = WatcherJni.forceAsTextField(viewPtr)
    fun waterui_force_as_toggle(viewPtr: Long): ToggleStruct = WatcherJni.forceAsToggle(viewPtr)
    fun waterui_force_as_slider(viewPtr: Long): SliderStruct = WatcherJni.forceAsSlider(viewPtr)
    fun waterui_force_as_stepper(viewPtr: Long): StepperStruct = WatcherJni.forceAsStepper(viewPtr)
    fun waterui_force_as_progress(viewPtr: Long): ProgressStruct = WatcherJni.forceAsProgress(viewPtr)
    fun waterui_force_as_scroll(viewPtr: Long): ScrollStruct = WatcherJni.forceAsScrollView(viewPtr)
    fun waterui_force_as_picker(viewPtr: Long): PickerStruct = WatcherJni.forceAsPicker(viewPtr)

    // ========== Renderer View ==========
    
    fun waterui_force_as_renderer_view(viewPtr: Long): Long = WatcherJni.forceAsRendererView(viewPtr)
    fun waterui_renderer_view_width(handle: Long): Float = WatcherJni.rendererViewWidth(handle)
    fun waterui_renderer_view_height(handle: Long): Float = WatcherJni.rendererViewHeight(handle)
    fun waterui_renderer_view_preferred_format(handle: Long): Int = WatcherJni.rendererViewPreferredFormat(handle)
    fun waterui_renderer_view_render_cpu(handle: Long, pixels: ByteArray, width: Int, height: Int, stride: Int, format: Int): Boolean =
        WatcherJni.rendererViewRenderCpu(handle, pixels, width, height, stride, format)
    fun waterui_drop_renderer_view(handle: Long) = WatcherJni.dropRendererView(handle)

    // ========== Reactive State Creation (for theme) ==========
    
    fun waterui_create_reactive_color_state(argb: Int): Long = WatcherJni.createReactiveColorState(argb)
    fun waterui_reactive_color_state_to_computed(statePtr: Long): Long = WatcherJni.reactiveColorStateToComputed(statePtr)
    fun waterui_reactive_color_state_set(statePtr: Long, argb: Int) = WatcherJni.reactiveColorStateSet(statePtr, argb)
    fun waterui_create_reactive_font_state(size: Float, weight: Int): Long = WatcherJni.createReactiveFontState(size, weight)
    fun waterui_reactive_font_state_to_computed(statePtr: Long): Long = WatcherJni.reactiveFontStateToComputed(statePtr)
    fun waterui_reactive_font_state_set(statePtr: Long, size: Float, weight: Int) = WatcherJni.reactiveFontStateSet(statePtr, size, weight)
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
