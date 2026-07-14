package dev.waterui.android.runtime

import android.view.View
import android.view.ViewGroup
import dev.waterui.android.reactive.WuiBinding
import java.io.Closeable

private val TAG_WUI_FOCUS_TARGET: Int get() = R.id.wui_focus_target

internal interface WuiFocusTarget {
    val view: View

    fun requestPlatformFocus()

    fun clearPlatformFocus()

    fun observePlatformFocusChanges(onChange: (Boolean) -> Unit): Closeable
}

internal class WuiTextInputFocusTarget(
    override val view: View
) : WuiFocusTarget, View.OnFocusChangeListener {
    private val observers = LinkedHashMap<Int, (Boolean) -> Unit>()
    private var nextObserverId = 0

    init {
        view.onFocusChangeListener = this
    }

    override fun requestPlatformFocus() {
        view.requestFocus()
    }

    override fun clearPlatformFocus() {
        view.clearFocus()
    }

    override fun observePlatformFocusChanges(onChange: (Boolean) -> Unit): Closeable {
        val observerId = nextObserverId
        nextObserverId += 1
        observers[observerId] = onChange
        return Closeable {
            observers.remove(observerId)
        }
    }

    override fun onFocusChange(view: View, hasFocus: Boolean) {
        observers.values.forEach { observer ->
            observer(hasFocus)
        }
    }
}

internal class WuiFocusedBindingController(
    private val container: View,
    private val focusTarget: WuiFocusTarget,
    private val binding: WuiBinding<Boolean>
) : Closeable {
    private var requestedFocus = false
    private val nativeFocusObserver = focusTarget.observePlatformFocusChanges { hasFocus ->
        if (requestedFocus != hasFocus) {
            binding.set(hasFocus)
        }
    }

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            syncRequestedFocusState()
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    init {
        container.addOnAttachStateChangeListener(attachListener)
        binding.observe { value ->
            requestedFocus = value
            syncRequestedFocusState()
        }
    }

    override fun close() {
        container.removeOnAttachStateChangeListener(attachListener)
        nativeFocusObserver.close()
        binding.close()
    }

    private fun syncRequestedFocusState() {
        if (!container.isAttachedToWindow) {
            return
        }
        if (requestedFocus) {
            focusTarget.requestPlatformFocus()
        } else {
            focusTarget.clearPlatformFocus()
        }
    }
}

internal fun View.installWuiFocusTarget(target: WuiFocusTarget) {
    setTag(TAG_WUI_FOCUS_TARGET, target)
}

internal fun View.requireSingleWuiFocusTarget(): WuiFocusTarget {
    val targets = ArrayList<WuiFocusTarget>(2)
    collectWuiFocusTargetsInto(targets)
    return when (targets.size) {
        1 -> targets.single()
        0 -> error("Metadata<Focused> requires exactly one TextField or SecureField focus anchor in its subtree, found 0.")
        else -> error("Metadata<Focused> requires exactly one TextField or SecureField focus anchor in its subtree, found ${targets.size}.")
    }
}

private fun View.collectWuiFocusTargetsInto(targets: MutableList<WuiFocusTarget>) {
    (getTag(TAG_WUI_FOCUS_TARGET) as? WuiFocusTarget)?.let(targets::add)
    if (this !is ViewGroup) {
        return
    }
    for (index in 0 until childCount) {
        getChildAt(index).collectWuiFocusTargetsInto(targets)
    }
}
