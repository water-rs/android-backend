package dev.waterui.android.components

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import dev.waterui.android.runtime.GestureType
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataGestureTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_gesture_id().toTypeId()
}

/**
 * Renderer for Metadata<GestureObserver>.
 *
 * Attaches gesture recognizers to the wrapped content view.
 * Supports tap, long press, drag, pinch (magnification), and rotation gestures.
 */
private val metadataGestureRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_metadata_gesture_id()
    val gestureData = NativeBindings.waterui_force_as_metadata_gesture(node.rawPtr)

    val container = FrameLayout(context)
    val envPtr = env.raw()

    // Inflate the content
    if (gestureData.contentPtr != 0L) {
        val child = inflateAnyView(context, gestureData.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Create action callback
    val callAction = {
        NativeBindings.waterui_call_action(gestureData.actionPtr, envPtr)
    }

    // Attach gesture recognizer based on type
    when (GestureType.fromInt(gestureData.gestureType)) {
        GestureType.TAP -> {
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    callAction()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (gestureData.gestureData.tapCount >= 2) {
                        callAction()
                        return true
                    }
                    return false
                }
            })

            container.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }

        GestureType.LONG_PRESS -> {
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    callAction()
                }
            })

            container.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }

        GestureType.DRAG -> {
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    // Call action when drag exceeds minimum distance
                    callAction()
                    return true
                }
            })

            container.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }

        GestureType.MAGNIFICATION -> {
            val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    callAction()
                }
            })

            container.setOnTouchListener { _, event ->
                scaleDetector.onTouchEvent(event)
                true
            }
        }

        GestureType.ROTATION -> {
            // Android doesn't have a built-in rotation gesture detector
            // For now, we use a simple two-finger touch detection
            container.setOnTouchListener { _, event ->
                if (event.pointerCount == 2) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_POINTER_UP -> {
                            callAction()
                        }
                    }
                }
                true
            }
        }

        GestureType.THEN -> {
            // For compound gestures, attach a simple tap for now
            // Full implementation would chain gesture states
            container.setOnClickListener {
                callAction()
            }
        }
    }

    // Cleanup
    container.disposeWith {
        if (gestureData.contentPtr != 0L) {
            NativeBindings.waterui_drop_anyview(gestureData.contentPtr)
        }
        NativeBindings.waterui_drop_action(gestureData.actionPtr)
    }

    container
}

internal fun RegistryBuilder.registerWuiGesture() {
    registerMetadata({ metadataGestureTypeId }, metadataGestureRenderer)
}
