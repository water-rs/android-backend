package dev.waterui.android.components

import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.PopupWindow
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.MetadataAnchoredOverlayStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.requireActivity
import java.io.Closeable
import kotlin.math.roundToInt

private val metadataAnchoredOverlayTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_anchored_overlay_id().toTypeId()
}

private const val DISMISSAL_OUTSIDE_INTERACTION = 1

/**
 * `AnchoredOverlay` metadata: presents the overlay content in a `PopupWindow`
 * positioned by `waterui_anchored_overlay_place` — the shared placement
 * contract — rather than a re-implementation.
 *
 * The popup is non-focusable and non-touch-modal, so a press outside it
 * dispatches straight to the view below; a `Window.Callback` wrapper observes
 * that same `ACTION_DOWN` and writes `false` into `is_presented`, so the event
 * both dismisses the overlay and still reaches its target. `Manual` overlays
 * never install the interceptor.
 */
internal class AnchoredOverlayPresentation(
    private val anchor: View,
    private val env: WuiEnvironment,
    private val overlayView: View,
    private val isPresented: WuiBinding<Boolean>,
    private val metadata: MetadataAnchoredOverlayStruct,
    private val popupFactory: (View, Int, Int, Boolean) -> PopupWindow =
        { view, width, height, focusable -> PopupWindow(view, width, height, focusable) }
) : Closeable {
    private var popup: PopupWindow? = null
    private var interceptor: Window.Callback? = null
    private var originalCallback: Window.Callback? = null
    private var followRegistration: FollowRegistration? = null
    private var isShowing = false

    fun show() {
        if (popup != null) {
            return
        }
        val placed = place() ?: return
        val popup = popupFactory(
            overlayView,
            placed.width(),
            placed.height(),
            false
        ).apply {
            // Not touch-modal: presses outside the popup's bounds dispatch to
            // the application window and reach their real target.
            // API 29+; below that the decor-view interceptor already forwards
            // outside presses to their real target.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setTouchModal(false)
            }
            elevation = 24f * anchor.resources.displayMetrics.density
        }
        this.popup = popup
        isShowing = true
        installOutsideInterceptor()
        installFollowListeners()
        popup.setOnDismissListener { isShowing = false }
        // Absolute gravity: the placement already resolved leading/trailing.
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, placed.left, placed.top)
    }

    fun dismiss() {
        uninstallOutsideInterceptor()
        uninstallFollowListeners()
        popup?.dismiss()
        popup = null
        isShowing = false
    }

    override fun close() = dismiss()

    /** The overlay's frame in screen coordinates, placed by the contract. */
    private fun place(): Rect? {
        val windowView = anchor.rootView ?: return null
        val windowOrigin = IntArray(2)
        windowView.getLocationOnScreen(windowOrigin)
        val windowW = windowView.width.toFloat()
        val windowH = windowView.height.toFloat()
        if (windowW <= 0f || windowH <= 0f) {
            return null
        }

        // The content's ideal size bounded by the window, in pixels.
        overlayView.measure(
            View.MeasureSpec.makeMeasureSpec(windowW.toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(windowH.toInt(), View.MeasureSpec.AT_MOST)
        )
        val overlayW = overlayView.measuredWidth.toFloat().coerceAtMost(windowW)
        val overlayH = overlayView.measuredHeight.toFloat().coerceAtMost(windowH)

        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val density = anchor.resources.displayMetrics.density
        val placed = NativeBindings.waterui_anchored_overlay_place(
            env.raw(),
            (anchorLoc[0] - windowOrigin[0]).toFloat(),
            (anchorLoc[1] - windowOrigin[1]).toFloat(),
            anchor.width.toFloat(),
            anchor.height.toFloat(),
            windowW,
            windowH,
            overlayW,
            overlayH,
            metadata.edge,
            metadata.alignment,
            metadata.gap * density,
            metadata.flip,
            metadata.clampTag,
            metadata.clampMargin * density
        )
        val left = windowOrigin[0] + placed.x.roundToInt()
        val top = windowOrigin[1] + placed.y.roundToInt()
        return Rect(left, top, left + placed.width.roundToInt(), top + placed.height.roundToInt())
    }

    private fun reposition() {
        val popup = popup ?: return
        val placed = place() ?: return
        popup.update(placed.left, placed.top, placed.width(), placed.height())
    }

    /**
     * Observes presses that reach the application window while the non-modal
     * popup is up — by construction those are outside the overlay — and writes
     * `false` before delegating, so the same event still reaches its target.
     */
    private fun installOutsideInterceptor() {
        if (metadata.dismissal != DISMISSAL_OUTSIDE_INTERACTION) {
            return
        }
        val window = anchor.context.requireActivity().window ?: return
        val original = window.callback ?: return
        val interceptor = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    isPresented.set(false)
                }
                return original.dispatchTouchEvent(event)
            }
        }
        window.callback = interceptor
        this.interceptor = interceptor
        this.originalCallback = original
    }

    private fun uninstallOutsideInterceptor() {
        val interceptor = interceptor ?: return
        val window = anchor.context.requireActivity().window
        if (window?.callback === interceptor) {
            window.callback = originalCallback
        }
        this.interceptor = null
        this.originalCallback = null
    }

    /** The overlay follows the anchor on relayouts and scrolls. */
    private fun installFollowListeners() {
        val root = anchor.rootView ?: return
        val listener = FollowListener { reposition() }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.viewTreeObserver.addOnScrollChangedListener(listener)
        anchor.addOnAttachStateChangeListener(listener)
        followRegistration = FollowRegistration(root, listener)
    }

    private fun uninstallFollowListeners() {
        val registration = followRegistration ?: return
        registration.root.viewTreeObserver.removeOnGlobalLayoutListener(registration.listener)
        registration.root.viewTreeObserver.removeOnScrollChangedListener(registration.listener)
        anchor.removeOnAttachStateChangeListener(registration.listener)
        followRegistration = null
    }

    private class FollowRegistration(
        val root: View,
        val listener: FollowListener
    )

    private class FollowListener(
        private val onChange: () -> Unit
    ) : ViewTreeObserver.OnGlobalLayoutListener,
        ViewTreeObserver.OnScrollChangedListener,
        View.OnAttachStateChangeListener {
        override fun onGlobalLayout() = onChange()
        override fun onScrollChanged() = onChange()
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {}
    }
}

private val metadataAnchoredOverlayRenderer = WuiRenderer { context, node, env, registry ->
    val metadata: MetadataAnchoredOverlayStruct =
        NativeBindings.waterui_force_as_metadata_anchored_overlay(node.rawPtr)
    require(metadata.contentPtr != 0L) { "MetadataAnchoredOverlay.contentPtr is null" }
    require(metadata.overlayContentPtr != 0L) { "MetadataAnchoredOverlay.overlayContentPtr is null" }
    require(metadata.isPresentedPtr != 0L) { "MetadataAnchoredOverlay.isPresentedPtr is null" }

    val child = inflateAnyView(context, metadata.contentPtr, env, registry)
    val overlay = OwnedWuiAnyView(metadata.overlayContentPtr) { ptr ->
        inflateAnyView(context, ptr, env, registry)
    }
    val isPresented = WuiBinding.bool(metadata.isPresentedPtr)

    val wrapper = PassThroughFrameLayout(context).apply {
        consumesTouches = true
        setTag(PassThroughFrameLayout.TAG_WANTS_TOUCHES, true)
        addView(child)
        disposeWith(overlay)
        disposeWith(isPresented)
    }

    var presentation: AnchoredOverlayPresentation? = null
    isPresented.observe { presented ->
        val overlayView = overlay.view()
        if (presented && overlayView != null) {
            val current = presentation ?: AnchoredOverlayPresentation(
                anchor = wrapper,
                env = env,
                overlayView = overlayView,
                isPresented = isPresented,
                metadata = metadata
            ).also { presentation = it }
            current.show()
        } else {
            presentation?.dismiss()
        }
    }
    wrapper.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            // The anchor left the tree: the overlay closes with it.
            presentation?.let {
                it.dismiss()
                isPresented.set(false)
            }
        }
    })
    wrapper.disposeWith { presentation?.close() }
    wrapper
}

internal fun RegistryBuilder.registerWuiAnchoredOverlay() {
    registerMetadata({ metadataAnchoredOverlayTypeId }, metadataAnchoredOverlayRenderer)
}
