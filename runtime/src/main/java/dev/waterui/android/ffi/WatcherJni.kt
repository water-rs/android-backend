package dev.waterui.android.ffi

import dev.waterui.android.reactive.WatcherCallback
import dev.waterui.android.runtime.*

/**
 * JNI interface for all WaterUI FFI functions.
 *
 * This object provides access to the Rust WaterUI library via JNI.
 * The native library is loaded via dlopen/dlsym to support dynamic loading.
 */
object WatcherJni {
    init {
        // Load the JNI helper library
        System.loadLibrary("waterui_android")
        nativeInit()
    }

    @JvmStatic
    private external fun nativeInit()

    // ========== Core Functions ==========

    @JvmStatic external fun init(): Long
    @JvmStatic external fun app(envPtr: Long): dev.waterui.android.runtime.AppStruct
    @JvmStatic external fun envInstallMediaPickerManager(envPtr: Long)
    @JvmStatic external fun viewBody(viewPtr: Long, envPtr: Long): Long
    @JvmStatic external fun viewId(viewPtr: Long): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun viewStretchAxis(viewPtr: Long): Int
    @JvmStatic external fun cloneEnv(envPtr: Long): Long
    @JvmStatic external fun dropEnv(envPtr: Long)
    @JvmStatic external fun dropAnyview(viewPtr: Long)
    @JvmStatic external fun configureHotReloadEndpoint(host: String, port: Int)
    @JvmStatic external fun configureHotReloadDirectory(path: String)

    // ========== Force-As Functions ==========

    @JvmStatic external fun forceAsPlain(viewPtr: Long): PlainStruct
    @JvmStatic external fun forceAsText(viewPtr: Long): Long
    @JvmStatic external fun forceAsButton(viewPtr: Long): ButtonStruct
    @JvmStatic external fun forceAsColor(viewPtr: Long): Long
    @JvmStatic external fun forceAsTextField(viewPtr: Long): TextFieldStruct
    @JvmStatic external fun forceAsToggle(viewPtr: Long): ToggleStruct
    @JvmStatic external fun forceAsSlider(viewPtr: Long): SliderStruct
    @JvmStatic external fun forceAsStepper(viewPtr: Long): StepperStruct
    @JvmStatic external fun forceAsProgress(viewPtr: Long): ProgressStruct
    @JvmStatic external fun forceAsScrollView(viewPtr: Long): ScrollStruct
    @JvmStatic external fun forceAsPicker(viewPtr: Long): PickerStruct
    @JvmStatic external fun forceAsSecureField(viewPtr: Long): SecureFieldStruct
    @JvmStatic external fun forceAsLayoutContainer(viewPtr: Long): LayoutContainerStruct
    @JvmStatic external fun forceAsFixedContainer(viewPtr: Long): FixedContainerStruct
    @JvmStatic external fun forceAsDynamic(viewPtr: Long): Long
    @JvmStatic external fun forceAsMetadataEnv(viewPtr: Long): MetadataEnvStruct
    @JvmStatic external fun forceAsMetadataSecure(viewPtr: Long): MetadataSecureStruct
    @JvmStatic external fun forceAsMetadataGesture(viewPtr: Long): MetadataGestureStruct
    @JvmStatic external fun forceAsMetadataOnEvent(viewPtr: Long): MetadataOnEventStruct
    @JvmStatic external fun forceAsMetadataBackground(viewPtr: Long): MetadataBackgroundStruct
    @JvmStatic external fun forceAsMetadataForeground(viewPtr: Long): MetadataForegroundStruct
    @JvmStatic external fun forceAsMetadataShadow(viewPtr: Long): MetadataShadowStruct
    @JvmStatic external fun forceAsMetadataFocused(viewPtr: Long): MetadataFocusedStruct
    @JvmStatic external fun forceAsMetadataIgnoreSafeArea(viewPtr: Long): MetadataIgnoreSafeAreaStruct
    @JvmStatic external fun forceAsMetadataRetain(viewPtr: Long): MetadataRetainStruct
    @JvmStatic external fun forceAsPhoto(viewPtr: Long): PhotoStruct
    @JvmStatic external fun forceAsVideo(viewPtr: Long): VideoStruct2
    @JvmStatic external fun forceAsVideoPlayer(viewPtr: Long): VideoPlayerStruct
    @JvmStatic external fun forceAsMediaPicker(viewPtr: Long): MediaPickerStruct

    // ========== Drop Functions ==========

    @JvmStatic external fun dropLayout(layoutPtr: Long)
    @JvmStatic external fun dropAction(actionPtr: Long)
    @JvmStatic external fun callAction(actionPtr: Long, envPtr: Long)
    @JvmStatic external fun dropDynamic(dynamicPtr: Long)
    @JvmStatic external fun dropColor(colorPtr: Long)
    @JvmStatic external fun dropFont(fontPtr: Long)
    @JvmStatic external fun resolveColor(colorPtr: Long, envPtr: Long): Long
    @JvmStatic external fun resolveFont(fontPtr: Long, envPtr: Long): Long
    @JvmStatic external fun dropWatcherGuard(guardPtr: Long)
    @JvmStatic external fun getAnimation(metadataPtr: Long): Int

    // ========== AnyViews Functions ==========

    @JvmStatic external fun anyViewsLen(handle: Long): Int
    @JvmStatic external fun anyViewsGetView(handle: Long, index: Int): Long
    @JvmStatic external fun anyViewsGetId(handle: Long, index: Int): Int
    @JvmStatic external fun dropAnyViews(handle: Long)

    // ========== Binding Read/Write/Drop ==========

    @JvmStatic external fun readBindingBool(bindingPtr: Long): Boolean
    @JvmStatic external fun readBindingInt(bindingPtr: Long): Int
    @JvmStatic external fun readBindingDouble(bindingPtr: Long): Double
    @JvmStatic external fun readBindingStr(bindingPtr: Long): ByteArray
    @JvmStatic external fun setBindingBool(bindingPtr: Long, value: Boolean)
    @JvmStatic external fun setBindingInt(bindingPtr: Long, value: Int)
    @JvmStatic external fun setBindingDouble(bindingPtr: Long, value: Double)
    @JvmStatic external fun setBindingStr(bindingPtr: Long, bytes: ByteArray)
    @JvmStatic external fun setBindingSecure(bindingPtr: Long, bytes: ByteArray)
    @JvmStatic external fun dropBindingBool(bindingPtr: Long)
    @JvmStatic external fun dropBindingInt(bindingPtr: Long)
    @JvmStatic external fun dropBindingDouble(bindingPtr: Long)
    @JvmStatic external fun dropBindingStr(bindingPtr: Long)
    @JvmStatic external fun readBindingFloat(bindingPtr: Long): Float
    @JvmStatic external fun setBindingFloat(bindingPtr: Long, value: Float)
    @JvmStatic external fun dropBindingFloat(bindingPtr: Long)

    // ========== Computed Read/Drop ==========

    @JvmStatic external fun readComputedF64(computedPtr: Long): Double
    @JvmStatic external fun readComputedI32(computedPtr: Long): Int
    @JvmStatic external fun readComputedResolvedColor(computedPtr: Long): ResolvedColorStruct
    @JvmStatic external fun readComputedResolvedFont(computedPtr: Long): ResolvedFontStruct
    @JvmStatic external fun readComputedStyledStr(computedPtr: Long): StyledStrStruct
    @JvmStatic external fun readComputedPickerItems(computedPtr: Long): Array<PickerItemStruct>
    @JvmStatic external fun readComputedColorScheme(computedPtr: Long): Int
    @JvmStatic external fun readComputedColor(computedPtr: Long): Long
    @JvmStatic external fun dropComputedColor(computedPtr: Long)
    @JvmStatic external fun dropComputedF64(computedPtr: Long)
    @JvmStatic external fun dropComputedI32(computedPtr: Long)
    @JvmStatic external fun dropComputedResolvedColor(computedPtr: Long)
    @JvmStatic external fun dropComputedResolvedFont(computedPtr: Long)
    @JvmStatic external fun dropComputedStyledStr(computedPtr: Long)
    @JvmStatic external fun dropComputedPickerItems(computedPtr: Long)
    @JvmStatic external fun dropComputedColorScheme(computedPtr: Long)

    // ========== Watcher Creation ==========

    @JvmStatic external fun createBoolWatcher(callback: WatcherCallback<Boolean>): WatcherStruct
    @JvmStatic external fun createIntWatcher(callback: WatcherCallback<Int>): WatcherStruct
    @JvmStatic external fun createDoubleWatcher(callback: WatcherCallback<Double>): WatcherStruct
    @JvmStatic external fun createFloatWatcher(callback: WatcherCallback<Float>): WatcherStruct
    @JvmStatic external fun createStringWatcher(callback: WatcherCallback<String>): WatcherStruct
    @JvmStatic external fun createVideoWatcher(callback: WatcherCallback<VideoStruct>): WatcherStruct
    @JvmStatic external fun createAnyViewWatcher(callback: WatcherCallback<Long>): WatcherStruct
    @JvmStatic external fun createStyledStrWatcher(callback: WatcherCallback<StyledStrStruct>): WatcherStruct
    @JvmStatic external fun createResolvedColorWatcher(callback: WatcherCallback<ResolvedColorStruct>): WatcherStruct
    @JvmStatic external fun createResolvedFontWatcher(callback: WatcherCallback<ResolvedFontStruct>): WatcherStruct
    @JvmStatic external fun createPickerItemsWatcher(callback: WatcherCallback<Array<PickerItemStruct>>): WatcherStruct

    // ========== Watch Binding ==========

    @JvmStatic external fun watchBindingBool(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingInt(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingDouble(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingStr(bindingPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchBindingFloat(bindingPtr: Long, watcher: WatcherStruct): Long

    // ========== Watch Computed ==========

    @JvmStatic external fun watchComputedF64(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedI32(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedStyledStr(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedResolvedColor(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedResolvedFont(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedPickerItems(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedColorScheme(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun readComputedStr(computedPtr: Long): String
    @JvmStatic external fun watchComputedStr(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun dropComputedStr(computedPtr: Long)
    @JvmStatic external fun readComputedVideo(computedPtr: Long): VideoStruct
    @JvmStatic external fun watchComputedVideo(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun dropComputedVideo(computedPtr: Long)

    // ========== Dynamic Connect ==========

    @JvmStatic external fun dynamicConnect(dynamicPtr: Long, watcher: WatcherStruct)

    // ========== Reactive State Creation ==========

    @JvmStatic external fun createReactiveColorState(argb: Int): Long
    @JvmStatic external fun reactiveColorStateToComputed(statePtr: Long): Long
    @JvmStatic external fun reactiveColorStateSet(statePtr: Long, argb: Int)
    @JvmStatic external fun createReactiveFontState(size: Float, weight: Int): Long
    @JvmStatic external fun reactiveFontStateToComputed(statePtr: Long): Long
    @JvmStatic external fun reactiveFontStateSet(statePtr: Long, size: Float, weight: Int)

    // ========== Theme Functions ==========

    @JvmStatic external fun themeColorBackground(envPtr: Long): Long
    @JvmStatic external fun themeColorSurface(envPtr: Long): Long
    @JvmStatic external fun themeColorSurfaceVariant(envPtr: Long): Long
    @JvmStatic external fun themeColorBorder(envPtr: Long): Long
    @JvmStatic external fun themeColorForeground(envPtr: Long): Long
    @JvmStatic external fun themeColorMutedForeground(envPtr: Long): Long
    @JvmStatic external fun themeColorAccent(envPtr: Long): Long
    @JvmStatic external fun themeColorAccentForeground(envPtr: Long): Long
    @JvmStatic external fun themeFontBody(envPtr: Long): Long
    @JvmStatic external fun themeFontTitle(envPtr: Long): Long
    @JvmStatic external fun themeFontHeadline(envPtr: Long): Long
    @JvmStatic external fun themeFontSubheadline(envPtr: Long): Long
    @JvmStatic external fun themeFontCaption(envPtr: Long): Long
    @JvmStatic external fun themeFontFootnote(envPtr: Long): Long
    @JvmStatic external fun themeInstallColor(envPtr: Long, slot: Int, signalPtr: Long)
    @JvmStatic external fun themeInstallFont(envPtr: Long, slot: Int, signalPtr: Long)
    @JvmStatic external fun themeInstallColorScheme(envPtr: Long, signalPtr: Long)
    @JvmStatic external fun themeColor(envPtr: Long, slot: Int): Long
    @JvmStatic external fun themeFont(envPtr: Long, slot: Int): Long
    @JvmStatic external fun themeColorScheme(envPtr: Long): Long
    @JvmStatic external fun computedColorSchemeConstant(scheme: Int): Long

    // ========== Layout Functions ==========

    @JvmStatic external fun layoutSizeThatFits(layoutPtr: Long, proposal: ProposalStruct, subviews: Array<SubViewStruct>): SizeStruct
    @JvmStatic external fun layoutPlace(layoutPtr: Long, bounds: RectStruct, subviews: Array<SubViewStruct>): Array<RectStruct>

    // ========== Type ID Functions ==========

    @JvmStatic external fun emptyId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun textId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun plainId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun buttonId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun colorId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun textFieldId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun stepperId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun progressId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun dynamicId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun scrollViewId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun spacerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun toggleId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun sliderId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun fixedContainerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun pickerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun secureFieldId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun layoutContainerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataEnvId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataSecureId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataGestureId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataOnEventId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataBackgroundId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataForegroundId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataShadowId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataFocusedId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataIgnoreSafeAreaId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun metadataRetainId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun photoId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun videoId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun videoPlayerId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun mediaPickerId(): dev.waterui.android.runtime.TypeIdStruct

    // ========== Navigation Type IDs ==========

    @JvmStatic external fun navigationStackId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun navigationViewId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun tabsId(): dev.waterui.android.runtime.TypeIdStruct

    // ========== List Type IDs ==========

    @JvmStatic external fun listId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun listItemId(): dev.waterui.android.runtime.TypeIdStruct

    // ========== List Force-As Functions ==========

    @JvmStatic external fun forceAsList(viewPtr: Long): dev.waterui.android.runtime.ListStruct
    @JvmStatic external fun forceAsListItem(viewPtr: Long): dev.waterui.android.runtime.ListItemStruct

    // ========== Navigation Force-As Functions ==========

    @JvmStatic external fun forceAsNavigationStack(viewPtr: Long): NavigationStackStruct
    @JvmStatic external fun forceAsNavigationView(viewPtr: Long): NavigationViewStruct
    @JvmStatic external fun forceAsTabs(viewPtr: Long): TabsStruct
    @JvmStatic external fun tabContent(contentPtr: Long): NavigationViewStruct

    // ========== Navigation Controller Functions ==========

    @JvmStatic external fun navigationControllerNew(callback: NavigationControllerCallback): Long
    @JvmStatic external fun envInstallNavigationController(envPtr: Long, controllerPtr: Long)
    @JvmStatic external fun dropNavigationController(ptr: Long)

    // ========== OnEvent Handler Functions ==========

    @JvmStatic external fun callOnEvent(handlerPtr: Long, envPtr: Long)
    @JvmStatic external fun dropOnEvent(handlerPtr: Long)

    // ========== Retain Functions ==========

    @JvmStatic external fun dropRetain(retainPtr: Long)

    // ========== GpuSurface Functions ==========

    @JvmStatic external fun gpuSurfaceId(): dev.waterui.android.runtime.TypeIdStruct
    @JvmStatic external fun forceAsGpuSurface(viewPtr: Long): dev.waterui.android.runtime.GpuSurfaceStruct
    @JvmStatic external fun gpuSurfaceInit(rendererPtr: Long, surface: android.view.Surface, width: Int, height: Int): Long
    @JvmStatic external fun gpuSurfaceRender(statePtr: Long, width: Int, height: Int): Boolean
    @JvmStatic external fun gpuSurfaceDrop(statePtr: Long)
}
