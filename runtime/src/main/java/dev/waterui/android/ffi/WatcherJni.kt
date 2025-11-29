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
    @JvmStatic external fun main(): Long
    @JvmStatic external fun viewBody(viewPtr: Long, envPtr: Long): Long
    @JvmStatic external fun viewId(viewPtr: Long): String
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
    @JvmStatic external fun forceAsLayoutContainer(viewPtr: Long): LayoutContainerStruct
    @JvmStatic external fun forceAsFixedContainer(viewPtr: Long): FixedContainerStruct
    @JvmStatic external fun forceAsDynamic(viewPtr: Long): Long
    @JvmStatic external fun forceAsRendererView(viewPtr: Long): Long

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
    @JvmStatic external fun dropBindingBool(bindingPtr: Long)
    @JvmStatic external fun dropBindingInt(bindingPtr: Long)
    @JvmStatic external fun dropBindingDouble(bindingPtr: Long)
    @JvmStatic external fun dropBindingStr(bindingPtr: Long)

    // ========== Computed Read/Drop ==========
    
    @JvmStatic external fun readComputedF64(computedPtr: Long): Double
    @JvmStatic external fun readComputedI32(computedPtr: Long): Int
    @JvmStatic external fun readComputedResolvedColor(computedPtr: Long): ResolvedColorStruct
    @JvmStatic external fun readComputedResolvedFont(computedPtr: Long): ResolvedFontStruct
    @JvmStatic external fun readComputedStyledStr(computedPtr: Long): StyledStrStruct
    @JvmStatic external fun readComputedPickerItems(computedPtr: Long): Array<PickerItemStruct>
    @JvmStatic external fun readComputedColorScheme(computedPtr: Long): Int
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
    @JvmStatic external fun createStringWatcher(callback: WatcherCallback<String>): WatcherStruct
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

    // ========== Watch Computed ==========
    
    @JvmStatic external fun watchComputedF64(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedI32(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedStyledStr(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedResolvedColor(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedResolvedFont(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedPickerItems(computedPtr: Long, watcher: WatcherStruct): Long
    @JvmStatic external fun watchComputedColorScheme(computedPtr: Long, watcher: WatcherStruct): Long

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
    
    @JvmStatic external fun layoutPropose(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>): Array<ProposalStruct>
    @JvmStatic external fun layoutSize(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>): SizeStruct
    @JvmStatic external fun layoutPlace(layoutPtr: Long, bounds: RectStruct, parent: ProposalStruct, children: Array<ChildMetadataStruct>): Array<RectStruct>

    // ========== Type ID Functions ==========
    
    @JvmStatic external fun emptyId(): String
    @JvmStatic external fun textId(): String
    @JvmStatic external fun plainId(): String
    @JvmStatic external fun buttonId(): String
    @JvmStatic external fun colorId(): String
    @JvmStatic external fun textFieldId(): String
    @JvmStatic external fun stepperId(): String
    @JvmStatic external fun progressId(): String
    @JvmStatic external fun dynamicId(): String
    @JvmStatic external fun scrollViewId(): String
    @JvmStatic external fun spacerId(): String
    @JvmStatic external fun toggleId(): String
    @JvmStatic external fun sliderId(): String
    @JvmStatic external fun rendererViewId(): String
    @JvmStatic external fun fixedContainerId(): String
    @JvmStatic external fun pickerId(): String
    @JvmStatic external fun layoutContainerId(): String

    // ========== Renderer View ==========
    
    @JvmStatic external fun rendererViewWidth(handle: Long): Float
    @JvmStatic external fun rendererViewHeight(handle: Long): Float
    @JvmStatic external fun rendererViewPreferredFormat(handle: Long): Int
    @JvmStatic external fun rendererViewRenderCpu(handle: Long, pixels: ByteArray, width: Int, height: Int, stride: Int, format: Int): Boolean
    @JvmStatic external fun dropRendererView(handle: Long)
}
