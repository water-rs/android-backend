package dev.waterui.android.reactive

import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.ResolvedFontStruct
import dev.waterui.android.runtime.WatcherStruct

/**
 * Type descriptor that encapsulates all FFI operations for a signal type.
 * 
 * Each type knows how to read, write, watch, and drop signals of that type.
 * This enables type-safe signal creation without verbose factory methods.
 */
sealed class WuiSignalType<T>(val id: Int) {
    /** Read the current value from a computed/binding pointer */
    abstract val read: (Long) -> T
    
    /** Write a value to a binding pointer (null for read-only types) */
    abstract val write: ((Long, T) -> Unit)?
    
    /** Create a watcher struct for this type */
    abstract val createWatcher: (WatcherCallback<T>) -> WatcherStruct
    
    /** Register a watcher with a computed and return guard pointer */
    abstract val watchComputed: (Long, WatcherStruct) -> Long
    
    /** Register a watcher with a binding and return guard pointer */
    abstract val watchBinding: ((Long, WatcherStruct) -> Long)?
    
    /** Drop a computed pointer */
    abstract val dropComputed: (Long) -> Unit
    
    /** Drop a binding pointer (null for read-only types) */
    abstract val dropBinding: ((Long) -> Unit)?
    
    object WuiBool : WuiSignalType<Boolean>(0) {
        override val read: (Long) -> Boolean = WatcherJni::readBindingBool
        override val write: (Long, Boolean) -> Unit = WatcherJni::setBindingBool
        override val createWatcher = { cb: WatcherCallback<Boolean> -> 
            WatcherJni.createBoolWatcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = { _, _ -> 0L } // Not used for bool
        override val watchBinding: (Long, WatcherStruct) -> Long = WatcherJni::watchBindingBool
        override val dropComputed: (Long) -> Unit = { } // Not used
        override val dropBinding: (Long) -> Unit = WatcherJni::dropBindingBool
    }
    
    object WuiInt : WuiSignalType<Int>(1) {
        override val read: (Long) -> Int = WatcherJni::readBindingInt
        override val write: (Long, Int) -> Unit = WatcherJni::setBindingInt
        override val createWatcher = { cb: WatcherCallback<Int> -> 
            WatcherJni.createIntWatcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = WatcherJni::watchComputedI32
        override val watchBinding: (Long, WatcherStruct) -> Long = WatcherJni::watchBindingInt
        override val dropComputed: (Long) -> Unit = WatcherJni::dropComputedI32
        override val dropBinding: (Long) -> Unit = WatcherJni::dropBindingInt
    }
    
    object WuiDouble : WuiSignalType<Double>(2) {
        override val read: (Long) -> Double = WatcherJni::readBindingDouble
        override val write: (Long, Double) -> Unit = WatcherJni::setBindingDouble
        override val createWatcher = { cb: WatcherCallback<Double> -> 
            WatcherJni.createDoubleWatcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = WatcherJni::watchComputedF64
        override val watchBinding: (Long, WatcherStruct) -> Long = WatcherJni::watchBindingDouble
        override val dropComputed: (Long) -> Unit = WatcherJni::dropComputedF64
        override val dropBinding: (Long) -> Unit = WatcherJni::dropBindingDouble
    }
    
    object WuiStr : WuiSignalType<String>(3) {
        override val read: (Long) -> String = { ptr ->
            WatcherJni.readBindingStr(ptr).decodeToString()
        }
        override val write: (Long, String) -> Unit = { ptr, value ->
            WatcherJni.setBindingStr(ptr, value.encodeToByteArray())
        }
        override val createWatcher = { cb: WatcherCallback<String> -> 
            WatcherJni.createStringWatcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = { _, _ -> 0L } // Not used
        override val watchBinding: (Long, WatcherStruct) -> Long = WatcherJni::watchBindingStr
        override val dropComputed: (Long) -> Unit = { }
        override val dropBinding: (Long) -> Unit = WatcherJni::dropBindingStr
    }
    
    object WuiColor : WuiSignalType<ResolvedColorStruct>(4) {
        override val read: (Long) -> ResolvedColorStruct = WatcherJni::readComputedResolvedColor
        override val write: ((Long, ResolvedColorStruct) -> Unit)? = null // Read-only
        override val createWatcher = { cb: WatcherCallback<ResolvedColorStruct> -> 
            WatcherJni.createResolvedColorWatcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = WatcherJni::watchComputedResolvedColor
        override val watchBinding: ((Long, WatcherStruct) -> Long)? = null
        override val dropComputed: (Long) -> Unit = WatcherJni::dropComputedResolvedColor
        override val dropBinding: ((Long) -> Unit)? = null
    }
    
    object WuiFont : WuiSignalType<ResolvedFontStruct>(5) {
        override val read: (Long) -> ResolvedFontStruct = WatcherJni::readComputedResolvedFont
        override val write: ((Long, ResolvedFontStruct) -> Unit)? = null // Read-only
        override val createWatcher = { cb: WatcherCallback<ResolvedFontStruct> -> 
            WatcherJni.createResolvedFontWatcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = WatcherJni::watchComputedResolvedFont
        override val watchBinding: ((Long, WatcherStruct) -> Long)? = null
        override val dropComputed: (Long) -> Unit = WatcherJni::dropComputedResolvedFont
        override val dropBinding: ((Long) -> Unit)? = null
    }
    
    object WuiColorScheme : WuiSignalType<Int>(6) {
        // ColorScheme is represented as int (0=Light, 1=Dark)
        override val read: (Long) -> Int = WatcherJni::readComputedColorScheme
        override val write: ((Long, Int) -> Unit)? = null // Read-only
        override val createWatcher = { cb: WatcherCallback<Int> -> 
            // Use int watcher for color scheme
            WatcherJni.createIntWatcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = WatcherJni::watchComputedColorScheme
        override val watchBinding: ((Long, WatcherStruct) -> Long)? = null
        override val dropComputed: (Long) -> Unit = WatcherJni::dropComputedColorScheme
        override val dropBinding: ((Long) -> Unit)? = null
    }
}

