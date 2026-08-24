package dev.waterui.android.ffi

import android.app.Activity
import android.content.Context
import android.webkit.WebView
import dev.waterui.android.components.WebViewFactory
import dev.waterui.android.reactive.WatcherCallback
import dev.waterui.android.runtime.*

/**
 * JNI interface for all WaterUI FFI functions.
 *
 * This object provides access to the Rust WaterUI library via JNI.
 */
object WatcherJni {
    init {
        // All JNI exports are provided by Rust.
        System.loadLibrary("waterui_app")
    }

    // ========== Core Functions ==========

    @JvmStatic external fun initializeAndroidContext(activity: Activity): Long
    @JvmStatic external fun releaseAndroidContext(owner: Long)
    @JvmStatic external fun init(): Long
    @JvmStatic external fun gpuRuntimeCreate(callback: GpuRuntimeReadyCallback)
    @JvmStatic external fun envInstallGpuRuntime(envPtr: Long, runtimePtr: Long)
    @JvmStatic external fun dropGpuRuntime(runtimePtr: Long)
    @JvmStatic external fun app(envPtr: Long): dev.waterui.android.runtime.AppStruct
    @JvmStatic external fun viewBody(viewPtr: Long, envPtr: Long): Long
    @JvmStatic external fun viewId(viewPtr: Long): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun viewStretchAxis(viewPtr: Long): Int
    @JvmStatic external fun cloneEnv(envPtr: Long): Long
    @JvmStatic external fun dropEnv(envPtr: Long)
    @JvmStatic external fun envInstallLocaleTag(envPtr: Long, localeTag: String)
    // ========== Force-As Functions ==========

    @JvmStatic external fun forceAsPlain(viewPtr: Long): PlainStruct
    @JvmStatic external fun forceAsText(viewPtr: Long): TextStruct
    @JvmStatic external fun forceAsButton(viewPtr: Long): ButtonStruct
    @JvmStatic external fun forceAsTextField(viewPtr: Long): TextFieldStruct
    @JvmStatic external fun forceAsToggle(viewPtr: Long): ToggleStruct
    @JvmStatic external fun forceAsSlider(viewPtr: Long): SliderStruct
    @JvmStatic external fun forceAsStepper(viewPtr: Long): StepperStruct
    @JvmStatic external fun forceAsProgress(viewPtr: Long): ProgressStruct
    @JvmStatic external fun forceAsScrollView(viewPtr: Long): ScrollStruct
    @JvmStatic external fun forceAsColorPicker(viewPtr: Long): ColorPickerStruct
    @JvmStatic external fun forceAsPicker(viewPtr: Long): PickerStruct
    @JvmStatic external fun forceAsPickerItem(viewPtr: Long): PickerItemStruct
    @JvmStatic external fun forceAsDatePicker(viewPtr: Long): DatePickerStruct
    @JvmStatic external fun forceAsMultiDatePicker(viewPtr: Long): MultiDatePickerStruct
    @JvmStatic external fun forceAsSecureField(viewPtr: Long): SecureFieldStruct
    @JvmStatic external fun forceAsLayoutContainer(viewPtr: Long): LayoutContainerStruct
    @JvmStatic external fun forceAsFixedContainer(viewPtr: Long): FixedContainerStruct
    @JvmStatic external fun forceAsResolvedColor(viewPtr: Long): ResolvedColorStruct
    @JvmStatic external fun forceAsColor(viewPtr: Long): Long
    @JvmStatic external fun forceAsResolvedGradient(viewPtr: Long): ResolvedGradientStruct
    @JvmStatic external fun forceAsResolvedShape(viewPtr: Long): ResolvedShapeStruct
    @JvmStatic external fun forceAsDynamic(viewPtr: Long): dev.waterui.android.runtime.DynamicStruct
    @JvmStatic external fun forceAsMetadataEnv(viewPtr: Long): MetadataEnvStruct
    @JvmStatic external fun forceAsMetadataNavigationTransitionSource(
        viewPtr: Long
    ): MetadataNavigationTransitionStruct
    @JvmStatic external fun forceAsMetadataNavigationTransitionDestination(
        viewPtr: Long
    ): MetadataNavigationTransitionStruct
    @JvmStatic external fun forceAsMetadataSecure(viewPtr: Long): MetadataSecureStruct
    @JvmStatic external fun forceAsMetadataGesture(viewPtr: Long): MetadataGestureStruct
    @JvmStatic external fun gestureFromPtr(gesturePtr: Long): GestureStruct
    @JvmStatic external fun forceAsMetadataOnEvent(viewPtr: Long): MetadataOnEventStruct
    @JvmStatic external fun forceAsMetadataLifecycleHook(viewPtr: Long): MetadataLifecycleHookStruct
    @JvmStatic external fun forceAsMetadataShadow(viewPtr: Long): MetadataShadowStruct
    @JvmStatic external fun forceAsMetadataBorder(viewPtr: Long): MetadataBorderStruct
    @JvmStatic external fun forceAsMetadataScale(viewPtr: Long): MetadataScaleStruct
    @JvmStatic external fun forceAsMetadataRotation(viewPtr: Long): MetadataRotationStruct
    @JvmStatic external fun forceAsMetadataOffset(viewPtr: Long): MetadataOffsetStruct
    @JvmStatic external fun forceAsMetadataCursor(viewPtr: Long): MetadataCursorStruct
    @JvmStatic external fun forceAsIgnorableMetadataAccessibilityIdentifier(viewPtr: Long): MetadataAccessibilityIdentifierStruct
    @JvmStatic external fun forceAsIgnorableMetadataAccessibilityLabel(viewPtr: Long): MetadataAccessibilityLabelStruct
    @JvmStatic external fun forceAsIgnorableMetadataAccessibilityRole(viewPtr: Long): MetadataAccessibilityValueStruct
    @JvmStatic external fun forceAsIgnorableMetadataAccessibilityHidden(viewPtr: Long): MetadataAccessibilityValueStruct
    @JvmStatic external fun forceAsIgnorableMetadataAccessibilityChildren(viewPtr: Long): MetadataAccessibilityValueStruct
    @JvmStatic external fun forceAsIgnorableMetadataAccessibilityState(viewPtr: Long): MetadataAccessibilityStateStruct
    @JvmStatic external fun forceAsIgnorableMetadataAccessibilityStateSignal(viewPtr: Long): MetadataAccessibilityStateStruct
    @JvmStatic external fun forceAsMetadataClipShape(viewPtr: Long): MetadataClipShapeStruct
    @JvmStatic external fun forceAsMetadataHittable(viewPtr: Long): MetadataHittableStruct
    @JvmStatic external fun forceAsMetadataOpacity(viewPtr: Long): MetadataOpacityStruct
    @JvmStatic external fun forceAsMetadataFocused(viewPtr: Long): MetadataFocusedStruct
    @JvmStatic external fun forceAsMetadataIgnoreSafeArea(viewPtr: Long): MetadataIgnoreSafeAreaStruct
    @JvmStatic external fun forceAsMetadataRetain(viewPtr: Long): MetadataRetainStruct
    @JvmStatic external fun forceAsWebView(viewPtr: Long): WebViewStruct
    @JvmStatic external fun forceAsMenu(viewPtr: Long): MenuStruct
    @JvmStatic external fun forceAsMenuItem(viewPtr: Long): MenuItemStruct
    @JvmStatic external fun forceAsMetadataContextMenu(viewPtr: Long): MetadataContextMenuStruct

    // ========== Drop Functions ==========

    @JvmStatic external fun dropLayout(layoutPtr: Long)
    @JvmStatic external fun dropAction(actionPtr: Long)
    @JvmStatic external fun dropGesture(gesturePtr: Long)
    @JvmStatic external fun callAction(actionPtr: Long, envPtr: Long)
    @JvmStatic external fun callSharedAction(actionPtr: Long, envPtr: Long)
    @JvmStatic external fun dropSharedAction(actionPtr: Long)
    @JvmStatic external fun dropTabContent(contentPtr: Long)
    @JvmStatic external fun dropDynamic(dynamicPtr: Long)
    @JvmStatic external fun dropWebView(webviewPtr: Long)
    @JvmStatic external fun dropColor(colorPtr: Long)
    @JvmStatic external fun dropFont(fontPtr: Long)
    @JvmStatic external fun colorFromLinearRgbaHeadroom(red: Float, green: Float, blue: Float, alpha: Float, headroom: Float): Long
    @JvmStatic external fun resolveColor(colorPtr: Long, envPtr: Long): Long
    @JvmStatic external fun resolveFont(fontPtr: Long, envPtr: Long): Long
    @JvmStatic external fun dropWatcherGuard(guardPtr: Long)
    @JvmStatic external fun getAnimationKindDurationPacked(metadataPtr: Long): Long
    @JvmStatic external fun getAnimationParams12Packed(metadataPtr: Long): Long
    @JvmStatic external fun getAnimationParams34Packed(metadataPtr: Long): Long

    // ========== AnyViews Functions ==========

    @JvmStatic external fun anyViewsLen(handle: Long): Int
    @JvmStatic external fun anyViewsGetView(handle: Long, index: Int): Long
    @JvmStatic external fun anyViewsGetIdsInRange(handle: Long, start: Int, end: Int): IntArray
    @JvmStatic external fun anyViewsWatch(handle: Long, callback: NativeAnyViewsWatcher): Long
    @JvmStatic external fun dropAnyViews(handle: Long)
    @JvmStatic external fun dropAnyView(viewPtr: Long)

    // ========== Binding Read/Write/Drop ==========

    @JvmStatic external fun readBindingBool(bindingPtr: Long): Boolean
    @JvmStatic external fun readBindingInt(bindingPtr: Long): Int
    @JvmStatic external fun readBindingId(bindingPtr: Long): Int
    @JvmStatic external fun readBindingDouble(bindingPtr: Long): Double
    @JvmStatic external fun readBindingStr(bindingPtr: Long): String
    @JvmStatic external fun readBindingStyledStrPlain(bindingPtr: Long): String
    @JvmStatic external fun readBindingSecure(bindingPtr: Long): String
    @JvmStatic external fun setBindingBool(bindingPtr: Long, value: Boolean)
    @JvmStatic external fun setBindingInt(bindingPtr: Long, value: Int)
    @JvmStatic external fun setBindingId(bindingPtr: Long, value: Int)
    @JvmStatic external fun setBindingDouble(bindingPtr: Long, value: Double)
    @JvmStatic external fun setBindingStr(bindingPtr: Long, value: String)
    @JvmStatic external fun setBindingStyledStrPlain(bindingPtr: Long, value: String)
    @JvmStatic external fun setBindingSecure(bindingPtr: Long, value: String)
    @JvmStatic external fun dropBindingSecure(bindingPtr: Long)
    @JvmStatic external fun dropBindingBool(bindingPtr: Long)
    @JvmStatic external fun dropBindingInt(bindingPtr: Long)
    @JvmStatic external fun dropBindingId(bindingPtr: Long)
    @JvmStatic external fun dropBindingDouble(bindingPtr: Long)
    @JvmStatic external fun dropBindingStr(bindingPtr: Long)
    @JvmStatic external fun dropBindingStyledStr(bindingPtr: Long)
    @JvmStatic external fun readBindingColor(bindingPtr: Long): Long
    @JvmStatic external fun setBindingColor(bindingPtr: Long, value: Long)
    @JvmStatic external fun dropBindingColor(bindingPtr: Long)
    @JvmStatic external fun readBindingDateTime(bindingPtr: Long): DateTimeStruct
    @JvmStatic external fun readBindingDateVec(bindingPtr: Long): Array<DateStruct>
    @JvmStatic external fun setBindingDateTime(
        bindingPtr: Long,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    )
    @JvmStatic external fun setBindingDateVec(bindingPtr: Long, dates: Array<DateStruct>)
    @JvmStatic external fun dropBindingDateTime(bindingPtr: Long)
    @JvmStatic external fun dropBindingDateVec(bindingPtr: Long)

    // ========== Computed Read/Drop ==========

    @JvmStatic external fun readComputedF64(computedPtr: Long): Double
    @JvmStatic external fun readComputedF32(computedPtr: Long): Float
    @JvmStatic external fun readComputedBool(computedPtr: Long): Boolean
    @JvmStatic external fun readComputedI32(computedPtr: Long): Int
    @JvmStatic external fun readComputedResolvedColor(computedPtr: Long): ResolvedColorStruct
    @JvmStatic external fun readComputedResolvedFont(computedPtr: Long): ResolvedFontStruct
    @JvmStatic external fun readComputedStyledStr(computedPtr: Long): StyledStrStruct
    @JvmStatic external fun readComputedHorizontalAlignment(computedPtr: Long): Int
    @JvmStatic external fun readComputedDateVec(computedPtr: Long): Array<DateStruct>
    @JvmStatic external fun readComputedColorScheme(computedPtr: Long): Int
    @JvmStatic external fun readComputedCursorStyle(computedPtr: Long): Int
    @JvmStatic external fun dropComputedF64(computedPtr: Long)
    @JvmStatic external fun dropComputedF32(computedPtr: Long)
    @JvmStatic external fun dropComputedBool(computedPtr: Long)
    @JvmStatic external fun dropComputedI32(computedPtr: Long)
    @JvmStatic external fun dropComputedResolvedColor(computedPtr: Long)
    @JvmStatic external fun dropComputedResolvedFont(computedPtr: Long)
    @JvmStatic external fun dropComputedStyledStr(computedPtr: Long)
    @JvmStatic external fun dropComputedHorizontalAlignment(computedPtr: Long)
    @JvmStatic external fun dropComputedDateVec(computedPtr: Long)
    @JvmStatic external fun dropComputedColorScheme(computedPtr: Long)
    @JvmStatic external fun dropComputedCursorStyle(computedPtr: Long)

    // ========== Watcher Creation ==========

    @JvmStatic external fun createBoolWatcher(callback: WatcherCallback<Boolean>): WatcherStruct
    @JvmStatic external fun createIntWatcher(callback: WatcherCallback<Int>): WatcherStruct
    @JvmStatic external fun createIdWatcher(callback: WatcherCallback<Int>): WatcherStruct
    @JvmStatic external fun createCursorStyleWatcher(callback: WatcherCallback<Int>): WatcherStruct
    @JvmStatic external fun createColorSchemeWatcher(callback: WatcherCallback<Int>): WatcherStruct
    @JvmStatic external fun createHorizontalAlignmentWatcher(
        callback: WatcherCallback<Int>
    ): WatcherStruct
    @JvmStatic external fun createDoubleWatcher(callback: WatcherCallback<Double>): WatcherStruct
    @JvmStatic external fun createFloatWatcher(callback: WatcherCallback<Float>): WatcherStruct
    @JvmStatic external fun createStringWatcher(callback: WatcherCallback<String>): WatcherStruct
    @JvmStatic external fun createSecureWatcher(callback: WatcherCallback<String>): WatcherStruct
    @JvmStatic external fun createStyledStrPlainWatcher(
        callback: WatcherCallback<String>
    ): WatcherStruct
    @JvmStatic external fun createAnyViewWatcher(callback: WatcherCallback<Long>): WatcherStruct
    @JvmStatic external fun createStyledStrWatcher(callback: WatcherCallback<StyledStrStruct>): WatcherStruct
    @JvmStatic external fun createResolvedColorWatcher(callback: WatcherCallback<ResolvedColorStruct>): WatcherStruct
    @JvmStatic external fun createResolvedFontWatcher(callback: WatcherCallback<ResolvedFontStruct>): WatcherStruct
    @JvmStatic external fun createColorWatcher(callback: WatcherCallback<Long>): WatcherStruct
    @JvmStatic external fun createDateTimeWatcher(callback: WatcherCallback<DateTimeStruct>): WatcherStruct
    @JvmStatic external fun createDateVecWatcher(callback: WatcherCallback<Array<DateStruct>>): WatcherStruct

    // ========== Watch Binding ==========

    @JvmStatic external fun watchBindingBool(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingInt(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingId(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingDouble(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingStr(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingSecure(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingStyledStr(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingColor(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingDateTime(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingDateVec(bindingPtr: Long, watcher: WatcherStruct): Long

    // ========== Watch Computed ==========

    @JvmStatic external fun watchComputedF64(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedF32(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedBool(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedI32(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedStyledStr(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedResolvedColor(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedResolvedFont(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedHorizontalAlignment(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedDateVec(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedColorScheme(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedCursorStyle(computedPtr: Long, watcher: WatcherStruct): Long

    // ========== Dynamic Connect ==========

    @JvmStatic external fun dynamicConnect(dynamicPtr: Long, watcher: WatcherStruct)

    // ========== Reactive State Creation ==========

    @JvmStatic external fun createReactiveColorState(argb: Int): Long
    @JvmStatic external fun reactiveColorStateToComputed(statePtr: Long): Long
    @JvmStatic external fun reactiveColorStateSet(statePtr: Long, argb: Int)
    @JvmStatic external fun dropReactiveColorState(statePtr: Long)
    @JvmStatic external fun createReactiveColorSchemeState(scheme: Int): Long
    @JvmStatic external fun reactiveColorSchemeStateToComputed(statePtr: Long): Long
    @JvmStatic external fun reactiveColorSchemeStateSet(statePtr: Long, scheme: Int)
    @JvmStatic external fun dropReactiveColorSchemeState(statePtr: Long)
    @JvmStatic external fun createReactiveEdgeInsetsState(
        top: Float,
        bottom: Float,
        leading: Float,
        trailing: Float
    ): Long
    @JvmStatic external fun reactiveEdgeInsetsStateToComputed(statePtr: Long): Long
    @JvmStatic external fun reactiveEdgeInsetsStateSet(
        statePtr: Long,
        top: Float,
        bottom: Float,
        leading: Float,
        trailing: Float
    )
    @JvmStatic external fun dropReactiveEdgeInsetsState(statePtr: Long)
    @JvmStatic external fun envInstallSafeArea(envPtr: Long, signalPtr: Long)
    @JvmStatic external fun createReactiveFontState(size: Float, weight: Int): Long
    @JvmStatic external fun reactiveFontStateToComputed(statePtr: Long): Long
    @JvmStatic external fun reactiveFontStateSet(statePtr: Long, size: Float, weight: Int)
    @JvmStatic external fun dropReactiveFontState(statePtr: Long)

    // ========== Theme Functions ==========

    @JvmStatic external fun themeInstallColor(envPtr: Long, slot: Int, signalPtr: Long)
    @JvmStatic external fun themeInstallFont(envPtr: Long, slot: Int, signalPtr: Long)
    @JvmStatic external fun themeInstallColorScheme(envPtr: Long, signalPtr: Long)
    @JvmStatic external fun themeColor(envPtr: Long, slot: Int): Long
    @JvmStatic external fun themeFont(envPtr: Long, slot: Int): Long
    @JvmStatic external fun themeColorScheme(envPtr: Long): Long

    /**
     * Returns the disabled signal in force at this point in the view tree.
     *
     * Disabled state is a scoped subtree attribute installed by `.disabled(...)`,
     * never a field on an individual control's configuration; every interactive
     * control reads it from the environment it is already handed.
     */
    @JvmStatic external fun envDisabled(envPtr: Long): Long

    // ========== Layout Functions ==========

    @JvmStatic external fun layoutMeasure(layoutPtr: Long, proposal: ProposalStruct, subviews: Array<SubViewStruct>): ViewDimensionsStruct
    @JvmStatic external fun layoutSizeThatFits(layoutPtr: Long, proposal: ProposalStruct, subviews: Array<SubViewStruct>): SizeStruct
    @JvmStatic external fun layoutPlace(layoutPtr: Long, bounds: RectStruct, subviews: Array<SubViewStruct>): Array<RectStruct>
    @JvmStatic external fun layoutLazyStackAxis(layoutPtr: Long): Int
    @JvmStatic external fun layoutLazyStackSpacing(layoutPtr: Long): Float
    @JvmStatic external fun layoutLazyStackHorizontalAlignment(layoutPtr: Long): Int
    @JvmStatic external fun layoutLazyStackVerticalAlignment(layoutPtr: Long): Int
    @JvmStatic external fun layoutWatchInvalidation(layoutPtr: Long, owner: android.view.View): Long
    @JvmStatic external fun layoutWatcherDrop(watcherPtr: Long)

    // ========== Type ID Functions ==========

    @JvmStatic external fun emptyId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun textId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun plainId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun buttonId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun textFieldId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun stepperId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun progressId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun dynamicId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun scrollViewId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun spacerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun appliedFilterId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun viewEffectId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun resolvedColorId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun colorId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun resolvedGradientId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun resolvedShapeId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun toggleId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun sliderId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun fixedContainerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun colorPickerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun pickerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun pickerItemId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun datePickerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun multiDatePickerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun secureFieldId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun layoutContainerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataEnvId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataNavigationTransitionSourceId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataNavigationTransitionDestinationId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataSecureId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataGestureId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataOnEventId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataLifecycleHookId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataShadowId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataBorderId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataScaleId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataRotationId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataOffsetId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataCursorId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun ignorableMetadataAccessibilityIdentifierId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun ignorableMetadataAccessibilityLabelId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun ignorableMetadataAccessibilityRoleId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun ignorableMetadataAccessibilityHiddenId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun ignorableMetadataAccessibilityChildrenId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun ignorableMetadataAccessibilityStateId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun ignorableMetadataAccessibilityStateSignalId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataClipShapeId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataHittableId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataOpacityId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataFocusedId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataIgnoreSafeAreaId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataRetainId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataStandardDynamicRangeId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataHighDynamicRangeId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun forceAsMetadataStandardDynamicRange(
        viewPtr: Long
    ): dev.waterui.android.runtime.MetadataDynamicRangeStruct
    @JvmStatic external fun forceAsMetadataHighDynamicRange(
        viewPtr: Long
    ): dev.waterui.android.runtime.MetadataDynamicRangeStruct
    @JvmStatic external fun metadataContextMenuId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun menuId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun menuItemId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun webViewId(): dev.waterui.android.runtime.TypeIdStruct

    // ========== Navigation Type IDs ==========

    @JvmStatic external fun navigationStackId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun navigationViewId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun tabsId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun splitNavigationContainerId(): dev.waterui.android.runtime.TypeIdStruct

    // ========== List Type IDs ==========

    @JvmStatic external fun listId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun listItemId(): dev.waterui.android.runtime.TypeIdStruct

    // ========== List Force-As Functions ==========

    @JvmStatic external fun forceAsList(viewPtr: Long): dev.waterui.android.runtime.ListStruct
    @JvmStatic external fun forceAsListItem(viewPtr: Long): dev.waterui.android.runtime.ListItemStruct
    @JvmStatic external fun callIndexAction(actionPtr: Long, envPtr: Long, index: Long)
    @JvmStatic external fun dropIndexAction(actionPtr: Long)
    @JvmStatic external fun callMoveAction(
        actionPtr: Long,
        envPtr: Long,
        fromIndex: Long,
        toIndex: Long
    )
    @JvmStatic external fun dropMoveAction(actionPtr: Long)

    // ========== Navigation Force-As Functions ==========

    @JvmStatic external fun forceAsNavigationStack(viewPtr: Long): NavigationStackStruct
    @JvmStatic external fun navigationStackRoot(rootPtr: Long, envPtr: Long): Long
    @JvmStatic external fun forceAsNavigationView(viewPtr: Long): NavigationViewStruct
    @JvmStatic external fun forceAsTabs(viewPtr: Long): TabsStruct
    @JvmStatic external fun forceAsSplitNavigationContainer(viewPtr: Long): SplitNavigationContainerStruct
    @JvmStatic external fun tabContent(contentPtr: Long, envPtr: Long): NavigationViewStruct
    @JvmStatic external fun splitNavigationDetailContent(
        detailPtr: Long,
        selectedId: Int,
        envPtr: Long
    ): NavigationViewStruct
    @JvmStatic external fun dropSplitNavigationDetail(ptr: Long)
    @JvmStatic external fun envInstallNavigationController(envPtr: Long, callback: Any)
    @JvmStatic external fun navigationRequestPop(envPtr: Long, count: Int)
    @JvmStatic external fun navigationCompleteNativePop(envPtr: Long, count: Int)
    @JvmStatic external fun navigationTransitionCompleted(envPtr: Long, id: Long): Boolean
    @JvmStatic external fun navigationTransitionCancelled(envPtr: Long, id: Long): Boolean
    @JvmStatic external fun envInstallWebViewController(envPtr: Long, factory: WebViewFactory)
    @JvmStatic external fun envHasNavigationController(envPtr: Long): Boolean

    // ========== WebView Native Access ==========

    @JvmStatic external fun webviewNativeHandle(webviewPtr: Long): Long
    @JvmStatic external fun webviewNativeView(handlePtr: Long): WebView

    // ========== OnEvent Handler Functions ==========

    @JvmStatic external fun callOnEvent(handlerPtr: Long, envPtr: Long)
    @JvmStatic external fun callOnHoverEvent(
        handlerPtr: Long,
        envPtr: Long,
        x: Float,
        y: Float
    )
    @JvmStatic external fun dropOnEvent(handlerPtr: Long)
    @JvmStatic external fun callLifecycleHook(handlerPtr: Long, envPtr: Long)
    @JvmStatic external fun dropLifecycleHook(handlerPtr: Long)

    // ========== Retain Functions ==========

    @JvmStatic external fun dropRetain(retainPtr: Long)

    // ========== GpuSurface Functions ==========

    @JvmStatic external fun gpuSurfaceId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun forceAsGpuSurface(viewPtr: Long): dev.waterui.android.runtime.GpuSurfaceStruct
    @JvmStatic external fun androidVideoSurfaceHostId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun forceAsAndroidVideoSurfaceHost(
        viewPtr: Long
    ): dev.waterui.android.runtime.AndroidVideoSurfaceHostStruct
    @JvmStatic external fun androidVideoSurfaceHostAttach(
        bridgePtr: Long,
        host: android.view.View
    )
    @JvmStatic external fun androidVideoSurfaceHostDrop(bridgePtr: Long)
    @JvmStatic external fun androidVideoSurfaceHostSurfaceDestroyed(bridgePtr: Long)
    @JvmStatic external fun gpuSurfaceCreate(
        owner: android.view.View,
        rendererPtr: Long,
        hasPictureInPictureHostId: Boolean,
        pictureInPictureHostId: Long,
        wuiEnvPtr: Long
    ): Long
    @JvmStatic external fun gpuSurfaceMeasure(
        statePtr: Long,
        width: Float,
        height: Float
    ): ViewDimensionsStruct
    @JvmStatic external fun gpuSurfacePriority(statePtr: Long): Int
    @JvmStatic external fun gpuSurfaceIsReady(statePtr: Long): Boolean
    @JvmStatic external fun gpuSurfaceAttach(
        statePtr: Long,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        prefersHdr: Boolean
    )
    @JvmStatic external fun gpuSurfaceDetach(statePtr: Long)
    @Suppress("LongParameterList") // Signature mirrors the allocation-free native GPU input ABI.
    @JvmStatic external fun gpuSurfaceSetInput(
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
    )
    @JvmStatic external fun gpuSurfaceRender(statePtr: Long, width: Int, height: Int): Boolean
    @JvmStatic external fun gpuSurfaceDrop(statePtr: Long)
}
