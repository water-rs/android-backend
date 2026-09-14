package dev.waterui.android.runtime

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.Closeable

/**
 * A view that places WaterUI content against the window's edges itself.
 *
 * Android draws edge to edge: the window reaches under the status and
 * navigation bars, and a view that wants to reach there too must take the
 * inset itself rather than have an ancestor keep it away from the edge. The
 * containers that own chrome do — a tab bar grows downwards under the gesture
 * bar, an app bar grows up under the status bar — and so do the views that
 * scroll: a scroll surface turns the inset into padding its content scrolls
 * under, and a stack lays its children out inside the inset and extends the
 * ones that handle it themselves to the edges they touch. That is the
 * platform rule: a scroll view beside a backdrop reaches the chrome, the
 * backdrop stays inside.
 *
 * Everything else is a leaf with no idea where the hardware is, and whoever
 * holds it pads it ([applyRemainingInsets]).
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
 * The view that decides how [applyRemainingInsets] hands insets to [view],
 * looking through the metadata wrappers WaterUI puts between a holder and the
 * thing the application actually wrote.
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

/** Whether [view] places its content against the safe area itself. */
fun handlesSafeArea(view: View): Boolean = resolvePrimaryContent(view) is WuiSafeAreaManaging

/**
 * The safe-area signal this backend installs in the environment.
 *
 * It stays at zero: the root lays its content out against the window's
 * insets, and every stack below it insets its own content and extends the
 * scroll surfaces and chrome that touch its edges (see [WuiSafeAreaManaging]),
 * so the insets are applied natively and the layers WaterUI lays out itself —
 * a window's snackbar and overlay hosts — must not pad themselves again. The
 * face stays because the contract is shared with every native backend.
 */
class ReactiveEdgeInsetsSignal : Closeable {
    private var statePtr = NativeBindings.waterui_create_reactive_edge_insets_state(0f, 0f, 0f, 0f)
    private var computedTaken = false

    fun takeComputed(): Long {
        check(!computedTaken) { "reactive safe-area computed signal was already consumed" }
        computedTaken = true
        return NativeBindings.waterui_reactive_edge_insets_state_to_computed(requireState())
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
