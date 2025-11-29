package dev.waterui.android.ffi

import dev.waterui.android.reactive.WatcherCallback
import dev.waterui.android.runtime.ChildMetadataStruct
import dev.waterui.android.runtime.PickerItemStruct
import dev.waterui.android.runtime.PlainStruct
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.RectStruct
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.ResolvedFontStruct
import dev.waterui.android.runtime.SizeStruct
import dev.waterui.android.runtime.StyledStrStruct
import dev.waterui.android.runtime.WatcherStruct

/**
 * Minimal JNI interface for watcher-related functions.
 * 
 * These functions require native-to-Java callbacks which cannot be handled
 * by JavaCPP's generated bindings. All other FFI calls should use WaterUILib directly.
 */
object WatcherJni {
    init {
        // Load the JNI helper library for callbacks
        System.loadLibrary("waterui_android")
        nativeInit()
    }

    @JvmStatic
    private external fun nativeInit()

    // ========== Watcher Creation ==========
    // These create native watcher structs that call back into Kotlin
    
    @JvmStatic
    external fun createBoolWatcher(callback: WatcherCallback<Boolean>): WatcherStruct
    
    @JvmStatic
    external fun createIntWatcher(callback: WatcherCallback<Int>): WatcherStruct
    
    @JvmStatic
    external fun createDoubleWatcher(callback: WatcherCallback<Double>): WatcherStruct
    
    @JvmStatic
    external fun createStringWatcher(callback: WatcherCallback<String>): WatcherStruct
    
    @JvmStatic
    external fun createAnyViewWatcher(callback: WatcherCallback<Long>): WatcherStruct
    
    @JvmStatic
    external fun createStyledStrWatcher(callback: WatcherCallback<StyledStrStruct>): WatcherStruct
    
    @JvmStatic
    external fun createResolvedColorWatcher(callback: WatcherCallback<ResolvedColorStruct>): WatcherStruct
    
    @JvmStatic
    external fun createResolvedFontWatcher(callback: WatcherCallback<ResolvedFontStruct>): WatcherStruct
    
    @JvmStatic
    external fun createPickerItemsWatcher(callback: WatcherCallback<Array<PickerItemStruct>>): WatcherStruct

    // ========== Watch Binding ==========
    // These register watchers on bindings and return guard handles
    
    @JvmStatic
    external fun watchBindingBool(bindingPtr: Long, watcher: WatcherStruct): Long
    
    @JvmStatic
    external fun watchBindingInt(bindingPtr: Long, watcher: WatcherStruct): Long
    
    @JvmStatic
    external fun watchBindingDouble(bindingPtr: Long, watcher: WatcherStruct): Long
    
    @JvmStatic
    external fun watchBindingStr(bindingPtr: Long, watcher: WatcherStruct): Long

    // ========== Watch Computed ==========
    // These register watchers on computed signals and return guard handles
    
    @JvmStatic
    external fun watchComputedF64(computedPtr: Long, watcher: WatcherStruct): Long
    
    @JvmStatic
    external fun watchComputedI32(computedPtr: Long, watcher: WatcherStruct): Long
    
    @JvmStatic
    external fun watchComputedStyledStr(computedPtr: Long, watcher: WatcherStruct): Long
    
    @JvmStatic
    external fun watchComputedResolvedColor(computedPtr: Long, watcher: WatcherStruct): Long
    
    @JvmStatic
    external fun watchComputedResolvedFont(computedPtr: Long, watcher: WatcherStruct): Long
    
    @JvmStatic
    external fun watchComputedPickerItems(computedPtr: Long, watcher: WatcherStruct): Long
    
    @JvmStatic
    external fun watchComputedColorScheme(computedPtr: Long, watcher: WatcherStruct): Long

    // ========== Dynamic Connect ==========
    
    @JvmStatic
    external fun dynamicConnect(dynamicPtr: Long, watcher: WatcherStruct)

    // ========== Reactive State Creation ==========
    // These create reactive states that can be updated from Kotlin
    
    @JvmStatic
    external fun createReactiveColorState(argb: Int): Long
    
    @JvmStatic
    external fun reactiveColorStateToComputed(statePtr: Long): Long
    
    @JvmStatic
    external fun reactiveColorStateSet(statePtr: Long, argb: Int)
    
    @JvmStatic
    external fun createReactiveFontState(size: Float, weight: Int): Long
    
    @JvmStatic
    external fun reactiveFontStateToComputed(statePtr: Long): Long
    
    @JvmStatic
    external fun reactiveFontStateSet(statePtr: Long, size: Float, weight: Int)

    // ========== Complex Struct Accessors ==========
    // These require JNI for proper struct conversion
    
    @JvmStatic
    external fun readComputedStyledStr(computedPtr: Long): StyledStrStruct
    
    @JvmStatic
    external fun readComputedPickerItems(computedPtr: Long): Array<PickerItemStruct>
    
    @JvmStatic
    external fun readBindingStr(bindingPtr: Long): ByteArray
    
    @JvmStatic
    external fun setBindingStr(bindingPtr: Long, bytes: ByteArray)

    // ========== String Conversion ==========
    // WuiStr is a complex struct that JavaCPP can't handle properly
    
    @JvmStatic
    external fun viewId(viewPtr: Long): String
    
    @JvmStatic
    external fun forceAsPlain(viewPtr: Long): PlainStruct

    // ========== Layout Functions ==========
    // These use WuiArray types with vtables that JavaCPP can't handle
    
    @JvmStatic
    external fun layoutPropose(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>): Array<ProposalStruct>
    
    @JvmStatic
    external fun layoutSize(layoutPtr: Long, parent: ProposalStruct, children: Array<ChildMetadataStruct>): SizeStruct
    
    @JvmStatic
    external fun layoutPlace(layoutPtr: Long, bounds: RectStruct, parent: ProposalStruct, children: Array<ChildMetadataStruct>): Array<RectStruct>

    // ========== Type ID Functions ==========
    // These return WuiStr which JavaCPP can't handle properly
    
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
}
