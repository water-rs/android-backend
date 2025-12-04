package dev.waterui.android.runtime

/**
 * Data classes that mirror native FFI structs.
 * 
 * These are used for marshalling data between Kotlin and native code.
 */

const val RENDERER_BUFFER_FORMAT_RGBA8888: Int = 0

// ========== Layout Structs ==========

/**
 * Stretch axis enum values matching WuiStretchAxis in FFI.
 * Determines which axis (or axes) a view stretches to fill available space.
 */
enum class StretchAxis(val value: Int) {
    /** Content-sized, does not expand */
    NONE(0),
    /** Expands horizontally to fill available width */
    HORIZONTAL(1),
    /** Expands vertically to fill available height */
    VERTICAL(2),
    /** Expands in both directions to fill all available space */
    BOTH(3),
    /** Expands along the main axis of the parent stack (VStack: vertical, HStack: horizontal) */
    MAIN_AXIS(4),
    /** Expands along the cross axis of the parent stack (VStack: horizontal, HStack: vertical) */
    CROSS_AXIS(5);

    companion object {
        fun fromInt(value: Int): StretchAxis = entries.firstOrNull { it.value == value } ?: NONE
    }
}

data class LayoutContainerStruct(val layoutPtr: Long, val childrenPtr: Long)

data class FixedContainerStruct(val layoutPtr: Long, val childPointers: LongArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FixedContainerStruct) return false
        return layoutPtr == other.layoutPtr && childPointers.contentEquals(other.childPointers)
    }
    override fun hashCode(): Int = 31 * layoutPtr.hashCode() + childPointers.contentHashCode()
}

data class ProposalStruct(val width: Float, val height: Float)

data class SizeStruct(val width: Float, val height: Float)

data class RectStruct(val x: Float, val y: Float, val width: Float, val height: Float)

/** @deprecated Use StretchAxis enum instead of boolean stretch */
@Deprecated("Use StretchAxis enum with SubViewStruct instead", ReplaceWith("SubViewStruct"))
data class ChildMetadataStruct(val proposal: ProposalStruct, val priority: Int, val stretch: Boolean) {
    fun isStretch(): Boolean = stretch
}

/**
 * SubView metadata for the new 2-phase layout system.
 * Used with waterui_layout_size_that_fits and waterui_layout_place.
 *
 * The view reference is used by the native layer to call back into Java
 * for measuring the child view during layout negotiation.
 *
 * @param density Screen density for converting between dp (Rust) and pixels (Android).
 *                Rust layout uses density-independent points; Android uses pixels.
 */
data class SubViewStruct(
    val view: android.view.View,
    val stretchAxis: StretchAxis,
    val priority: Int = 0,
    val density: Float = 1f
) {
    /**
     * Called by native code to measure this view for a given proposal.
     * This method must be present for the JNI callback to work.
     *
     * @param proposalWidth Proposed width in dp (density-independent points)
     * @param proposalHeight Proposed height in dp (density-independent points)
     * @return Size in dp for Rust layout engine
     */
    @Suppress("unused") // Called from native code
    fun measureForLayout(proposalWidth: Float, proposalHeight: Float): SizeStruct {
        // Convert dp proposal to pixel MeasureSpec
        val widthSpec = proposalToMeasureSpec(proposalWidth * density)
        val heightSpec = proposalToMeasureSpec(proposalHeight * density)
        view.measure(widthSpec, heightSpec)
        // Convert pixel result back to dp for Rust
        return SizeStruct(view.measuredWidth.toFloat() / density, view.measuredHeight.toFloat() / density)
    }

    private fun proposalToMeasureSpec(proposalPx: Float): Int {
        return when {
            proposalPx.isNaN() -> android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            proposalPx.isInfinite() -> android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            else -> android.view.View.MeasureSpec.makeMeasureSpec(proposalPx.toInt().coerceAtLeast(0), android.view.View.MeasureSpec.AT_MOST)
        }
    }
}

// ========== Safe Area Structs ==========

/**
 * Safe area insets in points (matches Rust SafeAreaInsets)
 */
data class SafeAreaInsetsStruct(
    val top: Float,
    val bottom: Float,
    val leading: Float,
    val trailing: Float
) {
    companion object {
        val ZERO = SafeAreaInsetsStruct(0f, 0f, 0f, 0f)
    }
}

/**
 * Safe area edges bitflags (matches Rust SafeAreaEdges)
 */
data class SafeAreaEdgesStruct(val bits: Int) {
    companion object {
        val NONE = SafeAreaEdgesStruct(0)
        val TOP = SafeAreaEdgesStruct(0b0001)
        val BOTTOM = SafeAreaEdgesStruct(0b0010)
        val LEADING = SafeAreaEdgesStruct(0b0100)
        val TRAILING = SafeAreaEdgesStruct(0b1000)
        val HORIZONTAL = SafeAreaEdgesStruct(LEADING.bits or TRAILING.bits)
        val VERTICAL = SafeAreaEdgesStruct(TOP.bits or BOTTOM.bits)
        val ALL = SafeAreaEdgesStruct(HORIZONTAL.bits or VERTICAL.bits)
    }
    
    operator fun plus(other: SafeAreaEdgesStruct) = SafeAreaEdgesStruct(bits or other.bits)
    fun contains(other: SafeAreaEdgesStruct) = (bits and other.bits) == other.bits
}

/**
 * Layout context passed through the layout hierarchy (matches Rust LayoutContext)
 */
data class LayoutContextStruct(
    val safeArea: SafeAreaInsetsStruct,
    val ignoresSafeArea: SafeAreaEdgesStruct
) {
    companion object {
        val EMPTY = LayoutContextStruct(SafeAreaInsetsStruct.ZERO, SafeAreaEdgesStruct.NONE)
    }
}

/**
 * Child placement result from layout (matches Rust ChildPlacement)
 */
data class ChildPlacementStruct(
    val rect: RectStruct,
    val context: LayoutContextStruct
)

// ========== Watcher Structs ==========

/**
 * Common watcher envelope for bindings/computed values.
 * Contains pointers to the callback data, call function, and drop function.
 */
data class WatcherStruct(val dataPtr: Long, val callPtr: Long, val dropPtr: Long)

// ========== View Structs ==========

data class ButtonStruct(val labelPtr: Long, val actionPtr: Long)

data class TextStruct(val contentPtr: Long)

data class PlainStruct(val textBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlainStruct) return false
        return textBytes.contentEquals(other.textBytes)
    }
    override fun hashCode(): Int = textBytes.contentHashCode()
}

data class ColorStruct(val colorPtr: Long)

data class TextFieldStruct(val labelPtr: Long, val valuePtr: Long, val promptPtr: Long, val keyboardType: Int)

data class SecureFieldStruct(val labelPtr: Long, val valuePtr: Long)

data class ToggleStruct(val labelPtr: Long, val bindingPtr: Long)

data class SliderStruct(
    val labelPtr: Long,
    val minLabelPtr: Long,
    val maxLabelPtr: Long,
    val rangeStart: Double,
    val rangeEnd: Double,
    val bindingPtr: Long
)

data class StepperStruct(
    val bindingPtr: Long,
    val stepPtr: Long,
    val labelPtr: Long,
    val rangeStart: Int,
    val rangeEnd: Int
)

data class ProgressStruct(val labelPtr: Long, val valueLabelPtr: Long, val valuePtr: Long, val style: Int)

data class ScrollStruct(val axis: Int, val contentPtr: Long)

data class DynamicStruct(val dynamicPtr: Long)

data class PickerStruct(val itemsPtr: Long, val selectionPtr: Long)

// Alias for container struct returned by force_as_layout_container
data class ContainerStruct(val layoutPtr: Long, val contentsPtr: Long)

/**
 * Metadata<Environment> struct for WithEnv component.
 * Provides a new environment for child views.
 */
data class MetadataEnvStruct(val contentPtr: Long, val envPtr: Long)

// ========== Metadata Structs ==========

/**
 * Metadata<Secure> struct for secure view rendering.
 * Prevents screenshots and screen recording of the wrapped content.
 */
data class MetadataSecureStruct(val contentPtr: Long)

/**
 * Gesture type enum matching WuiGesture_Tag in FFI.
 */
enum class GestureType(val value: Int) {
    TAP(0),
    LONG_PRESS(1),
    DRAG(2),
    MAGNIFICATION(3),
    ROTATION(4),
    THEN(5);

    companion object {
        fun fromInt(value: Int): GestureType = entries.firstOrNull { it.value == value } ?: TAP
    }
}

/**
 * Metadata<GestureObserver> struct for gesture recognition.
 */
data class MetadataGestureStruct(
    val contentPtr: Long,
    val gestureType: Int,
    val gestureData: GestureDataStruct,
    val actionPtr: Long
)

/**
 * Gesture-specific data union.
 */
data class GestureDataStruct(
    val tapCount: Int = 1,
    val longPressDuration: Int = 500,
    val dragMinDistance: Float = 10f,
    val magnificationInitialScale: Float = 1f,
    val rotationInitialAngle: Float = 0f,
    val thenFirstPtr: Long = 0L,
    val thenSecondPtr: Long = 0L
)

/**
 * Event type enum matching WuiEvent in FFI.
 */
enum class EventType(val value: Int) {
    APPEAR(0),
    DISAPPEAR(1);

    companion object {
        fun fromInt(value: Int): EventType = entries.firstOrNull { it.value == value } ?: APPEAR
    }
}

/**
 * Metadata<OnEvent> struct for lifecycle event handlers.
 */
data class MetadataOnEventStruct(
    val contentPtr: Long,
    val eventType: Int,
    val handlerPtr: Long
)

/**
 * Background type enum matching WuiBackground_Tag in FFI.
 */
enum class BackgroundType(val value: Int) {
    COLOR(0),
    IMAGE(1);

    companion object {
        fun fromInt(value: Int): BackgroundType = entries.firstOrNull { it.value == value } ?: COLOR
    }
}

/**
 * Metadata<Background> struct for background color/image.
 */
data class MetadataBackgroundStruct(
    val contentPtr: Long,
    val backgroundType: Int,
    val colorPtr: Long,  // Used when backgroundType == COLOR
    val imagePtr: Long   // Used when backgroundType == IMAGE
)

/**
 * Metadata<ForegroundColor> struct for foreground/tint color.
 */
data class MetadataForegroundStruct(
    val contentPtr: Long,
    val colorPtr: Long
)

/**
 * Metadata<Shadow> struct for shadow effects.
 */
data class MetadataShadowStruct(
    val contentPtr: Long,
    val colorPtr: Long,
    val offsetX: Float,
    val offsetY: Float,
    val radius: Float
)

/**
 * Metadata<Focused> struct for focus state management.
 */
data class MetadataFocusedStruct(
    val contentPtr: Long,
    val bindingPtr: Long
)

/**
 * Metadata<IgnoreSafeArea> struct for safe area handling.
 */
data class MetadataIgnoreSafeAreaStruct(
    val contentPtr: Long,
    val top: Boolean,
    val bottom: Boolean,
    val leading: Boolean,
    val trailing: Boolean
)

// ========== Text Styling Structs ==========

data class StyledStrStruct(val chunks: Array<StyledChunkStruct>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StyledStrStruct) return false
        return chunks.contentEquals(other.chunks)
    }
    override fun hashCode(): Int = chunks.contentHashCode()
}

data class StyledChunkStruct(val text: String, val style: TextStyleStruct)

data class TextStyleStruct(
    val fontPtr: Long,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
    val foregroundPtr: Long,
    val backgroundPtr: Long
)

data class PickerItemStruct(val tag: Int, val label: StyledStrStruct)

// ========== Resolved Value Structs ==========

data class ResolvedColorStruct(val red: Float, val green: Float, val blue: Float, val opacity: Float)

data class ResolvedFontStruct(val size: Float, val weight: Int)

// ========== Type ID Struct ==========

/**
 * 128-bit type identifier for O(1) comparison.
 * Returned from JNI for type identification.
 */
data class TypeIdStruct(val low: Long, val high: Long) {
    /**
     * Converts to WuiTypeId for registry lookups.
     */
    fun toTypeId(): WuiTypeId = WuiTypeId(low, high)
}

