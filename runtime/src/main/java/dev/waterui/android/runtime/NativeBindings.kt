package dev.waterui.android.runtime

import android.app.Activity
import android.content.Context
import dev.waterui.android.components.WebViewFactory
import dev.waterui.android.ffi.WatcherJni

/**
 * Centralised access to native WaterUI FFI functions.
 *
 * This module provides a thin wrapper over WatcherJni JNI exports.
 */
internal object NativeBindings {

    /**
     * Bootstrap the native library. Must be called before any other functions.
     */
    fun bootstrapNativeBindings() {
        // Initialize WatcherJni and load libwaterui_app.so.
        WatcherJni
    }

    fun initializeAndroidContext(activity: Activity): Long =
        WatcherJni.initializeAndroidContext(activity)

    fun releaseAndroidContext(owner: Long) =
        WatcherJni.releaseAndroidContext(owner)

    // ========== Core Functions ==========

    fun waterui_init(): Long = WatcherJni.init()
    fun waterui_gpu_runtime_create(callback: GpuRuntimeReadyCallback) =
        WatcherJni.gpuRuntimeCreate(callback)
    fun waterui_env_install_gpu_runtime(envPtr: Long, runtimePtr: Long) =
        WatcherJni.envInstallGpuRuntime(envPtr, runtimePtr)
    fun waterui_drop_gpu_runtime(runtimePtr: Long) = WatcherJni.dropGpuRuntime(runtimePtr)
    fun waterui_app(envPtr: Long): AppStruct = WatcherJni.app(envPtr)
    fun waterui_view_id(anyViewPtr: Long): TypeIdStruct = WatcherJni.viewId(anyViewPtr)
    fun waterui_view_body(anyViewPtr: Long, envPtr: Long): Long = WatcherJni.viewBody(anyViewPtr, envPtr)
    fun waterui_view_stretch_axis(anyViewPtr: Long): Int = WatcherJni.viewStretchAxis(anyViewPtr)
    // ========== Type Identifiers ==========

    fun waterui_empty_id(): TypeIdStruct = WatcherJni.emptyId()
    fun waterui_text_id(): TypeIdStruct = WatcherJni.textId()
    fun waterui_plain_id(): TypeIdStruct = WatcherJni.plainId()
    fun waterui_button_id(): TypeIdStruct = WatcherJni.buttonId()
    fun waterui_text_field_id(): TypeIdStruct = WatcherJni.textFieldId()
    fun waterui_menu_id(): TypeIdStruct = WatcherJni.menuId()
    fun waterui_menu_item_id(): TypeIdStruct = WatcherJni.menuItemId()
    fun waterui_stepper_id(): TypeIdStruct = WatcherJni.stepperId()
    fun waterui_progress_id(): TypeIdStruct = WatcherJni.progressId()
    fun waterui_dynamic_id(): TypeIdStruct = WatcherJni.dynamicId()
    fun waterui_scroll_view_id(): TypeIdStruct = WatcherJni.scrollViewId()
    fun waterui_spacer_id(): TypeIdStruct = WatcherJni.spacerId()
    fun waterui_applied_filter_id(): TypeIdStruct = WatcherJni.appliedFilterId()
    fun waterui_view_effect_id(): TypeIdStruct = WatcherJni.viewEffectId()
    fun waterui_resolved_color_id(): TypeIdStruct = WatcherJni.resolvedColorId()
    fun waterui_color_id(): TypeIdStruct = WatcherJni.colorId()
    fun waterui_resolved_gradient_id(): TypeIdStruct = WatcherJni.resolvedGradientId()
    fun waterui_resolved_shape_id(): TypeIdStruct = WatcherJni.resolvedShapeId()
    fun waterui_toggle_id(): TypeIdStruct = WatcherJni.toggleId()
    fun waterui_slider_id(): TypeIdStruct = WatcherJni.sliderId()
    fun waterui_fixed_container_id(): TypeIdStruct = WatcherJni.fixedContainerId()
    fun waterui_color_picker_id(): TypeIdStruct = WatcherJni.colorPickerId()
    fun waterui_picker_id(): TypeIdStruct = WatcherJni.pickerId()
    fun waterui_picker_item_id(): TypeIdStruct = WatcherJni.pickerItemId()
    fun waterui_date_picker_id(): TypeIdStruct = WatcherJni.datePickerId()
    fun waterui_multi_date_picker_id(): TypeIdStruct = WatcherJni.multiDatePickerId()
    fun waterui_secure_field_id(): TypeIdStruct = WatcherJni.secureFieldId()
    fun waterui_layout_container_id(): TypeIdStruct = WatcherJni.layoutContainerId()
    fun waterui_metadata_env_id(): TypeIdStruct = WatcherJni.metadataEnvId()
    fun waterui_metadata_navigation_transition_source_id(): TypeIdStruct =
        WatcherJni.metadataNavigationTransitionSourceId()
    fun waterui_metadata_navigation_transition_destination_id(): TypeIdStruct =
        WatcherJni.metadataNavigationTransitionDestinationId()
    fun waterui_metadata_context_menu_id(): TypeIdStruct = WatcherJni.metadataContextMenuId()

    // ========== Theme: Color Scheme ==========

    fun waterui_theme_install_color_scheme(envPtr: Long, signalPtr: Long) = WatcherJni.themeInstallColorScheme(envPtr, signalPtr)
    fun waterui_theme_color_scheme(envPtr: Long): Long = WatcherJni.themeColorScheme(envPtr)

    // ========== Theme: Slot-based Color API ==========

    fun waterui_theme_install_color(envPtr: Long, slot: Int, signalPtr: Long) = WatcherJni.themeInstallColor(envPtr, slot, signalPtr)
    fun waterui_theme_color(envPtr: Long, slot: Int): Long = WatcherJni.themeColor(envPtr, slot)

    // ========== Theme: Slot-based Font API ==========

    fun waterui_theme_install_font(envPtr: Long, slot: Int, signalPtr: Long) = WatcherJni.themeInstallFont(envPtr, slot, signalPtr)
    fun waterui_theme_font(envPtr: Long, slot: Int): Long = WatcherJni.themeFont(envPtr, slot)
    fun waterui_create_reactive_font_state(size: Float, weight: Int): Long =
        WatcherJni.createReactiveFontState(size, weight)
    fun waterui_reactive_font_state_to_computed(statePtr: Long): Long =
        WatcherJni.reactiveFontStateToComputed(statePtr)
    fun waterui_reactive_font_state_set(statePtr: Long, size: Float, weight: Int) =
        WatcherJni.reactiveFontStateSet(statePtr, size, weight)
    fun waterui_drop_reactive_font_state(statePtr: Long) =
        WatcherJni.dropReactiveFontState(statePtr)

    // ========== Environment ==========

    fun waterui_clone_env(envPtr: Long): Long = WatcherJni.cloneEnv(envPtr)
    fun waterui_env_drop(envPtr: Long) = WatcherJni.dropEnv(envPtr)
    fun waterui_env_install_locale_tag(envPtr: Long, localeTag: String) =
        WatcherJni.envInstallLocaleTag(envPtr, localeTag)

    // ========== Layout bridging ==========

    fun waterui_force_as_layout_container(viewPtr: Long): LayoutContainerStruct = WatcherJni.forceAsLayoutContainer(viewPtr)
    fun waterui_force_as_fixed_container(viewPtr: Long): FixedContainerStruct = WatcherJni.forceAsFixedContainer(viewPtr)
    fun waterui_layout_measure(layoutPtr: Long, proposal: ProposalStruct, subviews: Array<SubViewStruct>): ViewDimensionsStruct =
        WatcherJni.layoutMeasure(layoutPtr, proposal, subviews)
    fun waterui_layout_size_that_fits(layoutPtr: Long, proposal: ProposalStruct, subviews: Array<SubViewStruct>): SizeStruct =
        WatcherJni.layoutSizeThatFits(layoutPtr, proposal, subviews)
    fun waterui_layout_place(layoutPtr: Long, bounds: RectStruct, subviews: Array<SubViewStruct>): Array<RectStruct> =
        WatcherJni.layoutPlace(layoutPtr, bounds, subviews)
    fun waterui_layout_lazy_stack_axis(layoutPtr: Long): Int = WatcherJni.layoutLazyStackAxis(layoutPtr)
    fun waterui_layout_lazy_stack_spacing(layoutPtr: Long): Float = WatcherJni.layoutLazyStackSpacing(layoutPtr)
    fun waterui_layout_lazy_stack_horizontal_alignment(layoutPtr: Long): Int =
        WatcherJni.layoutLazyStackHorizontalAlignment(layoutPtr)
    fun waterui_layout_lazy_stack_vertical_alignment(layoutPtr: Long): Int =
        WatcherJni.layoutLazyStackVerticalAlignment(layoutPtr)
    fun waterui_layout_watch_invalidation(layoutPtr: Long, owner: android.view.View): Long =
        WatcherJni.layoutWatchInvalidation(layoutPtr, owner)
    fun waterui_layout_watcher_drop(watcherPtr: Long) = WatcherJni.layoutWatcherDrop(watcherPtr)
    fun waterui_drop_layout(layoutPtr: Long) = WatcherJni.dropLayout(layoutPtr)

    // ========== AnyViews ==========

    fun waterui_any_views_len(handle: Long): Int = WatcherJni.anyViewsLen(handle)
    fun waterui_any_views_get_view(handle: Long, index: Int): Long = WatcherJni.anyViewsGetView(handle, index)
    fun waterui_any_views_get_ids(handle: Long, start: Int, end: Int): IntArray =
        WatcherJni.anyViewsGetIdsInRange(handle, start, end)
    fun waterui_any_views_watch(handle: Long, callback: NativeAnyViewsWatcher): Long =
        WatcherJni.anyViewsWatch(handle, callback)
    fun waterui_drop_any_views(handle: Long) = WatcherJni.dropAnyViews(handle)
    fun waterui_drop_any_view(viewPtr: Long) = WatcherJni.dropAnyView(viewPtr)

    // ========== Color resolution ==========

    fun waterui_color_from_linear_rgba_headroom(
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        headroom: Float
    ): Long = WatcherJni.colorFromLinearRgbaHeadroom(red, green, blue, alpha, headroom)
    fun waterui_resolve_color(colorPtr: Long, envPtr: Long): Long = WatcherJni.resolveColor(colorPtr, envPtr)
    fun waterui_drop_color(colorPtr: Long) = WatcherJni.dropColor(colorPtr)

    // ========== Font resolution ==========

    fun waterui_resolve_font(fontPtr: Long, envPtr: Long): Long = WatcherJni.resolveFont(fontPtr, envPtr)
    fun waterui_drop_font(fontPtr: Long) = WatcherJni.dropFont(fontPtr)

    // ========== Action ==========

    fun waterui_call_action(actionPtr: Long, envPtr: Long) = WatcherJni.callAction(actionPtr, envPtr)
    fun waterui_drop_action(actionPtr: Long) = WatcherJni.dropAction(actionPtr)
    fun waterui_call_shared_action(actionPtr: Long, envPtr: Long) =
        WatcherJni.callSharedAction(actionPtr, envPtr)
    fun waterui_drop_shared_action(actionPtr: Long) = WatcherJni.dropSharedAction(actionPtr)
    fun waterui_drop_tab_content(contentPtr: Long) = WatcherJni.dropTabContent(contentPtr)
    fun waterui_drop_gesture(gesturePtr: Long) = WatcherJni.dropGesture(gesturePtr)

    // ========== Watcher guard ==========

    fun waterui_drop_watcher_guard(guardPtr: Long) = WatcherJni.dropWatcherGuard(guardPtr)
    fun waterui_get_animation(metadataPtr: Long): WuiAnimation = WuiAnimation.fromNative(metadataPtr)

    // ========== Dynamic ==========

    fun waterui_force_as_dynamic(viewPtr: Long): DynamicStruct = WatcherJni.forceAsDynamic(viewPtr)
    fun waterui_drop_dynamic(dynamicPtr: Long) = WatcherJni.dropDynamic(dynamicPtr)
    fun waterui_dynamic_connect(dynamicPtr: Long, watcher: WatcherStruct) = WatcherJni.dynamicConnect(dynamicPtr, watcher)

    // ========== Force-as functions ==========

    fun waterui_force_as_plain(viewPtr: Long): PlainStruct = WatcherJni.forceAsPlain(viewPtr)
    fun waterui_force_as_text(viewPtr: Long): TextStruct = WatcherJni.forceAsText(viewPtr)
    fun waterui_force_as_button(viewPtr: Long): ButtonStruct = WatcherJni.forceAsButton(viewPtr)
    fun waterui_force_as_text_field(viewPtr: Long): TextFieldStruct = WatcherJni.forceAsTextField(viewPtr)
    fun waterui_force_as_menu(viewPtr: Long): MenuStruct = WatcherJni.forceAsMenu(viewPtr)
    fun waterui_force_as_menu_item(viewPtr: Long): MenuItemStruct = WatcherJni.forceAsMenuItem(viewPtr)
    fun waterui_force_as_toggle(viewPtr: Long): ToggleStruct = WatcherJni.forceAsToggle(viewPtr)
    fun waterui_force_as_slider(viewPtr: Long): SliderStruct = WatcherJni.forceAsSlider(viewPtr)
    fun waterui_force_as_stepper(viewPtr: Long): StepperStruct = WatcherJni.forceAsStepper(viewPtr)
    fun waterui_force_as_progress(viewPtr: Long): ProgressStruct = WatcherJni.forceAsProgress(viewPtr)
    fun waterui_force_as_scroll(viewPtr: Long): ScrollStruct = WatcherJni.forceAsScrollView(viewPtr)
    fun waterui_force_as_color_picker(viewPtr: Long): ColorPickerStruct = WatcherJni.forceAsColorPicker(viewPtr)
    fun waterui_force_as_picker(viewPtr: Long): PickerStruct = WatcherJni.forceAsPicker(viewPtr)
    fun waterui_force_as_picker_item(viewPtr: Long): PickerItemStruct =
        WatcherJni.forceAsPickerItem(viewPtr)
    fun waterui_force_as_date_picker(viewPtr: Long): DatePickerStruct = WatcherJni.forceAsDatePicker(viewPtr)
    fun waterui_force_as_multi_date_picker(viewPtr: Long): MultiDatePickerStruct = WatcherJni.forceAsMultiDatePicker(viewPtr)
    fun waterui_force_as_secure_field(viewPtr: Long): SecureFieldStruct = WatcherJni.forceAsSecureField(viewPtr)
    fun waterui_force_as_resolved_color(viewPtr: Long): ResolvedColorStruct = WatcherJni.forceAsResolvedColor(viewPtr)
    fun waterui_force_as_color(viewPtr: Long): Long = WatcherJni.forceAsColor(viewPtr)
    fun waterui_force_as_resolved_gradient(viewPtr: Long): ResolvedGradientStruct = WatcherJni.forceAsResolvedGradient(viewPtr)
    fun waterui_force_as_resolved_shape(viewPtr: Long): ResolvedShapeStruct = WatcherJni.forceAsResolvedShape(viewPtr)
    fun waterui_force_as_metadata_env(viewPtr: Long): MetadataEnvStruct = WatcherJni.forceAsMetadataEnv(viewPtr)
    fun waterui_force_as_metadata_navigation_transition_source(
        viewPtr: Long
    ): MetadataNavigationTransitionStruct =
        WatcherJni.forceAsMetadataNavigationTransitionSource(viewPtr)
    fun waterui_force_as_metadata_navigation_transition_destination(
        viewPtr: Long
    ): MetadataNavigationTransitionStruct =
        WatcherJni.forceAsMetadataNavigationTransitionDestination(viewPtr)
    fun waterui_force_as_metadata_context_menu(viewPtr: Long): MetadataContextMenuStruct = WatcherJni.forceAsMetadataContextMenu(viewPtr)
    fun waterui_force_as_metadata_secure(viewPtr: Long): MetadataSecureStruct = WatcherJni.forceAsMetadataSecure(viewPtr)
    fun waterui_force_as_metadata_gesture(viewPtr: Long): MetadataGestureStruct = WatcherJni.forceAsMetadataGesture(viewPtr)
    fun waterui_gesture_from_ptr(gesturePtr: Long): GestureStruct = WatcherJni.gestureFromPtr(gesturePtr)
    fun waterui_force_as_metadata_on_event(viewPtr: Long): MetadataOnEventStruct = WatcherJni.forceAsMetadataOnEvent(viewPtr)
    fun waterui_force_as_metadata_lifecycle_hook(viewPtr: Long): MetadataLifecycleHookStruct =
        WatcherJni.forceAsMetadataLifecycleHook(viewPtr)
    fun waterui_force_as_metadata_shadow(viewPtr: Long): MetadataShadowStruct = WatcherJni.forceAsMetadataShadow(viewPtr)
    fun waterui_force_as_metadata_border(viewPtr: Long): MetadataBorderStruct =
        WatcherJni.forceAsMetadataBorder(viewPtr)
    fun waterui_force_as_metadata_scale(viewPtr: Long): MetadataScaleStruct =
        WatcherJni.forceAsMetadataScale(viewPtr)
    fun waterui_force_as_metadata_rotation(viewPtr: Long): MetadataRotationStruct =
        WatcherJni.forceAsMetadataRotation(viewPtr)
    fun waterui_force_as_metadata_offset(viewPtr: Long): MetadataOffsetStruct =
        WatcherJni.forceAsMetadataOffset(viewPtr)
    fun waterui_force_as_metadata_cursor(viewPtr: Long): MetadataCursorStruct =
        WatcherJni.forceAsMetadataCursor(viewPtr)
    fun waterui_force_as_metadata_clip_shape(viewPtr: Long): MetadataClipShapeStruct =
        WatcherJni.forceAsMetadataClipShape(viewPtr)
    fun waterui_force_as_metadata_hittable(viewPtr: Long): MetadataHittableStruct =
        WatcherJni.forceAsMetadataHittable(viewPtr)
    fun waterui_force_as_metadata_opacity(viewPtr: Long): MetadataOpacityStruct = WatcherJni.forceAsMetadataOpacity(viewPtr)
    fun waterui_force_as_metadata_focused(viewPtr: Long): MetadataFocusedStruct = WatcherJni.forceAsMetadataFocused(viewPtr)
    fun waterui_force_as_metadata_ignore_safe_area(viewPtr: Long): MetadataIgnoreSafeAreaStruct = WatcherJni.forceAsMetadataIgnoreSafeArea(viewPtr)
    fun waterui_force_as_metadata_retain(viewPtr: Long): MetadataRetainStruct = WatcherJni.forceAsMetadataRetain(viewPtr)
    fun waterui_force_as_web_view(viewPtr: Long): WebViewStruct = WatcherJni.forceAsWebView(viewPtr)

    // ========== Media Type IDs ==========

    fun waterui_web_view_id(): TypeIdStruct = WatcherJni.webViewId()

    // ========== Navigation Type IDs ==========

    fun waterui_navigation_stack_id(): TypeIdStruct = WatcherJni.navigationStackId()
    fun waterui_navigation_view_id(): TypeIdStruct = WatcherJni.navigationViewId()
    fun waterui_tabs_id(): TypeIdStruct = WatcherJni.tabsId()
    fun waterui_split_navigation_container_id(): TypeIdStruct = WatcherJni.splitNavigationContainerId()

    // ========== List Type IDs ==========

    fun waterui_list_id(): TypeIdStruct = WatcherJni.listId()
    fun waterui_list_item_id(): TypeIdStruct = WatcherJni.listItemId()

    // ========== List Force-As Functions ==========

    fun waterui_force_as_list(viewPtr: Long): ListStruct = WatcherJni.forceAsList(viewPtr)
    fun waterui_force_as_list_item(viewPtr: Long): ListItemStruct = WatcherJni.forceAsListItem(viewPtr)
    fun waterui_call_index_action(actionPtr: Long, envPtr: Long, index: Int) =
        WatcherJni.callIndexAction(actionPtr, envPtr, index.toLong())
    fun waterui_drop_index_action(actionPtr: Long) = WatcherJni.dropIndexAction(actionPtr)
    fun waterui_call_move_action(actionPtr: Long, envPtr: Long, fromIndex: Int, toIndex: Int) =
        WatcherJni.callMoveAction(actionPtr, envPtr, fromIndex.toLong(), toIndex.toLong())
    fun waterui_drop_move_action(actionPtr: Long) = WatcherJni.dropMoveAction(actionPtr)

    // ========== Navigation Force-As Functions ==========

    fun waterui_force_as_navigation_stack(viewPtr: Long): NavigationStackStruct = WatcherJni.forceAsNavigationStack(viewPtr)
    fun waterui_navigation_stack_root(rootPtr: Long, envPtr: Long): Long =
        WatcherJni.navigationStackRoot(rootPtr, envPtr)
    fun waterui_force_as_navigation_view(viewPtr: Long): NavigationViewStruct = WatcherJni.forceAsNavigationView(viewPtr)
    fun waterui_force_as_tabs(viewPtr: Long): TabsStruct = WatcherJni.forceAsTabs(viewPtr)
    fun waterui_force_as_split_navigation_container(viewPtr: Long): SplitNavigationContainerStruct =
        WatcherJni.forceAsSplitNavigationContainer(viewPtr)
    fun waterui_tab_content(contentPtr: Long, envPtr: Long): NavigationViewStruct =
        WatcherJni.tabContent(contentPtr, envPtr)
    fun waterui_split_navigation_detail_content(
        detailPtr: Long,
        selectedId: Int,
        envPtr: Long
    ): NavigationViewStruct = WatcherJni.splitNavigationDetailContent(detailPtr, selectedId, envPtr)
    fun waterui_drop_split_navigation_detail(detailPtr: Long) = WatcherJni.dropSplitNavigationDetail(detailPtr)
    fun waterui_env_install_navigation_controller(envPtr: Long, callback: Any) =
        WatcherJni.envInstallNavigationController(envPtr, callback)
    fun waterui_navigation_request_pop(envPtr: Long, count: Int) =
        WatcherJni.navigationRequestPop(envPtr, count)
    fun waterui_navigation_complete_native_pop(envPtr: Long, count: Int) =
        WatcherJni.navigationCompleteNativePop(envPtr, count)
    fun waterui_navigation_transition_completed(envPtr: Long, id: Long): Boolean =
        WatcherJni.navigationTransitionCompleted(envPtr, id)
    fun waterui_navigation_transition_cancelled(envPtr: Long, id: Long): Boolean =
        WatcherJni.navigationTransitionCancelled(envPtr, id)
    fun waterui_env_install_webview_controller(envPtr: Long, factory: WebViewFactory) =
        WatcherJni.envInstallWebViewController(envPtr, factory)
    fun waterui_env_has_navigation_controller(envPtr: Long): Boolean = WatcherJni.envHasNavigationController(envPtr)
    fun waterui_webview_native_handle(webviewPtr: Long): Long = WatcherJni.webviewNativeHandle(webviewPtr)
    fun waterui_webview_native_view(handlePtr: Long): android.webkit.WebView = WatcherJni.webviewNativeView(handlePtr)
    fun waterui_drop_web_view(webviewPtr: Long) = WatcherJni.dropWebView(webviewPtr)

    // ========== Metadata Type IDs ==========

    fun waterui_metadata_secure_id(): TypeIdStruct = WatcherJni.metadataSecureId()
    fun waterui_metadata_gesture_id(): TypeIdStruct = WatcherJni.metadataGestureId()
    fun waterui_metadata_on_event_id(): TypeIdStruct = WatcherJni.metadataOnEventId()
    fun waterui_metadata_lifecycle_hook_id(): TypeIdStruct = WatcherJni.metadataLifecycleHookId()
    fun waterui_metadata_shadow_id(): TypeIdStruct = WatcherJni.metadataShadowId()
    fun waterui_metadata_border_id(): TypeIdStruct = WatcherJni.metadataBorderId()
    fun waterui_metadata_scale_id(): TypeIdStruct = WatcherJni.metadataScaleId()
    fun waterui_metadata_rotation_id(): TypeIdStruct = WatcherJni.metadataRotationId()
    fun waterui_metadata_offset_id(): TypeIdStruct = WatcherJni.metadataOffsetId()
    fun waterui_metadata_cursor_id(): TypeIdStruct = WatcherJni.metadataCursorId()
    fun waterui_metadata_clip_shape_id(): TypeIdStruct = WatcherJni.metadataClipShapeId()
    fun waterui_metadata_hittable_id(): TypeIdStruct = WatcherJni.metadataHittableId()
    fun waterui_metadata_opacity_id(): TypeIdStruct = WatcherJni.metadataOpacityId()
    fun waterui_metadata_focused_id(): TypeIdStruct = WatcherJni.metadataFocusedId()
    fun waterui_metadata_ignore_safe_area_id(): TypeIdStruct = WatcherJni.metadataIgnoreSafeAreaId()
    fun waterui_metadata_retain_id(): TypeIdStruct = WatcherJni.metadataRetainId()
    fun waterui_metadata_standard_dynamic_range_id(): TypeIdStruct =
        WatcherJni.metadataStandardDynamicRangeId()
    fun waterui_metadata_high_dynamic_range_id(): TypeIdStruct =
        WatcherJni.metadataHighDynamicRangeId()
    fun waterui_force_as_metadata_standard_dynamic_range(viewPtr: Long): MetadataDynamicRangeStruct =
        WatcherJni.forceAsMetadataStandardDynamicRange(viewPtr)
    fun waterui_force_as_metadata_high_dynamic_range(viewPtr: Long): MetadataDynamicRangeStruct =
        WatcherJni.forceAsMetadataHighDynamicRange(viewPtr)

    // ========== OnEvent Handler ==========

    fun waterui_call_on_event(handlerPtr: Long, envPtr: Long) = WatcherJni.callOnEvent(handlerPtr, envPtr)
    fun waterui_call_on_hover_event(
        handlerPtr: Long,
        envPtr: Long,
        x: Float,
        y: Float
    ) = WatcherJni.callOnHoverEvent(handlerPtr, envPtr, x, y)
    fun waterui_drop_on_event(handlerPtr: Long) = WatcherJni.dropOnEvent(handlerPtr)
    fun waterui_call_lifecycle_hook(handlerPtr: Long, envPtr: Long) =
        WatcherJni.callLifecycleHook(handlerPtr, envPtr)
    fun waterui_drop_lifecycle_hook(handlerPtr: Long) = WatcherJni.dropLifecycleHook(handlerPtr)

    // ========== Retain ==========

    fun waterui_drop_retain(retainPtr: Long) = WatcherJni.dropRetain(retainPtr)

    // ========== GpuSurface ==========

    fun waterui_gpu_surface_id(): TypeIdStruct = WatcherJni.gpuSurfaceId()
    fun waterui_force_as_gpu_surface(viewPtr: Long): GpuSurfaceStruct = WatcherJni.forceAsGpuSurface(viewPtr)
    fun waterui_android_video_surface_host_id(): TypeIdStruct =
        WatcherJni.androidVideoSurfaceHostId()
    fun waterui_force_as_android_video_surface_host(
        viewPtr: Long
    ): AndroidVideoSurfaceHostStruct =
        WatcherJni.forceAsAndroidVideoSurfaceHost(viewPtr)
    fun waterui_android_video_surface_host_attach(
        bridgePtr: Long,
        host: android.view.View
    ) = WatcherJni.androidVideoSurfaceHostAttach(bridgePtr, host)
    fun waterui_android_video_surface_host_drop(bridgePtr: Long) =
        WatcherJni.androidVideoSurfaceHostDrop(bridgePtr)
    fun waterui_android_video_surface_host_surface_destroyed(bridgePtr: Long) =
        WatcherJni.androidVideoSurfaceHostSurfaceDestroyed(bridgePtr)
    fun waterui_gpu_surface_create(
        owner: android.view.View,
        rendererPtr: Long,
        hasPictureInPictureHostId: Boolean,
        pictureInPictureHostId: Long,
        wuiEnvPtr: Long
    ): Long = WatcherJni.gpuSurfaceCreate(
        owner,
        rendererPtr,
        hasPictureInPictureHostId,
        pictureInPictureHostId,
        wuiEnvPtr
    )
    fun waterui_gpu_surface_measure(
        statePtr: Long,
        width: Float,
        height: Float
    ): ViewDimensionsStruct = WatcherJni.gpuSurfaceMeasure(statePtr, width, height)
    fun waterui_gpu_surface_priority(statePtr: Long): Int = WatcherJni.gpuSurfacePriority(statePtr)
    fun waterui_gpu_surface_is_ready(statePtr: Long): Boolean =
        WatcherJni.gpuSurfaceIsReady(statePtr)
    fun waterui_gpu_surface_attach(
        statePtr: Long,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        prefersHdr: Boolean
    ) = WatcherJni.gpuSurfaceAttach(statePtr, surface, width, height, prefersHdr)
    fun waterui_gpu_surface_detach(statePtr: Long) = WatcherJni.gpuSurfaceDetach(statePtr)
    @Suppress("LongParameterList") // Direct JNI transfer avoids allocating an input envelope per gesture frame.
    fun waterui_gpu_surface_set_input(
        statePtr: Long,
        hasPosition: Boolean,
        x: Float,
        y: Float,
        hasHit: Boolean,
        hitX: Float,
        hitY: Float,
        gestureActive: Boolean,
        pinchScale: Float,
        hasPinchCenter: Boolean,
        pinchCenterX: Float,
        pinchCenterY: Float,
        panOffsetX: Float,
        panOffsetY: Float,
        doubleTap: Boolean
    ) = WatcherJni.gpuSurfaceSetInput(
        statePtr,
        hasPosition,
        x,
        y,
        hasHit,
        hitX,
        hitY,
        gestureActive,
        pinchScale,
        hasPinchCenter,
        pinchCenterX,
        pinchCenterY,
        panOffsetX,
        panOffsetY,
        doubleTap
    )
    fun waterui_gpu_surface_render(
        statePtr: Long,
        width: Int,
        height: Int
    ): Boolean = WatcherJni.gpuSurfaceRender(statePtr, width, height)
    fun waterui_gpu_surface_drop(statePtr: Long) = WatcherJni.gpuSurfaceDrop(statePtr)

    // ========== Reactive State Creation (for theme) ==========

    fun waterui_create_reactive_color_state(argb: Int): Long = WatcherJni.createReactiveColorState(argb)
    fun waterui_reactive_color_state_to_computed(statePtr: Long): Long = WatcherJni.reactiveColorStateToComputed(statePtr)
    fun waterui_reactive_color_state_set(statePtr: Long, argb: Int) = WatcherJni.reactiveColorStateSet(statePtr, argb)
    fun waterui_drop_reactive_color_state(statePtr: Long) = WatcherJni.dropReactiveColorState(statePtr)
    fun waterui_create_reactive_color_scheme_state(scheme: Int): Long =
        WatcherJni.createReactiveColorSchemeState(scheme)
    fun waterui_reactive_color_scheme_state_to_computed(statePtr: Long): Long =
        WatcherJni.reactiveColorSchemeStateToComputed(statePtr)
    fun waterui_reactive_color_scheme_state_set(statePtr: Long, scheme: Int) =
        WatcherJni.reactiveColorSchemeStateSet(statePtr, scheme)
    fun waterui_drop_reactive_color_scheme_state(statePtr: Long) =
        WatcherJni.dropReactiveColorSchemeState(statePtr)
}

fun bootstrapWaterUiRuntime(activity: Activity): Long {
    NativeBindings.bootstrapNativeBindings()
    return NativeBindings.initializeAndroidContext(activity)
}

fun releaseWaterUiRuntime(owner: Long) {
    NativeBindings.releaseAndroidContext(owner)
}
