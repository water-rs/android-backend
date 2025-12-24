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
    fun waterui_app(envPtr: Long): AppStruct = WatcherJni.app(envPtr)
    fun waterui_env_install_media_picker_manager(envPtr: Long) = WatcherJni.envInstallMediaPickerManager(envPtr)
    fun waterui_env_install_webview_controller(envPtr: Long) = WatcherJni.envInstallWebViewController(envPtr)
    fun waterui_view_id(anyViewPtr: Long): TypeIdStruct = WatcherJni.viewId(anyViewPtr)
    fun waterui_view_body(anyViewPtr: Long, envPtr: Long): Long = WatcherJni.viewBody(anyViewPtr, envPtr)
    fun waterui_view_stretch_axis(anyViewPtr: Long): Int = WatcherJni.viewStretchAxis(anyViewPtr)
    fun waterui_configure_hot_reload_endpoint(host: String, port: Int) = WatcherJni.configureHotReloadEndpoint(host, port)
    fun waterui_configure_hot_reload_directory(path: String) = WatcherJni.configureHotReloadDirectory(path)

    // ========== Type Identifiers ==========

    fun waterui_empty_id(): TypeIdStruct = WatcherJni.emptyId()
    fun waterui_text_id(): TypeIdStruct = WatcherJni.textId()
    fun waterui_plain_id(): TypeIdStruct = WatcherJni.plainId()
    fun waterui_button_id(): TypeIdStruct = WatcherJni.buttonId()
    fun waterui_color_id(): TypeIdStruct = WatcherJni.colorId()
    fun waterui_text_field_id(): TypeIdStruct = WatcherJni.textFieldId()
    fun waterui_stepper_id(): TypeIdStruct = WatcherJni.stepperId()
    fun waterui_date_picker_id(): TypeIdStruct = WatcherJni.datePickerId()
    fun waterui_color_picker_id(): TypeIdStruct = WatcherJni.colorPickerId()
    fun waterui_progress_id(): TypeIdStruct = WatcherJni.progressId()
    fun waterui_dynamic_id(): TypeIdStruct = WatcherJni.dynamicId()
    fun waterui_scroll_view_id(): TypeIdStruct = WatcherJni.scrollViewId()
    fun waterui_spacer_id(): TypeIdStruct = WatcherJni.spacerId()
    fun waterui_toggle_id(): TypeIdStruct = WatcherJni.toggleId()
    fun waterui_slider_id(): TypeIdStruct = WatcherJni.sliderId()
    fun waterui_fixed_container_id(): TypeIdStruct = WatcherJni.fixedContainerId()
    fun waterui_picker_id(): TypeIdStruct = WatcherJni.pickerId()
    fun waterui_secure_field_id(): TypeIdStruct = WatcherJni.secureFieldId()
    fun waterui_layout_container_id(): TypeIdStruct = WatcherJni.layoutContainerId()
    fun waterui_metadata_env_id(): TypeIdStruct = WatcherJni.metadataEnvId()

    // ========== Theme: Color Scheme ==========

    fun waterui_create_reactive_color_scheme_state(scheme: Int): Long =
        WatcherJni.createReactiveColorSchemeState(scheme)
    fun waterui_reactive_color_scheme_state_to_computed(statePtr: Long): Long =
        WatcherJni.reactiveColorSchemeStateToComputed(statePtr)
    fun waterui_reactive_color_scheme_state_set(statePtr: Long, scheme: Int) =
        WatcherJni.reactiveColorSchemeStateSet(statePtr, scheme)
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
    fun waterui_layout_size_that_fits(layoutPtr: Long, proposal: ProposalStruct, subviews: Array<SubViewStruct>): SizeStruct =
        WatcherJni.layoutSizeThatFits(layoutPtr, proposal, subviews)
    fun waterui_layout_place(layoutPtr: Long, bounds: RectStruct, subviews: Array<SubViewStruct>): Array<RectStruct> =
        WatcherJni.layoutPlace(layoutPtr, bounds, subviews)
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
    fun waterui_create_float_watcher(callback: WatcherCallback<Float>): WatcherStruct = WatcherJni.createFloatWatcher(callback)
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
    fun waterui_set_binding_secure(bindingPtr: Long, bytes: ByteArray) = WatcherJni.setBindingSecure(bindingPtr, bytes)
    fun waterui_set_binding_color(bindingPtr: Long, colorPtr: Long) = WatcherJni.setBindingColor(bindingPtr, colorPtr)
    fun waterui_read_binding_bool(bindingPtr: Long): Boolean = WatcherJni.readBindingBool(bindingPtr)
    fun waterui_read_binding_int(bindingPtr: Long): Int = WatcherJni.readBindingInt(bindingPtr)
    fun waterui_read_binding_double(bindingPtr: Long): Double = WatcherJni.readBindingDouble(bindingPtr)
    fun waterui_read_binding_str(bindingPtr: Long): ByteArray = WatcherJni.readBindingStr(bindingPtr)
    fun waterui_read_binding_color(bindingPtr: Long): Long = WatcherJni.readBindingColor(bindingPtr)
    fun waterui_drop_binding_bool(bindingPtr: Long) = WatcherJni.dropBindingBool(bindingPtr)
    fun waterui_drop_binding_int(bindingPtr: Long) = WatcherJni.dropBindingInt(bindingPtr)
    fun waterui_drop_binding_double(bindingPtr: Long) = WatcherJni.dropBindingDouble(bindingPtr)
    fun waterui_drop_binding_str(bindingPtr: Long) = WatcherJni.dropBindingStr(bindingPtr)
    fun waterui_drop_binding_color(bindingPtr: Long) = WatcherJni.dropBindingColor(bindingPtr)

    // Date binding functions
    fun waterui_read_binding_date(bindingPtr: Long): DateStruct = WatcherJni.readBindingDate(bindingPtr)
    fun waterui_set_binding_date(bindingPtr: Long, date: DateStruct) = WatcherJni.setBindingDate(bindingPtr, date.year, date.month, date.day)
    fun waterui_drop_binding_date(bindingPtr: Long) = WatcherJni.dropBindingDate(bindingPtr)
    fun waterui_watch_binding_date(bindingPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchBindingDate(bindingPtr, watcher)
    fun waterui_create_date_watcher(callback: WatcherCallback<DateStruct>): WatcherStruct = WatcherJni.createDateWatcher(callback)

    // ========== Computed accessors ==========

    fun waterui_read_computed_f64(computedPtr: Long): Double = WatcherJni.readComputedF64(computedPtr)
    fun waterui_drop_computed_f64(computedPtr: Long) = WatcherJni.dropComputedF64(computedPtr)
    fun waterui_read_computed_f32(computedPtr: Long): Float = WatcherJni.readComputedF32(computedPtr)
    fun waterui_drop_computed_f32(computedPtr: Long) = WatcherJni.dropComputedF32(computedPtr)
    fun waterui_watch_computed_f64(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedF64(computedPtr, watcher)
    fun waterui_watch_computed_f32(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedF32(computedPtr, watcher)
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
    fun waterui_color_from_srgba(red: Float, green: Float, blue: Float, alpha: Float): Long =
        WatcherJni.colorFromSrgba(red, green, blue, alpha)
    fun waterui_color_from_linear_rgba_headroom(
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        headroom: Float
    ): Long = WatcherJni.colorFromLinearRgbaHeadroom(red, green, blue, alpha, headroom)
    fun waterui_read_computed_resolved_color(computedPtr: Long): ResolvedColorStruct = WatcherJni.readComputedResolvedColor(computedPtr)
    fun waterui_drop_computed_resolved_color(computedPtr: Long) = WatcherJni.dropComputedResolvedColor(computedPtr)
    fun waterui_read_computed_color(computedPtr: Long): Long = WatcherJni.readComputedColor(computedPtr)
    fun waterui_drop_computed_color(computedPtr: Long) = WatcherJni.dropComputedColor(computedPtr)
    fun waterui_watch_computed_resolved_color(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedResolvedColor(computedPtr, watcher)

    // ========== Font resolution ==========

    fun waterui_resolve_font(fontPtr: Long, envPtr: Long): Long = WatcherJni.resolveFont(fontPtr, envPtr)
    fun waterui_drop_font(fontPtr: Long) = WatcherJni.dropFont(fontPtr)

    // ========== Action ==========

    fun waterui_call_action(actionPtr: Long, envPtr: Long) = WatcherJni.callAction(actionPtr, envPtr)
    fun waterui_drop_action(actionPtr: Long) = WatcherJni.dropAction(actionPtr)

    // ========== Index/Move Action ==========

    fun waterui_drop_index_action(actionPtr: Long) = WatcherJni.dropIndexAction(actionPtr)
    fun waterui_call_index_action(actionPtr: Long, envPtr: Long, index: Long) = WatcherJni.callIndexAction(actionPtr, envPtr, index)
    fun waterui_drop_move_action(actionPtr: Long) = WatcherJni.dropMoveAction(actionPtr)
    fun waterui_call_move_action(actionPtr: Long, envPtr: Long, fromIndex: Long, toIndex: Long) = WatcherJni.callMoveAction(actionPtr, envPtr, fromIndex, toIndex)

    // ========== Watcher guard ==========

    fun waterui_drop_watcher_guard(guardPtr: Long) = WatcherJni.dropWatcherGuard(guardPtr)

    // ========== Animation ==========

    /** Get full animation from metadata with all parameters */
    fun waterui_get_animation(metadataPtr: Long): WuiAnimation {
        val tag = WatcherJni.getAnimationTag(metadataPtr)
        val durationMs = WatcherJni.getAnimationDurationMs(metadataPtr)
        val stiffness = WatcherJni.getAnimationStiffness(metadataPtr)
        val damping = WatcherJni.getAnimationDamping(metadataPtr)
        return WuiAnimation.fromNative(tag, durationMs, stiffness, damping)
    }

    /** Legacy: get animation tag only (deprecated) */
    @Deprecated("Use waterui_get_animation instead for full animation support")
    fun waterui_get_animation_tag(metadataPtr: Long): Int = WatcherJni.getAnimationTag(metadataPtr)

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
    fun waterui_force_as_date_picker(viewPtr: Long): DatePickerStruct = WatcherJni.forceAsDatePicker(viewPtr)
    fun waterui_force_as_color_picker(viewPtr: Long): ColorPickerStruct = WatcherJni.forceAsColorPicker(viewPtr)
    fun waterui_force_as_progress(viewPtr: Long): ProgressStruct = WatcherJni.forceAsProgress(viewPtr)
    fun waterui_force_as_scroll(viewPtr: Long): ScrollStruct = WatcherJni.forceAsScrollView(viewPtr)
    fun waterui_force_as_picker(viewPtr: Long): PickerStruct = WatcherJni.forceAsPicker(viewPtr)
    fun waterui_force_as_secure_field(viewPtr: Long): SecureFieldStruct = WatcherJni.forceAsSecureField(viewPtr)
    fun waterui_force_as_metadata_env(viewPtr: Long): MetadataEnvStruct = WatcherJni.forceAsMetadataEnv(viewPtr)
    fun waterui_force_as_metadata_secure(viewPtr: Long): MetadataSecureStruct = WatcherJni.forceAsMetadataSecure(viewPtr)
    fun waterui_force_as_metadata_standard_dynamic_range(viewPtr: Long): MetadataStandardDynamicRangeStruct =
        WatcherJni.forceAsMetadataStandardDynamicRange(viewPtr)
    fun waterui_force_as_metadata_high_dynamic_range(viewPtr: Long): MetadataHighDynamicRangeStruct =
        WatcherJni.forceAsMetadataHighDynamicRange(viewPtr)
    fun waterui_force_as_metadata_gesture(viewPtr: Long): MetadataGestureStruct = WatcherJni.forceAsMetadataGesture(viewPtr)
    fun waterui_force_as_metadata_lifecycle_hook(viewPtr: Long): MetadataLifeCycleHookStruct = WatcherJni.forceAsMetadataLifeCycleHook(viewPtr)
    fun waterui_force_as_metadata_on_event(viewPtr: Long): MetadataOnEventStruct = WatcherJni.forceAsMetadataOnEvent(viewPtr)
    fun waterui_force_as_metadata_cursor(viewPtr: Long): MetadataCursorStruct = WatcherJni.forceAsMetadataCursor(viewPtr)
    fun waterui_force_as_metadata_shadow(viewPtr: Long): MetadataShadowStruct = WatcherJni.forceAsMetadataShadow(viewPtr)
    fun waterui_force_as_metadata_border(viewPtr: Long): MetadataBorderStruct = WatcherJni.forceAsMetadataBorder(viewPtr)
    fun waterui_metadata_border_id(): TypeIdStruct = WatcherJni.metadataBorderId()
    fun waterui_force_as_metadata_focused(viewPtr: Long): MetadataFocusedStruct = WatcherJni.forceAsMetadataFocused(viewPtr)
    fun waterui_force_as_metadata_ignore_safe_area(viewPtr: Long): MetadataIgnoreSafeAreaStruct = WatcherJni.forceAsMetadataIgnoreSafeArea(viewPtr)
    fun waterui_force_as_metadata_retain(viewPtr: Long): MetadataRetainStruct = WatcherJni.forceAsMetadataRetain(viewPtr)
    fun waterui_force_as_metadata_scale(viewPtr: Long): MetadataScaleStruct = WatcherJni.forceAsMetadataScale(viewPtr)
    fun waterui_force_as_metadata_rotation(viewPtr: Long): MetadataRotationStruct = WatcherJni.forceAsMetadataRotation(viewPtr)
    fun waterui_force_as_metadata_offset(viewPtr: Long): MetadataOffsetStruct = WatcherJni.forceAsMetadataOffset(viewPtr)
    fun waterui_force_as_metadata_blur(viewPtr: Long): MetadataBlurStruct = WatcherJni.forceAsMetadataBlur(viewPtr)
    fun waterui_force_as_metadata_brightness(viewPtr: Long): MetadataBrightnessStruct = WatcherJni.forceAsMetadataBrightness(viewPtr)
    fun waterui_force_as_metadata_saturation(viewPtr: Long): MetadataSaturationStruct = WatcherJni.forceAsMetadataSaturation(viewPtr)
    fun waterui_force_as_metadata_contrast(viewPtr: Long): MetadataContrastStruct = WatcherJni.forceAsMetadataContrast(viewPtr)
    fun waterui_force_as_metadata_hue_rotation(viewPtr: Long): MetadataHueRotationStruct = WatcherJni.forceAsMetadataHueRotation(viewPtr)
    fun waterui_force_as_metadata_grayscale(viewPtr: Long): MetadataGrayscaleStruct = WatcherJni.forceAsMetadataGrayscale(viewPtr)
    fun waterui_force_as_metadata_opacity(viewPtr: Long): MetadataOpacityStruct = WatcherJni.forceAsMetadataOpacity(viewPtr)
    fun waterui_force_as_metadata_clip_shape(viewPtr: Long): MetadataClipShapeStruct = WatcherJni.forceAsMetadataClipShape(viewPtr)
    fun waterui_force_as_metadata_context_menu(viewPtr: Long): MetadataContextMenuStruct = WatcherJni.forceAsMetadataContextMenu(viewPtr)
    fun waterui_metadata_context_menu_id(): TypeIdStruct = WatcherJni.metadataContextMenuId()
    fun waterui_read_computed_menu_items(computedPtr: Long): Array<MenuItemStruct> = WatcherJni.readComputedMenuItems(computedPtr)
    fun waterui_drop_computed_menu_items(computedPtr: Long) = WatcherJni.dropComputedMenuItems(computedPtr)
    fun waterui_call_shared_action(actionPtr: Long, envPtr: Long) = WatcherJni.callSharedAction(actionPtr, envPtr)
    fun waterui_drop_shared_action(actionPtr: Long) = WatcherJni.dropSharedAction(actionPtr)
    fun waterui_force_as_filled_shape(viewPtr: Long): FilledShapeStruct = WatcherJni.forceAsFilledShape(viewPtr)
    fun waterui_filled_shape_id(): TypeIdStruct = WatcherJni.filledShapeId()
    fun waterui_force_as_photo(viewPtr: Long): PhotoStruct = WatcherJni.forceAsPhoto(viewPtr)
    fun waterui_force_as_video(viewPtr: Long): VideoStruct2 = WatcherJni.forceAsVideo(viewPtr)
    fun waterui_force_as_video_player(viewPtr: Long): VideoPlayerStruct = WatcherJni.forceAsVideoPlayer(viewPtr)
    fun waterui_force_as_webview(viewPtr: Long): WebViewStruct = WebViewStruct(WatcherJni.forceAsWebView(viewPtr))
    fun waterui_webview_native_handle(webviewPtr: Long): Long = WatcherJni.webviewNativeHandle(webviewPtr)
    fun waterui_webview_native_view(handlePtr: Long): android.webkit.WebView? = WatcherJni.webviewNativeView(handlePtr)
    fun waterui_drop_web_view(webviewPtr: Long) = WatcherJni.dropWebView(webviewPtr)

    // ========== Media Type IDs ==========

    fun waterui_photo_id(): TypeIdStruct = WatcherJni.photoId()
    fun waterui_video_id(): TypeIdStruct = WatcherJni.videoId()
    fun waterui_webview_id(): TypeIdStruct = WatcherJni.webviewId()

    // ========== Navigation Type IDs ==========

    fun waterui_navigation_stack_id(): TypeIdStruct = WatcherJni.navigationStackId()
    fun waterui_navigation_view_id(): TypeIdStruct = WatcherJni.navigationViewId()
    fun waterui_tabs_id(): TypeIdStruct = WatcherJni.tabsId()

    // ========== List Type IDs ==========

    fun waterui_list_id(): TypeIdStruct = WatcherJni.listId()
    fun waterui_list_item_id(): TypeIdStruct = WatcherJni.listItemId()

    // ========== List Force-As Functions ==========

    fun waterui_force_as_list(viewPtr: Long): ListStruct = WatcherJni.forceAsList(viewPtr)
    fun waterui_force_as_list_item(viewPtr: Long): ListItemStruct = WatcherJni.forceAsListItem(viewPtr)

    // ========== Navigation Force-As Functions ==========

    fun waterui_force_as_navigation_stack(viewPtr: Long): NavigationStackStruct = WatcherJni.forceAsNavigationStack(viewPtr)
    fun waterui_force_as_navigation_view(viewPtr: Long): NavigationViewStruct = WatcherJni.forceAsNavigationView(viewPtr)
    fun waterui_force_as_tabs(viewPtr: Long): TabsStruct = WatcherJni.forceAsTabs(viewPtr)
    fun waterui_tab_content(contentPtr: Long): NavigationViewStruct = WatcherJni.tabContent(contentPtr)

    // ========== Navigation Controller Functions ==========

    fun waterui_navigation_controller_new(callback: NavigationControllerCallback): Long =
        WatcherJni.navigationControllerNew(callback)
    fun waterui_env_install_navigation_controller(envPtr: Long, controllerPtr: Long) =
        WatcherJni.envInstallNavigationController(envPtr, controllerPtr)
    fun waterui_drop_navigation_controller(ptr: Long) =
        WatcherJni.dropNavigationController(ptr)

    // ========== Metadata Type IDs ==========

    fun waterui_metadata_secure_id(): TypeIdStruct = WatcherJni.metadataSecureId()
    fun waterui_metadata_standard_dynamic_range_id(): TypeIdStruct = WatcherJni.metadataStandardDynamicRangeId()
    fun waterui_metadata_high_dynamic_range_id(): TypeIdStruct = WatcherJni.metadataHighDynamicRangeId()
    fun waterui_metadata_gesture_id(): TypeIdStruct = WatcherJni.metadataGestureId()
    fun waterui_metadata_lifecycle_hook_id(): TypeIdStruct = WatcherJni.metadataLifeCycleHookId()
    fun waterui_metadata_on_event_id(): TypeIdStruct = WatcherJni.metadataOnEventId()
    fun waterui_metadata_cursor_id(): TypeIdStruct = WatcherJni.metadataCursorId()
    fun waterui_metadata_foreground_id(): TypeIdStruct = WatcherJni.metadataForegroundId()
    fun waterui_metadata_shadow_id(): TypeIdStruct = WatcherJni.metadataShadowId()
    fun waterui_metadata_focused_id(): TypeIdStruct = WatcherJni.metadataFocusedId()
    fun waterui_metadata_ignore_safe_area_id(): TypeIdStruct = WatcherJni.metadataIgnoreSafeAreaId()
    fun waterui_metadata_retain_id(): TypeIdStruct = WatcherJni.metadataRetainId()
    fun waterui_metadata_scale_id(): TypeIdStruct = WatcherJni.metadataScaleId()
    fun waterui_metadata_rotation_id(): TypeIdStruct = WatcherJni.metadataRotationId()
    fun waterui_metadata_offset_id(): TypeIdStruct = WatcherJni.metadataOffsetId()
    fun waterui_metadata_blur_id(): TypeIdStruct = WatcherJni.metadataBlurId()
    fun waterui_metadata_brightness_id(): TypeIdStruct = WatcherJni.metadataBrightnessId()
    fun waterui_metadata_saturation_id(): TypeIdStruct = WatcherJni.metadataSaturationId()
    fun waterui_metadata_contrast_id(): TypeIdStruct = WatcherJni.metadataContrastId()
    fun waterui_metadata_hue_rotation_id(): TypeIdStruct = WatcherJni.metadataHueRotationId()
    fun waterui_metadata_grayscale_id(): TypeIdStruct = WatcherJni.metadataGrayscaleId()
    fun waterui_metadata_opacity_id(): TypeIdStruct = WatcherJni.metadataOpacityId()
    fun waterui_metadata_clip_shape_id(): TypeIdStruct = WatcherJni.metadataClipShapeId()
    fun waterui_video_player_id(): TypeIdStruct = WatcherJni.videoPlayerId()
    fun waterui_media_picker_id(): TypeIdStruct = WatcherJni.mediaPickerId()
    fun waterui_force_as_media_picker(viewPtr: Long): MediaPickerStruct = WatcherJni.forceAsMediaPicker(viewPtr)
    fun waterui_menu_id(): TypeIdStruct = WatcherJni.menuId()
    fun waterui_force_as_menu(viewPtr: Long): MenuStruct = WatcherJni.forceAsMenu(viewPtr)

    // ========== Media Selection Callback ==========

    @JvmStatic
    external fun callOnSelection(dataPtr: Long, callPtr: Long, selectionId: Int)

    // ========== LifeCycleHook Handler ==========

    fun waterui_call_lifecycle_hook(handlerPtr: Long, envPtr: Long) = WatcherJni.callLifeCycleHook(handlerPtr, envPtr)
    fun waterui_drop_lifecycle_hook(handlerPtr: Long) = WatcherJni.dropLifeCycleHook(handlerPtr)

    // ========== OnEvent Handler ==========

    fun waterui_call_on_event(handlerPtr: Long, envPtr: Long) = WatcherJni.callOnEvent(handlerPtr, envPtr)
    fun waterui_drop_on_event(handlerPtr: Long) = WatcherJni.dropOnEvent(handlerPtr)

    // ========== Cursor Style Computed ==========

    fun waterui_read_computed_cursor_style(computedPtr: Long): Int = WatcherJni.readComputedCursorStyle(computedPtr)
    fun waterui_watch_computed_cursor_style(computedPtr: Long, watcher: WatcherStruct): Long = WatcherJni.watchComputedCursorStyle(computedPtr, watcher)
    fun waterui_drop_computed_cursor_style(computedPtr: Long) = WatcherJni.dropComputedCursorStyle(computedPtr)
    fun waterui_create_cursor_style_watcher(callback: dev.waterui.android.reactive.WatcherCallback<Int>): WatcherStruct = WatcherJni.createCursorStyleWatcher(callback)

    // ========== Retain ==========

    fun waterui_drop_retain(retainPtr: Long) = WatcherJni.dropRetain(retainPtr)

    // ========== GpuSurface ==========

    fun waterui_gpu_surface_id(): TypeIdStruct = WatcherJni.gpuSurfaceId()
    fun waterui_force_as_gpu_surface(viewPtr: Long): GpuSurfaceStruct = WatcherJni.forceAsGpuSurface(viewPtr)
    fun waterui_gpu_surface_init(rendererPtr: Long, surface: android.view.Surface, width: Int, height: Int): Long =
        WatcherJni.gpuSurfaceInit(rendererPtr, surface, width, height)
    fun waterui_gpu_surface_render(statePtr: Long, width: Int, height: Int): Boolean =
        WatcherJni.gpuSurfaceRender(statePtr, width, height)
    fun waterui_gpu_surface_drop(statePtr: Long) = WatcherJni.gpuSurfaceDrop(statePtr)

    // ========== Reactive State Creation (for theme) ==========

    fun waterui_create_reactive_color_state(argb: Int): Long = WatcherJni.createReactiveColorState(argb)
    fun waterui_reactive_color_state_to_computed(statePtr: Long): Long = WatcherJni.reactiveColorStateToComputed(statePtr)
    fun waterui_reactive_color_state_set(statePtr: Long, argb: Int) = WatcherJni.reactiveColorStateSet(statePtr, argb)
    fun waterui_create_reactive_font_state(size: Float, weight: Int): Long = WatcherJni.createReactiveFontState(size, weight)
    fun waterui_reactive_font_state_to_computed(statePtr: Long): Long = WatcherJni.reactiveFontStateToComputed(statePtr)
    fun waterui_reactive_font_state_set(statePtr: Long, size: Float, weight: Int) = WatcherJni.reactiveFontStateSet(statePtr, size, weight)

    // ========== Drag and Drop ==========

    fun waterui_metadata_draggable_id(): TypeIdStruct = WatcherJni.metadataDraggableId()
    fun waterui_metadata_drop_destination_id(): TypeIdStruct = WatcherJni.metadataDropDestinationId()
    fun waterui_force_as_metadata_draggable(viewPtr: Long): dev.waterui.android.components.MetadataDraggableStruct =
        WatcherJni.forceAsMetadataDraggable(viewPtr)
    fun waterui_force_as_metadata_drop_destination(viewPtr: Long): dev.waterui.android.components.MetadataDropDestinationStruct =
        WatcherJni.forceAsMetadataDropDestination(viewPtr)
    fun waterui_draggable_get_data(draggablePtr: Long): dev.waterui.android.components.DragDataStruct =
        WatcherJni.draggableGetData(draggablePtr)
    fun waterui_drop_draggable(draggablePtr: Long) = WatcherJni.dropDraggable(draggablePtr)
    fun waterui_drop_drop_destination(dropDestPtr: Long) = WatcherJni.dropDropDestination(dropDestPtr)
    fun waterui_call_drop_handler(dropDestPtr: Long, envPtr: Long, dataTag: Int, dataValue: String) =
        WatcherJni.callDropHandler(dropDestPtr, envPtr, dataTag, dataValue)
    fun waterui_call_drop_enter_handler(dropDestPtr: Long, envPtr: Long) =
        WatcherJni.callDropEnterHandler(dropDestPtr, envPtr)
    fun waterui_call_drop_exit_handler(dropDestPtr: Long, envPtr: Long) =
        WatcherJni.callDropExitHandler(dropDestPtr, envPtr)
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
