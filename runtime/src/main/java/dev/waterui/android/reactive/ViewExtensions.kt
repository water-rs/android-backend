package dev.waterui.android.reactive

import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.CompoundButton
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.applyRustAnimation

/**
 * Extension functions for binding WaterUI signals to Android views.
 * 
 * These provide idiomatic Kotlin APIs for connecting reactive signals
 * to view properties, with automatic cleanup when views are detached.
 */

/**
 * Binds a computed signal to a view, calling the block whenever the value changes.
 * The binding is automatically disposed when the view is detached.
 * 
 * @param view The view to bind to
 * @param block Called with the view and new value whenever the signal changes
 * @return A disposable that can be used to manually stop observing
 */
fun <T> WuiComputed<T>.bindTo(view: View, block: View.(T) -> Unit): WuiDisposable {
    // Initial value
    view.block(current())
    
    // Observe changes
    observe { value ->
        view.block(value)
    }
    
    // Auto-dispose when view is detached
    val disposable = object : WuiDisposable {
        override fun dispose() {
            close()
        }
    }
    
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            disposable.dispose()
            view.removeOnAttachStateChangeListener(this)
        }
    })
    
    return disposable
}

/**
 * Binds a computed signal to a view with animation support.
 * 
 * @param view The view to bind to
 * @param block Called with the view, new value, and animation metadata
 * @return A disposable that can be used to manually stop observing
 */
fun <T> WuiComputed<T>.animateTo(view: View, block: View.(T, WuiAnimation) -> Unit): WuiDisposable {
    // Initial value (no animation)
    view.block(current(), WuiAnimation.NONE)
    
    // Observe changes with animation
    observeWithAnimation { value, animation ->
        view.applyRustAnimation(animation) {
            view.block(value, animation)
        }
    }
    
    // Auto-dispose when view is detached
    val disposable = object : WuiDisposable {
        override fun dispose() {
            close()
        }
    }
    
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            disposable.dispose()
            view.removeOnAttachStateChangeListener(this)
        }
    })
    
    return disposable
}

/**
 * Two-way binds a string binding to an EditText.
 * Changes from Rust update the EditText, and user edits update the binding.
 * 
 * @param editText The EditText to bind to
 * @return A disposable that can be used to manually stop the binding
 */
fun WuiBinding<String>.bindText(editText: EditText): WuiDisposable {
    var isUpdating = false
    
    // Observe Rust -> EditText
    observe { value ->
        if (!isUpdating && editText.text.toString() != value) {
            isUpdating = true
            editText.setText(value)
            editText.setSelection(value.length)
            isUpdating = false
        }
    }
    
    // EditText -> Rust
    editText.doAfterTextChanged { text ->
        if (!isUpdating) {
            isUpdating = true
            set(text?.toString() ?: "")
            isUpdating = false
        }
    }
    
    // Auto-dispose when view is detached
    val disposable = object : WuiDisposable {
        override fun dispose() {
            close()
        }
    }
    
    editText.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            disposable.dispose()
            editText.removeOnAttachStateChangeListener(this)
        }
    })
    
    return disposable
}

/**
 * Two-way binds a boolean binding to a Switch (or any CompoundButton).
 * Changes from Rust update the switch state, and user toggles update the binding.
 * 
 * @param switch The Switch to bind to
 * @return A disposable that can be used to manually stop the binding
 */
fun WuiBinding<Boolean>.bindChecked(switch: CompoundButton): WuiDisposable {
    var isUpdating = false
    
    // Observe Rust -> Switch
    observe { value ->
        if (!isUpdating && switch.isChecked != value) {
            isUpdating = true
            switch.isChecked = value
            isUpdating = false
        }
    }
    
    // Switch -> Rust
    switch.setOnCheckedChangeListener { _, isChecked ->
        if (!isUpdating) {
            isUpdating = true
            set(isChecked)
            isUpdating = false
        }
    }
    
    // Auto-dispose when view is detached
    val disposable = object : WuiDisposable {
        override fun dispose() {
            close()
            switch.setOnCheckedChangeListener(null)
        }
    }
    
    switch.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            disposable.dispose()
            switch.removeOnAttachStateChangeListener(this)
        }
    })
    
    return disposable
}

/**
 * Attaches a signal to a view for lifecycle management.
 * The signal will be closed when the view is detached.
 * 
 * This is useful when you want to keep a signal alive as long as the view exists.
 */
fun <T> WuiComputed<T>.attachTo(view: View) {
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            close()
            view.removeOnAttachStateChangeListener(this)
        }
    })
}

/**
 * Attaches a binding to a view for lifecycle management.
 * The binding will be closed when the view is detached.
 */
fun <T> WuiBinding<T>.attachTo(view: View) {
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            close()
            view.removeOnAttachStateChangeListener(this)
        }
    })
}

