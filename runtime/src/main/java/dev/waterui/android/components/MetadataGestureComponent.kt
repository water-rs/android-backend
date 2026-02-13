package dev.waterui.android.components

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.GestureDataStruct
import dev.waterui.android.runtime.GestureType
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataGestureTypeId: WuiTypeId by lazy {
    WatcherJni.metadataGestureId().toTypeId()
}

private fun interface MotionHandler {
    fun onTouch(event: MotionEvent)
}

private data class GestureSpec(
    val type: GestureType,
    val data: GestureDataStruct,
    val first: GestureSpec? = null,
    val second: GestureSpec? = null
)

private fun GestureType.isComposite(): Boolean =
    this == GestureType.THEN || this == GestureType.SIMULTANEOUS || this == GestureType.EXCLUSIVE

private fun decodeGestureSpec(type: Int, data: GestureDataStruct): GestureSpec {
    val gestureType = GestureType.fromInt(type)
    if (!gestureType.isComposite()) {
        return GestureSpec(type = gestureType, data = data)
    }

    val first = data.firstPtr
        .takeIf { it != 0L }
        ?.let { ptr ->
            val gesture = WatcherJni.gestureFromPtr(ptr)
            decodeGestureSpec(gesture.gestureType, gesture.gestureData)
        }

    val second = data.secondPtr
        .takeIf { it != 0L }
        ?.let { ptr ->
            val gesture = WatcherJni.gestureFromPtr(ptr)
            decodeGestureSpec(gesture.gestureType, gesture.gestureData)
        }

    return GestureSpec(
        type = gestureType,
        data = data,
        first = first,
        second = second
    )
}

private fun releaseCompositePointers(spec: GestureSpec) {
    if (!spec.type.isComposite()) {
        return
    }

    spec.data.firstPtr
        .takeIf { it != 0L }
        ?.let { ptr -> WatcherJni.dropGesture(ptr) }

    spec.data.secondPtr
        .takeIf { it != 0L }
        ?.let { ptr -> WatcherJni.dropGesture(ptr) }
}

private fun buildTapHandler(
    touchSlop: Float,
    requiredTaps: Int,
    onRecognized: () -> Unit
): MotionHandler {
    val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
    val multiTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    var downX = 0f
    var downY = 0f
    var downTime = 0L

    var tapCount = 0
    var lastTapUpTime = 0L
    var lastTapX = 0f
    var lastTapY = 0f

    return MotionHandler { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val movedTooFar = dx * dx + dy * dy > touchSlop * touchSlop
                val heldTooLong = (event.eventTime - downTime) > tapTimeout

                if (movedTooFar || heldTooLong) {
                    tapCount = 0
                    lastTapUpTime = 0L
                    return@MotionHandler
                }

                val withinInterval =
                    lastTapUpTime != 0L && (event.eventTime - lastTapUpTime) <= multiTapTimeout
                val closeToPrevious =
                    (event.x - lastTapX) * (event.x - lastTapX) +
                        (event.y - lastTapY) * (event.y - lastTapY) <= touchSlop * touchSlop

                tapCount = if (withinInterval && closeToPrevious) {
                    tapCount + 1
                } else {
                    1
                }

                if (tapCount == requiredTaps) {
                    onRecognized()
                    tapCount = 0
                    lastTapUpTime = 0L
                } else {
                    lastTapUpTime = event.eventTime
                    lastTapX = event.x
                    lastTapY = event.y
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                tapCount = 0
                lastTapUpTime = 0L
            }
        }
    }
}

private fun buildLongPressHandler(
    container: PassThroughFrameLayout,
    touchSlop: Float,
    durationMs: Long,
    onRecognized: () -> Unit
): MotionHandler {
    var downX = 0f
    var downY = 0f
    var active = false
    var fired = false
    var trigger: Runnable? = null

    fun cancelPending() {
        trigger?.let { container.removeCallbacks(it) }
        trigger = null
        active = false
    }

    return MotionHandler { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPending()
                downX = event.x
                downY = event.y
                active = true
                fired = false
                val callback = Runnable {
                    if (active && !fired) {
                        fired = true
                        onRecognized()
                    }
                }
                trigger = callback
                container.postDelayed(callback, durationMs)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!active) {
                    return@MotionHandler
                }
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                    cancelPending()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPending()
            }
        }
    }
}

private fun buildDragHandler(
    minDistancePx: Float,
    onRecognized: () -> Unit
): MotionHandler {
    val minDistanceSq = minDistancePx * minDistancePx
    var startX = 0f
    var startY = 0f
    var dragStarted = false

    return MotionHandler { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragStarted = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (!dragStarted && (dx * dx + dy * dy) < minDistanceSq) {
                    return@MotionHandler
                }
                dragStarted = true
                onRecognized()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                dragStarted = false
            }
        }
    }
}

private fun buildMagnificationHandler(
    context: android.content.Context,
    onRecognized: () -> Unit
): MotionHandler {
    val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onRecognized()
            }
        }
    )

    return MotionHandler { event ->
        scaleDetector.onTouchEvent(event)
    }
}

private fun buildRotationHandler(onRecognized: () -> Unit): MotionHandler {
    var hasTwoFingerSession = false

    return MotionHandler { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    hasTwoFingerSession = true
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (hasTwoFingerSession && event.pointerCount == 2) {
                    onRecognized()
                    hasTwoFingerSession = false
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                hasTwoFingerSession = false
            }
        }
    }
}

private fun installGesture(
    context: android.content.Context,
    container: PassThroughFrameLayout,
    spec: GestureSpec,
    handlers: MutableList<MotionHandler>,
    onRecognized: () -> Unit
) {
    when (spec.type) {
        GestureType.TAP -> {
            val requiredTaps = spec.data.tapCount.coerceAtLeast(1)
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            handlers += buildTapHandler(touchSlop, requiredTaps, onRecognized)
        }

        GestureType.LONG_PRESS -> {
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            val durationMs = spec.data.longPressDuration.coerceAtLeast(1).toLong()
            handlers += buildLongPressHandler(container, touchSlop, durationMs, onRecognized)
        }

        GestureType.DRAG -> {
            val density = context.resources.displayMetrics.density
            val minDistancePx = (spec.data.dragMinDistance * density).coerceAtLeast(0f)
            handlers += buildDragHandler(minDistancePx, onRecognized)
        }

        GestureType.MAGNIFICATION -> {
            handlers += buildMagnificationHandler(context, onRecognized)
        }

        GestureType.ROTATION -> {
            handlers += buildRotationHandler(onRecognized)
        }

        GestureType.THEN -> {
            val first = spec.first
            val second = spec.second
            if (first == null || second == null) {
                return
            }

            var armed = false
            installGesture(context, container, first, handlers) {
                armed = true
            }
            installGesture(context, container, second, handlers) {
                if (!armed) {
                    return@installGesture
                }
                armed = false
                onRecognized()
            }
        }

        GestureType.SIMULTANEOUS -> {
            val first = spec.first
            val second = spec.second
            if (first == null || second == null) {
                return
            }

            installGesture(context, container, first, handlers, onRecognized)
            installGesture(context, container, second, handlers, onRecognized)
        }

        GestureType.EXCLUSIVE -> {
            val first = spec.first
            val second = spec.second
            if (first == null || second == null) {
                return
            }

            var resolved = false
            handlers += MotionHandler { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_CANCEL -> resolved = false
                }
            }

            installGesture(context, container, first, handlers) {
                if (resolved) {
                    return@installGesture
                }
                resolved = true
                onRecognized()
            }
            installGesture(context, container, second, handlers) {
                if (resolved) {
                    return@installGesture
                }
                resolved = true
                onRecognized()
            }
        }
    }
}

/**
 * Renderer for Metadata<GestureObserver>.
 *
 * Attaches gesture recognizers to the wrapped content view.
 * Supports tap, long press, drag, pinch (magnification), rotation, and gesture compositions.
 */
private val metadataGestureRenderer = WuiRenderer { context, node, env, registry ->
    val gestureData = WatcherJni.forceAsMetadataGesture(node.rawPtr)
    val gestureSpec = decodeGestureSpec(gestureData.gestureType, gestureData.gestureData)

    val container = PassThroughFrameLayout(context).apply {
        consumesTouches = true
        setTag(PassThroughFrameLayout.TAG_WANTS_TOUCHES, true)
    }
    val envPtr = env.raw()

    if (gestureData.contentPtr != 0L) {
        val child = inflateAnyView(context, gestureData.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    val callAction = {
        WatcherJni.callAction(gestureData.actionPtr, envPtr)
    }

    val handlers = mutableListOf<MotionHandler>()
    installGesture(context, container, gestureSpec, handlers, callAction)

    container.setOnTouchListener { _, event ->
        handlers.forEach { handler -> handler.onTouch(event) }
        true
    }

    container.disposeWith {
        WatcherJni.dropAction(gestureData.actionPtr)
        releaseCompositePointers(gestureSpec)
    }

    container
}

internal fun RegistryBuilder.registerWuiGesture() {
    registerMetadata({ metadataGestureTypeId }, metadataGestureRenderer)
}
