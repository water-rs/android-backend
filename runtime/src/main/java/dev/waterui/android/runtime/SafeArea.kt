package dev.waterui.android.runtime

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.Closeable

/**
 * A container that places WaterUI content against the window's edges itself.
 *
 * Android draws edge to edge: the window reaches under the status and
 * navigation bars, and a view that wants its background to reach there too must
 * take the inset as its own padding rather than have an ancestor keep it away
 * from the edge. Only the containers that own chrome can do that — a tab bar
 * knows to grow downwards under the gesture bar, an app bar knows to grow up
 * under the status bar — so they declare it here and the root leaves them alone.
 *
 * Everything else is ordinary content with no idea where the hardware is, and
 * the root insets it exactly as before.
 */
interface WuiSafeAreaManaging {
    /**
     * Hands this container the insets no ancestor has consumed. It pads its own
     * chrome by the edges it owns and passes the rest down with
     * [applyRemainingInsets].
     */
    fun applySafeArea(insets: Insets)
}

/**
 * A view that only wraps or arranges one primary content view.
 *
 * Questions about the window root descend through these to the view that
 * actually answers them.
 */
interface WuiPrimaryContentProviding {
    val wuiPrimaryContent: View?
}

/**
 * The view that decides whether the window's root is inset, looking through the
 * metadata and layout wrappers WaterUI puts between the root and the thing the
 * application actually wrote.
 *
 * A stack answers with its base layer: the window composes overlay layers
 * (snackbars, dialogs) above the content, and a layer stacked above the content
 * never changes how the window insets it.
 */
tailrec fun resolvePrimaryContent(view: View): View {
    if (view is WuiSafeAreaManaging) {
        return view
    }
    val next = (view as? WuiPrimaryContentProviding)?.wuiPrimaryContent
        ?: (view as? ViewGroup)?.takeIf { it.childCount == 1 }?.getChildAt(0)
        ?: return view
    return resolvePrimaryContent(next)
}

/**
 * Gives [content] the insets its parent did not consume: padding when it is
 * ordinary content, and a chance to place its own chrome when it is not.
 */
fun applyRemainingInsets(content: View, insets: Insets) {
    when (val primary = resolvePrimaryContent(content)) {
        is WuiSafeAreaManaging -> primary.applySafeArea(insets)
        else -> content.setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }
}

/**
 * The window's safe area, as a signal the Rust side can read.
 *
 * Native views are placed by the backend, but the layers WaterUI lays out
 * itself — a window's snackbar and overlay hosts arrive as one Rust-laid-out
 * container — can only be inset from inside. They read this and pad themselves.
 */
class ReactiveEdgeInsetsSignal(initial: Insets, private val density: Float) : Closeable {
    private var statePtr = NativeBindings.waterui_create_reactive_edge_insets_state(
        initial.top / density,
        initial.bottom / density,
        initial.left / density,
        initial.right / density
    )
    private var computedTaken = false

    fun takeComputed(): Long {
        check(!computedTaken) { "reactive safe-area computed signal was already consumed" }
        computedTaken = true
        return NativeBindings.waterui_reactive_edge_insets_state_to_computed(requireState())
    }

    /** Republishes after a rotation, a bar appearing, or a keyboard. */
    fun setValue(insets: Insets) {
        NativeBindings.waterui_reactive_edge_insets_state_set(
            requireState(),
            insets.top / density,
            insets.bottom / density,
            insets.left / density,
            insets.right / density
        )
    }

    override fun close() {
        NativeBindings.waterui_drop_reactive_edge_insets_state(requireState())
        statePtr = 0L
    }

    private fun requireState(): Long = statePtr.also {
        check(it != 0L) { "reactive safe-area state is closed" }
    }
}

/** The bars and cutouts a WaterUI window keeps its content clear of. */
internal fun WindowInsetsCompat.waterUiSafeArea(): Insets = getInsets(
    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
)

/** Everything but [edge], for handing down to content that still needs it. */
internal fun Insets.without(edge: SafeAreaEdge): Insets = when (edge) {
    SafeAreaEdge.Top -> Insets.of(left, 0, right, bottom)
    SafeAreaEdge.Bottom -> Insets.of(left, top, right, 0)
    SafeAreaEdge.Start -> Insets.of(0, top, right, bottom)
    SafeAreaEdge.End -> Insets.of(left, top, 0, bottom)
}

internal enum class SafeAreaEdge { Top, Bottom, Start, End }

/** Pads [view] on one edge only, leaving its other padding as authored. */
internal fun View.padSafeAreaEdge(insets: Insets, edge: SafeAreaEdge) {
    when (edge) {
        SafeAreaEdge.Top -> setPadding(paddingLeft, insets.top, paddingRight, paddingBottom)
        SafeAreaEdge.Bottom -> setPadding(paddingLeft, paddingTop, paddingRight, insets.bottom)
        SafeAreaEdge.Start -> setPadding(insets.left, paddingTop, paddingRight, paddingBottom)
        SafeAreaEdge.End -> setPadding(paddingLeft, paddingTop, insets.right, paddingBottom)
    }
}

/** Asks the window to dispatch its insets again, after the tree changed. */
internal fun View.requestSafeArea() = ViewCompat.requestApplyInsets(this)
