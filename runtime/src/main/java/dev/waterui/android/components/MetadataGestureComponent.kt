package dev.waterui.android.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.GestureDataStruct
import dev.waterui.android.runtime.GestureType
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

private val metadataGestureTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_gesture_id().toTypeId()
}

private data class GestureTree(
    val type: GestureType,
    val data: GestureDataStruct,
    val first: GestureTree? = null,
    val second: GestureTree? = null
)

private interface GestureTouchHandler {
    fun onTouch(event: MotionEvent)
}

private class TapGestureTouchHandler(
    context: Context,
    private val requiredTapCount: Int,
    private val onRecognized: () -> Unit
) : GestureTouchHandler {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val multiTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var isTrackingTap = false
    private var tapSeriesCount = 0
    private var downX = 0f
    private var downY = 0f
    private var lastUpX = 0f
    private var lastUpY = 0f
    private var lastUpEventTime = Long.MIN_VALUE

    override fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.eventTime - lastUpEventTime > multiTapTimeoutMs) {
                    tapSeriesCount = 0
                }
                isTrackingTap = true
                downX = event.x
                downY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTrackingTap && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    reset()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isTrackingTap) {
                    return
                }
                if (
                    tapSeriesCount > 0 &&
                    hypot(event.x - lastUpX, event.y - lastUpY) > touchSlop
                ) {
                    tapSeriesCount = 0
                }
                tapSeriesCount += 1
                lastUpX = event.x
                lastUpY = event.y
                lastUpEventTime = event.eventTime
                isTrackingTap = false

                if (tapSeriesCount == requiredTapCount) {
                    onRecognized()
                    reset()
                }
            }

            MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    private fun reset() {
        isTrackingTap = false
        tapSeriesCount = 0
        lastUpEventTime = Long.MIN_VALUE
    }
}

private class DragGestureTouchHandler(
    private val minDistance: Float,
    private val onRecognized: () -> Unit
) : GestureTouchHandler {
    private var startX = 0f
    private var startY = 0f
    private var exceededMinimumDistance = false

    override fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                exceededMinimumDistance = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!exceededMinimumDistance) {
                    exceededMinimumDistance = hypot(event.x - startX, event.y - startY) >= minDistance
                }
            }

            MotionEvent.ACTION_UP -> {
                if (exceededMinimumDistance) {
                    onRecognized()
                }
                exceededMinimumDistance = false
            }

            MotionEvent.ACTION_CANCEL -> {
                exceededMinimumDistance = false
            }
        }
    }
}

private class LongPressGestureTouchHandler(
    context: Context,
    private val durationMs: Int,
    private val onRecognized: () -> Unit
) : GestureTouchHandler {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val handler = Handler(Looper.getMainLooper())
    private val trigger = Runnable {
        if (isPressActive) {
            isPressActive = false
            onRecognized()
        }
    }
    private var isPressActive = false
    private var startX = 0f
    private var startY = 0f

    override fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isPressActive = true
                handler.removeCallbacks(trigger)
                handler.postDelayed(trigger, durationMs.toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                if (isPressActive && hypot(event.x - startX, event.y - startY) > touchSlop) {
                    cancel()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    private fun cancel() {
        isPressActive = false
        handler.removeCallbacks(trigger)
    }
}

private class ScaleGestureTouchHandler(
    context: Context,
    private val onRecognized: () -> Unit
) : GestureTouchHandler {
    private val detector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onRecognized()
            }
        }
    )

    override fun onTouch(event: MotionEvent) {
        detector.onTouchEvent(event)
    }
}

private class RotationGestureTouchHandler(
    context: Context,
    private val onRecognized: () -> Unit
) : GestureTouchHandler {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var isTracking = false
    private var hasRotated = false
    private var startAngle = 0f
    private var startRadius = 0f

    override fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL -> reset()

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isTracking = true
                    hasRotated = false
                    startAngle = currentAngle(event)
                    startRadius = currentRadius(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isTracking || event.pointerCount < 2 || hasRotated) {
                    return
                }
                val angularDelta = shortestAngleDelta(startAngle, currentAngle(event))
                val gestureRadius = max(startRadius, currentRadius(event))
                hasRotated = abs(angularDelta) * gestureRadius >= touchSlop
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (isTracking && hasRotated) {
                    onRecognized()
                }
                reset()
            }

            MotionEvent.ACTION_UP -> reset()
        }
    }

    private fun currentAngle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return atan2(dy, dx)
    }

    private fun currentRadius(event: MotionEvent): Float =
        hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0)) / 2f

    private fun shortestAngleDelta(startAngle: Float, currentAngle: Float): Float {
        var delta = currentAngle - startAngle
        while (delta > Math.PI.toFloat()) {
            delta -= (Math.PI * 2).toFloat()
        }
        while (delta < -Math.PI.toFloat()) {
            delta += (Math.PI * 2).toFloat()
        }
        return delta
    }

    private fun reset() {
        isTracking = false
        hasRotated = false
        startAngle = 0f
        startRadius = 0f
    }
}

private fun parseGestureTree(gestureType: Int, gestureData: GestureDataStruct): GestureTree {
    val type = GestureType.fromInt(gestureType)
    return when (type) {
        GestureType.THEN,
        GestureType.SIMULTANEOUS,
        GestureType.EXCLUSIVE -> GestureTree(
            type = type,
            data = gestureData,
            first = requireGestureChild(type, "first", gestureData.thenFirstPtr),
            second = requireGestureChild(type, "second", gestureData.thenSecondPtr)
        )

        GestureType.TAP,
        GestureType.LONG_PRESS,
        GestureType.DRAG,
        GestureType.MAGNIFICATION,
        GestureType.ROTATION -> GestureTree(type = type, data = gestureData)
    }
}

private fun requireGestureChild(type: GestureType, role: String, pointer: Long): GestureTree {
    check(pointer != 0L) { "${type.name} gesture missing $role child pointer" }
    val childGesture = NativeBindings.waterui_gesture_from_ptr(pointer)
    return parseGestureTree(childGesture.gestureType, childGesture.gestureData)
}

private fun rootOwnedGesturePointers(type: GestureType, gestureData: GestureDataStruct): LongArray =
    when (type) {
        GestureType.THEN,
        GestureType.SIMULTANEOUS,
        GestureType.EXCLUSIVE -> longArrayOf(
            requireGesturePointer(type, "first", gestureData.thenFirstPtr),
            requireGesturePointer(type, "second", gestureData.thenSecondPtr)
        )

        GestureType.TAP,
        GestureType.LONG_PRESS,
        GestureType.DRAG,
        GestureType.MAGNIFICATION,
        GestureType.ROTATION -> longArrayOf()
    }

private fun requireGesturePointer(type: GestureType, role: String, pointer: Long): Long {
    check(pointer != 0L) { "${type.name} gesture missing $role child pointer" }
    return pointer
}

private fun compositeTouchHandler(vararg handlers: GestureTouchHandler): GestureTouchHandler =
    object : GestureTouchHandler {
        override fun onTouch(event: MotionEvent) {
            handlers.forEach { it.onTouch(event) }
        }
    }

private fun buildGestureTouchHandler(
    context: Context,
    gestureTree: GestureTree,
    onRecognized: () -> Unit
): GestureTouchHandler =
    when (gestureTree.type) {
        GestureType.TAP -> TapGestureTouchHandler(
            context = context,
            requiredTapCount = gestureTree.data.tapCount.also {
                require(it > 0) { "tap gesture count must be positive" }
            },
            onRecognized = onRecognized
        )

        GestureType.LONG_PRESS -> LongPressGestureTouchHandler(
            context = context,
            durationMs = gestureTree.data.longPressDuration,
            onRecognized = onRecognized
        )

        GestureType.DRAG -> DragGestureTouchHandler(
            minDistance = gestureTree.data.dragMinDistance,
            onRecognized = onRecognized
        )

        GestureType.MAGNIFICATION -> ScaleGestureTouchHandler(context, onRecognized)

        GestureType.ROTATION -> RotationGestureTouchHandler(context, onRecognized)

        GestureType.THEN -> {
            val first = checkNotNull(gestureTree.first) { "THEN gesture missing first child" }
            val second = checkNotNull(gestureTree.second) { "THEN gesture missing second child" }
            var armed = false
            val firstHandler = buildGestureTouchHandler(context, first) {
                armed = true
            }
            val secondHandler = buildGestureTouchHandler(context, second) {
                if (armed) {
                    armed = false
                    onRecognized()
                }
            }
            object : GestureTouchHandler {
                override fun onTouch(event: MotionEvent) {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        armed = false
                    }
                    firstHandler.onTouch(event)
                    secondHandler.onTouch(event)
                }
            }
        }

        GestureType.SIMULTANEOUS -> {
            val first = checkNotNull(gestureTree.first) { "SIMULTANEOUS gesture missing first child" }
            val second = checkNotNull(gestureTree.second) { "SIMULTANEOUS gesture missing second child" }
            compositeTouchHandler(
                buildGestureTouchHandler(context, first, onRecognized),
                buildGestureTouchHandler(context, second, onRecognized)
            )
        }

        GestureType.EXCLUSIVE -> {
            val first = checkNotNull(gestureTree.first) { "EXCLUSIVE gesture missing first child" }
            val second = checkNotNull(gestureTree.second) { "EXCLUSIVE gesture missing second child" }
            var resolvedCurrentSequence = false
            val resolveExclusive = {
                if (!resolvedCurrentSequence) {
                    resolvedCurrentSequence = true
                    onRecognized()
                }
            }
            val firstHandler = buildGestureTouchHandler(context, first, resolveExclusive)
            val secondHandler = buildGestureTouchHandler(context, second, resolveExclusive)
            object : GestureTouchHandler {
                override fun onTouch(event: MotionEvent) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        resolvedCurrentSequence = false
                    }
                    firstHandler.onTouch(event)
                    secondHandler.onTouch(event)
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        resolvedCurrentSequence = false
                    }
                }
            }
        }
    }

private val metadataGestureRenderer = WuiRenderer { context, node, env, registry ->
    val gestureMetadata = NativeBindings.waterui_force_as_metadata_gesture(node.rawPtr)
    val rootGestureType = GestureType.fromInt(gestureMetadata.gestureType)
    val rootGestureTree = parseGestureTree(gestureMetadata.gestureType, gestureMetadata.gestureData)
    val ownedGesturePointers = rootOwnedGesturePointers(rootGestureType, gestureMetadata.gestureData)

    val container = PassThroughFrameLayout(context).apply {
        consumesTouches = true
        isClickable = true
        setTag(PassThroughFrameLayout.TAG_WANTS_TOUCHES, true)
    }.attachMetadataContent(context, gestureMetadata.contentPtr, env, registry)
    val envPtr = env.raw()

    val callAction = {
        NativeBindings.waterui_call_action(gestureMetadata.actionPtr, envPtr)
    }
    val gestureHandler = buildGestureTouchHandler(context, rootGestureTree, callAction)

    container.setOnTouchListener { _, event ->
        gestureHandler.onTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            container.performClick()
        }
        true
    }

    container.disposeWith {
        ownedGesturePointers.forEach { gesturePointer ->
            NativeBindings.waterui_drop_gesture(gesturePointer)
        }
        NativeBindings.waterui_drop_action(gestureMetadata.actionPtr)
    }

    container
}

internal fun RegistryBuilder.registerWuiGesture() {
    registerMetadata({ metadataGestureTypeId }, metadataGestureRenderer)
}
