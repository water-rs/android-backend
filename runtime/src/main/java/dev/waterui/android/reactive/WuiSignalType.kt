package dev.waterui.android.reactive

import dev.waterui.android.runtime.NativeBindings
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
        override val read: (Long) -> Boolean = NativeBindings::waterui_read_binding_bool
        override val write: (Long, Boolean) -> Unit = NativeBindings::waterui_set_binding_bool
        override val createWatcher = { cb: WatcherCallback<Boolean> -> 
            NativeBindings.waterui_create_bool_watcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = { _, _ -> 0L } // Not used for bool
        override val watchBinding: (Long, WatcherStruct) -> Long = NativeBindings::waterui_watch_binding_bool
        override val dropComputed: (Long) -> Unit = { } // Not used
        override val dropBinding: (Long) -> Unit = NativeBindings::waterui_drop_binding_bool
    }
    
    object WuiInt : WuiSignalType<Int>(1) {
        override val read: (Long) -> Int = NativeBindings::waterui_read_binding_int
        override val write: (Long, Int) -> Unit = NativeBindings::waterui_set_binding_int
        override val createWatcher = { cb: WatcherCallback<Int> -> 
            NativeBindings.waterui_create_int_watcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = NativeBindings::waterui_watch_computed_i32
        override val watchBinding: (Long, WatcherStruct) -> Long = NativeBindings::waterui_watch_binding_int
        override val dropComputed: (Long) -> Unit = NativeBindings::waterui_drop_computed_i32
        override val dropBinding: (Long) -> Unit = NativeBindings::waterui_drop_binding_int
    }
    
    object WuiDouble : WuiSignalType<Double>(2) {
        override val read: (Long) -> Double = NativeBindings::waterui_read_binding_double
        override val write: (Long, Double) -> Unit = NativeBindings::waterui_set_binding_double
        override val createWatcher = { cb: WatcherCallback<Double> -> 
            NativeBindings.waterui_create_double_watcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = NativeBindings::waterui_watch_computed_f64
        override val watchBinding: (Long, WatcherStruct) -> Long = NativeBindings::waterui_watch_binding_double
        override val dropComputed: (Long) -> Unit = NativeBindings::waterui_drop_computed_f64
        override val dropBinding: (Long) -> Unit = NativeBindings::waterui_drop_binding_double
    }
    
    object WuiStr : WuiSignalType<String>(3) {
        override val read: (Long) -> String = { ptr ->
            NativeBindings.waterui_read_binding_str(ptr).decodeToString()
        }
        override val write: (Long, String) -> Unit = { ptr, value ->
            NativeBindings.waterui_set_binding_str(ptr, value.encodeToByteArray())
        }
        override val createWatcher = { cb: WatcherCallback<String> -> 
            NativeBindings.waterui_create_string_watcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = { _, _ -> 0L } // Not used
        override val watchBinding: (Long, WatcherStruct) -> Long = NativeBindings::waterui_watch_binding_str
        override val dropComputed: (Long) -> Unit = { }
        override val dropBinding: (Long) -> Unit = NativeBindings::waterui_drop_binding_str
    }
    
    object WuiColor : WuiSignalType<ResolvedColorStruct>(4) {
        override val read: (Long) -> ResolvedColorStruct = NativeBindings::waterui_read_computed_resolved_color
        override val write: ((Long, ResolvedColorStruct) -> Unit)? = null // Read-only
        override val createWatcher = { cb: WatcherCallback<ResolvedColorStruct> -> 
            NativeBindings.waterui_create_resolved_color_watcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = NativeBindings::waterui_watch_computed_resolved_color
        override val watchBinding: ((Long, WatcherStruct) -> Long)? = null
        override val dropComputed: (Long) -> Unit = NativeBindings::waterui_drop_computed_resolved_color
        override val dropBinding: ((Long) -> Unit)? = null
    }
    
    object WuiFont : WuiSignalType<ResolvedFontStruct>(5) {
        override val read: (Long) -> ResolvedFontStruct = NativeBindings::waterui_read_computed_resolved_font
        override val write: ((Long, ResolvedFontStruct) -> Unit)? = null // Read-only
        override val createWatcher = { cb: WatcherCallback<ResolvedFontStruct> -> 
            NativeBindings.waterui_create_resolved_font_watcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = NativeBindings::waterui_watch_computed_resolved_font
        override val watchBinding: ((Long, WatcherStruct) -> Long)? = null
        override val dropComputed: (Long) -> Unit = NativeBindings::waterui_drop_computed_resolved_font
        override val dropBinding: ((Long) -> Unit)? = null
    }
    
    object WuiColorScheme : WuiSignalType<Int>(6) {
        // ColorScheme is represented as int (0=Light, 1=Dark)
        override val read: (Long) -> Int = NativeBindings::waterui_read_computed_color_scheme
        override val write: ((Long, Int) -> Unit)? = null // Read-only
        override val createWatcher = { cb: WatcherCallback<Int> -> 
            // Use int watcher for color scheme
            NativeBindings.waterui_create_int_watcher(cb)
        }
        override val watchComputed: (Long, WatcherStruct) -> Long = NativeBindings::waterui_watch_computed_color_scheme
        override val watchBinding: ((Long, WatcherStruct) -> Long)? = null
        override val dropComputed: (Long) -> Unit = NativeBindings::waterui_drop_computed_color_scheme
        override val dropBinding: ((Long) -> Unit)? = null
    }
}

