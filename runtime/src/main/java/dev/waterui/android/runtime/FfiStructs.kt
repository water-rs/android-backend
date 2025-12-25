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

data class ButtonStruct(val labelPtr: Long, val actionPtr: Long, val style: Int)

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

/**
 * Toggle style enum matching WuiToggleStyle in FFI.
 */
enum class ToggleStyle(val value: Int) {
    /** Platform decides the style (default) */
    AUTOMATIC(0),
    /** Explicit switch toggle */
    SWITCH(1),
    /** Checkbox style */
    CHECKBOX(2);

    companion object {
        fun fromInt(value: Int): ToggleStyle = entries.firstOrNull { it.value == value } ?: AUTOMATIC
    }
}

data class ToggleStruct(val labelPtr: Long, val bindingPtr: Long, val style: Int) {
    fun toggleStyle(): ToggleStyle = ToggleStyle.fromInt(style)
}

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

/**
 * Date value struct matching WuiDate in FFI.
 * Uses year/month/day representation.
 */
data class DateStruct(
    val year: Int,
    val month: Int,
    val day: Int
)

/**
 * Date range struct matching WuiRange_WuiDate in FFI.
 */
data class DateRangeStruct(
    val start: DateStruct,
    val end: DateStruct
)

/**
 * DatePicker type enum matching WuiDatePickerType in FFI.
 */
enum class DatePickerType(val value: Int) {
    DATE(0),
    HOUR_AND_MINUTE(1),
    HOUR_MINUTE_AND_SECOND(2),
    DATE_HOUR_AND_MINUTE(3),
    DATE_HOUR_MINUTE_AND_SECOND(4);

    companion object {
        fun fromInt(value: Int): DatePickerType = entries.firstOrNull { it.value == value } ?: DATE_HOUR_AND_MINUTE
    }
}

/**
 * DatePicker component data.
 */
data class DatePickerStruct(
    val labelPtr: Long,
    val valuePtr: Long,
    val range: DateRangeStruct,
    val pickerType: Int
) {
    fun type(): DatePickerType = DatePickerType.fromInt(pickerType)
}

data class ProgressStruct(val labelPtr: Long, val valueLabelPtr: Long, val valuePtr: Long, val style: Int)

data class ScrollStruct(val axis: Int, val contentPtr: Long)

data class DynamicStruct(val dynamicPtr: Long)

data class PickerStruct(val itemsPtr: Long, val selectionPtr: Long)

/**
 * ColorPicker component data.
 * - labelPtr: AnyView pointer for label
 * - valuePtr: Binding<Color> pointer
 * - supportAlpha: Whether to allow alpha channel selection
 * - supportHdr: Whether to allow HDR color selection
 */
data class ColorPickerStruct(
    val labelPtr: Long,
    val valuePtr: Long,
    val supportAlpha: Boolean,
    val supportHdr: Boolean
)

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
 * Metadata<StandardDynamicRange> struct for SDR rendering.
 */
data class MetadataStandardDynamicRangeStruct(val contentPtr: Long)

/**
 * Metadata<HighDynamicRange> struct for HDR rendering.
 */
data class MetadataHighDynamicRangeStruct(val contentPtr: Long)

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
 * Note: No default values - JNI requires explicit constructor signature (IIFFFFJJ)V
 */
data class GestureDataStruct(
    val tapCount: Int,
    val longPressDuration: Int,
    val dragMinDistance: Float,
    val magnificationInitialScale: Float,
    val rotationInitialAngle: Float,
    val thenFirstPtr: Long,
    val thenSecondPtr: Long
)

/**
 * Lifecycle type enum matching WuiLifeCycle in FFI.
 */
enum class LifeCycleType(val value: Int) {
    APPEAR(0),
    DISAPPEAR(1);

    companion object {
        fun fromInt(value: Int): LifeCycleType = entries.firstOrNull { it.value == value } ?: APPEAR
    }
}

/**
 * Event type enum matching WuiEvent in FFI.
 * Used for repeatable interaction events like hover.
 */
enum class EventType(val value: Int) {
    HOVER_ENTER(0),
    HOVER_EXIT(1);

    companion object {
        fun fromInt(value: Int): EventType = entries.firstOrNull { it.value == value } ?: HOVER_ENTER
    }
}

/**
 * Cursor style enum matching WuiCursorStyle in FFI.
 * Maps to Android PointerIcon types (API 24+).
 */
enum class CursorStyle(val value: Int) {
    ARROW(0),
    POINTING_HAND(1),
    IBEAM(2),
    CROSSHAIR(3),
    OPEN_HAND(4),
    CLOSED_HAND(5),
    NOT_ALLOWED(6),
    RESIZE_LEFT(7),
    RESIZE_RIGHT(8),
    RESIZE_UP(9),
    RESIZE_DOWN(10),
    RESIZE_LEFT_RIGHT(11),
    RESIZE_UP_DOWN(12),
    MOVE(13),
    WAIT(14),
    COPY(15);

    companion object {
        fun fromInt(value: Int): CursorStyle = entries.firstOrNull { it.value == value } ?: ARROW
    }
}

/**
 * Metadata<LifeCycleHook> struct for lifecycle event handlers.
 * The handler is called once (FnOnce) when the lifecycle event occurs.
 */
data class MetadataLifeCycleHookStruct(
    val contentPtr: Long,
    val lifecycleType: Int,
    val handlerPtr: Long
)

/**
 * Metadata<OnEvent> struct for interaction event handlers.
 * The handler can be called multiple times (Fn) when the event occurs.
 */
data class MetadataOnEventStruct(
    val contentPtr: Long,
    val eventType: Int,
    val handlerPtr: Long
)

/**
 * Metadata<Cursor> struct for cursor style.
 * Contains a Computed<CursorStyle> for reactive cursor updates.
 */
data class MetadataCursorStruct(
    val contentPtr: Long,
    val stylePtr: Long
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
 * Metadata<Border> struct for border effects.
 * Contains border color, width, corner radius, and which edges to draw.
 */
data class MetadataBorderStruct(
    val contentPtr: Long,
    val colorPtr: Long,
    val width: Float,
    val cornerRadius: Float,
    val top: Boolean,
    val leading: Boolean,
    val bottom: Boolean,
    val trailing: Boolean
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

/**
 * Metadata<Retain> struct for keeping values alive.
 * The retainPtr is opaque - we just hold onto it and drop it when disposed.
 */
data class MetadataRetainStruct(
    val contentPtr: Long,
    val retainPtr: Long
)

/**
 * Metadata<Scale> struct for scale transforms.
 * Contains computed f32 pointers for scale and anchor values.
 */
data class MetadataScaleStruct(
    val contentPtr: Long,
    val scaleXPtr: Long,
    val scaleYPtr: Long,
    val anchorX: Float,
    val anchorY: Float
)

/**
 * Metadata<Rotation> struct for rotation transforms.
 * Contains computed f32 pointer for rotation and anchor values.
 */
data class MetadataRotationStruct(
    val contentPtr: Long,
    val anglePtr: Long,
    val anchorX: Float,
    val anchorY: Float
)

/**
 * Metadata<Offset> struct for offset transforms.
 * Contains computed f32 pointers for translation.
 */
data class MetadataOffsetStruct(
    val contentPtr: Long,
    val offsetXPtr: Long,
    val offsetYPtr: Long
)

// ========== Filter Metadata Structs ==========

/**
 * Metadata<Blur> struct for blur filter.
 * Contains computed f32 pointer for blur radius.
 */
data class MetadataBlurStruct(
    val contentPtr: Long,
    val radiusPtr: Long
)

/**
 * Metadata<Brightness> struct for brightness filter.
 * Contains computed f32 pointer for brightness amount.
 */
data class MetadataBrightnessStruct(
    val contentPtr: Long,
    val amountPtr: Long
)

/**
 * Metadata<Saturation> struct for saturation filter.
 * Contains computed f32 pointer for saturation amount.
 */
data class MetadataSaturationStruct(
    val contentPtr: Long,
    val amountPtr: Long
)

/**
 * Metadata<Contrast> struct for contrast filter.
 * Contains computed f32 pointer for contrast amount.
 */
data class MetadataContrastStruct(
    val contentPtr: Long,
    val amountPtr: Long
)

/**
 * Metadata<HueRotation> struct for hue rotation filter.
 * Contains computed f32 pointer for rotation angle.
 */
data class MetadataHueRotationStruct(
    val contentPtr: Long,
    val anglePtr: Long
)

/**
 * Metadata<Grayscale> struct for grayscale filter.
 * Contains computed f32 pointer for grayscale intensity.
 */
data class MetadataGrayscaleStruct(
    val contentPtr: Long,
    val intensityPtr: Long
)

/**
 * Metadata<Opacity> struct for opacity filter.
 * Contains computed f32 pointer for opacity value.
 */
data class MetadataOpacityStruct(
    val contentPtr: Long,
    val valuePtr: Long
)

// ========== Path and ClipShape Structs ==========

/**
 * Path command type enum matching WuiPathCommand_Tag in FFI.
 */
enum class PathCommandType(val value: Int) {
    MOVE_TO(0),
    LINE_TO(1),
    QUAD_TO(2),
    CUBIC_TO(3),
    ARC(4),
    CLOSE(5);

    companion object {
        fun fromValue(value: Int): PathCommandType =
            entries.find { it.value == value } ?: CLOSE
    }
}

/**
 * Path command struct matching WuiPathCommand in FFI.
 * Contains all possible coordinates for different command types.
 */
data class PathCommandStruct(
    val tag: Int,
    // MoveTo / LineTo: x, y
    val x: Float,
    val y: Float,
    // QuadTo: cx, cy (control), x, y (end)
    val cx: Float,
    val cy: Float,
    // CubicTo: c1x, c1y (control1), c2x, c2y (control2), x, y (end)
    val c1x: Float,
    val c1y: Float,
    val c2x: Float,
    val c2y: Float,
    // Arc: cx, cy (center), rx, ry (radii), start, sweep (angles)
    val rx: Float,
    val ry: Float,
    val start: Float,
    val sweep: Float
) {
    val type: PathCommandType get() = PathCommandType.fromValue(tag)
}

/**
 * Metadata<ClipShape> struct for view clipping.
 * Contains the content view and an array of path commands defining the clip shape.
 */
data class MetadataClipShapeStruct(
    val contentPtr: Long,
    val commands: Array<PathCommandStruct>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetadataClipShapeStruct) return false
        if (contentPtr != other.contentPtr) return false
        return commands.contentEquals(other.commands)
    }
    override fun hashCode(): Int {
        var result = contentPtr.hashCode()
        result = 31 * result + commands.contentHashCode()
        return result
    }
}

/**
 * MenuItem struct for context menu items.
 * Contains a label (styled text pointer) and an action pointer.
 */
data class MenuItemStruct(
    val labelPtr: Long,
    val actionPtr: Long
)

/**
 * Metadata<ContextMenu> struct for context menu.
 * Contains the content view and a computed list of menu items.
 */
data class MetadataContextMenuStruct(
    val contentPtr: Long,
    val itemsPtr: Long
)

/**
 * Menu component struct for dropdown menus.
 * Contains a label view and a computed list of menu items.
 */
data class MenuStruct(
    val labelPtr: Long,
    val itemsPtr: Long
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

// ========== Photo Structs ==========

/**
 * Photo component data.
 * - source: URL of the image to display
 */
data class PhotoStruct(val source: String)

// ========== Video Structs ==========

/**
 * Video source struct with URL (used for Computed<Video>).
 */
data class VideoStruct(val url: String)

/**
 * Video (raw) component data - video without native controls.
 * - sourcePtr: Computed<Str> pointer for reactive URL
 * - volumePtr: Binding<Volume> pointer (f32)
 * - aspectRatio: 0=Fit, 1=Fill, 2=Stretch
 * - loops: Whether to loop playback
 * - showControls: Always false for raw video
 */
data class VideoStruct2(
    val sourcePtr: Long,
    val volumePtr: Long,
    val aspectRatio: Int,
    val loops: Boolean,
    val showControls: Boolean
)

/**
 * VideoPlayer component data - video with native controls.
 * - sourcePtr: Computed<Str> pointer for reactive URL (note: NOT Computed<Video>)
 * - volumePtr: Binding<Volume> pointer (f32)
 * - aspectRatio: 0=Fit, 1=Fill, 2=Stretch
 * - showControls: Whether to show native playback controls
 */
data class VideoPlayerStruct(
    val sourcePtr: Long,
    val volumePtr: Long,
    val aspectRatio: Int,
    val showControls: Boolean
)

/**
 * WebView raw component pointer.
 * Holds the opaque WuiWebView pointer for lifecycle management.
 */
data class WebViewStruct(val webviewPtr: Long)

/**
 * GpuSurface component data.
 * - rendererPtr: Opaque pointer to the boxed GpuRenderer trait object.
 *                This is consumed during init and should not be used after.
 */
data class GpuSurfaceStruct(val rendererPtr: Long)

// ========== MediaPicker Structs ==========

/**
 * Media filter type enum matching WuiMediaFilterType in FFI.
 */
enum class MediaFilterType(val value: Int) {
    /** Filter for live photos only */
    LIVE_PHOTO(0),
    /** Filter for videos only */
    VIDEO(1),
    /** Filter for images only */
    IMAGE(2),
    /** Filter for all media types */
    ALL(3);

    companion object {
        fun fromInt(value: Int): MediaFilterType = entries.firstOrNull { it.value == value } ?: ALL
    }
}

/**
 * MediaPicker component data.
 * - filter: Media filter type (image, video, all, etc.)
 */
data class MediaPickerStruct(
    val filter: Int,
    val onSelectionDataPtr: Long,
    val onSelectionCallPtr: Long
) {
    fun filterType(): MediaFilterType = MediaFilterType.fromInt(filter)
    fun onSelectionDataPtr(): Long = onSelectionDataPtr
    fun onSelectionCallPtr(): Long = onSelectionCallPtr
}

// ========== Resolved Value Structs ==========

data class ResolvedColorStruct(
    val red: Float,
    val green: Float,
    val blue: Float,
    val opacity: Float,
    val headroom: Float
)

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

// ========== Navigation Structs ==========

/**
 * Tab position enum matching WuiTabPosition in FFI.
 */
enum class TabPosition(val value: Int) {
    /** Tab bar at top of container */
    TOP(0),
    /** Tab bar at bottom of container */
    BOTTOM(1);

    companion object {
        fun fromInt(value: Int): TabPosition = entries.firstOrNull { it.value == value } ?: BOTTOM
    }
}

/**
 * NavigationStack component data.
 * Contains the root view of the navigation stack.
 */
data class NavigationStackStruct(val rootPtr: Long)

/**
 * Navigation bar configuration.
 * - titleContentPtr: Computed<StyledStr> pointer for title text
 * - colorPtr: Computed<Color> pointer for bar color
 * - hiddenPtr: Computed<bool> pointer for bar visibility
 */
data class BarStruct(
    val titleContentPtr: Long,
    val colorPtr: Long,
    val hiddenPtr: Long
)

/**
 * NavigationView component data.
 * Contains bar configuration and content view.
 */
data class NavigationViewStruct(
    val bar: BarStruct,
    val contentPtr: Long
)

/**
 * Callback interface for navigation controller push/pop events.
 * This is called from native code when Rust triggers navigation.
 */
interface NavigationControllerCallback {
    /**
     * Called when a view is pushed onto the navigation stack.
     * @param navView The navigation view to push
     */
    fun onPush(navView: NavigationViewStruct)

    /**
     * Called when the top view should be popped from the navigation stack.
     */
    fun onPop()
}

/**
 * Individual tab data.
 * - id: Unique tab identifier (u64)
 * - labelPtr: AnyView pointer for tab label
 * - contentPtr: WuiTabContent pointer for lazy content building
 */
data class TabStruct(
    val id: Long,
    val labelPtr: Long,
    val contentPtr: Long
)

/**
 * Tabs component data.
 * - selectionPtr: Binding<Id> pointer for selected tab
 * - tabs: Array of tab data
 * - position: Tab bar position (top/bottom)
 */
data class TabsStruct(
    val selectionPtr: Long,
    val tabs: Array<TabStruct>,
    val position: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TabsStruct) return false
        return selectionPtr == other.selectionPtr &&
               tabs.contentEquals(other.tabs) &&
               position == other.position
    }
    override fun hashCode(): Int = 31 * (31 * selectionPtr.hashCode() + tabs.contentHashCode()) + position
}

// ========== List Structs ==========

/**
 * List component data.
 * - contentsPtr: WuiAnyViews pointer containing ListItem views
 * - editingPtr: WuiComputed<bool> pointer for edit mode state (0 if not provided)
 * - onDeletePtr: WuiIndexAction pointer for delete callback (0 if not provided)
 * - onMovePtr: WuiMoveAction pointer for move/reorder callback (0 if not provided)
 */
data class ListStruct(
    val contentsPtr: Long,
    val editingPtr: Long,
    val onDeletePtr: Long,
    val onMovePtr: Long
)

/**
 * ListItem component data.
 * - contentPtr: AnyView pointer for item content
 * - deletablePtr: WuiComputed<bool> pointer for deletable state (0 if not provided)
 */
data class ListItemStruct(
    val contentPtr: Long,
    val deletablePtr: Long
)

// ========== Window and App Structs ==========

/**
 * Window style enum matching WuiWindowStyle in FFI.
 */
enum class WindowStyle(val value: Int) {
    /** Standard window with title bar and controls */
    TITLED(0),
    /** Borderless window without title bar */
    BORDERLESS(1),
    /** Window where content extends into the title bar area */
    FULL_SIZE_CONTENT_VIEW(2);

    companion object {
        fun fromInt(value: Int): WindowStyle = entries.firstOrNull { it.value == value } ?: TITLED
    }
}

/**
 * Window struct matching WuiWindow in FFI.
 * Represents a single application window.
 */
data class WindowStruct(
    /** Computed<Str> pointer for window title */
    val titlePtr: Long,
    /** Whether the window can be closed */
    val closable: Boolean,
    /** Whether the window can be resized */
    val resizable: Boolean,
    /** Binding<Rect> pointer for window frame */
    val framePtr: Long,
    /** AnyView pointer for window content */
    val contentPtr: Long,
    /** Binding<WindowState> pointer for window state */
    val statePtr: Long,
    /** AnyView pointer for toolbar content (0 if none) */
    val toolbarPtr: Long,
    /** Window style */
    val style: Int
) {
    fun windowStyle(): WindowStyle = WindowStyle.fromInt(style)
}

/**
 * App struct matching WuiApp in FFI.
 * Returned by waterui_app(env).
 *
 * The environment is returned inside the App struct for native to use during rendering.
 * App::new injects FullScreenOverlayManager into the environment.
 */
data class AppStruct(
    /** Array of windows - first window is main window */
    val windows: Array<WindowStruct>,
    /** Environment pointer returned from the app (with FullScreenOverlayManager injected) */
    val envPtr: Long
) {
    /** Get the main window (first window) */
    fun mainWindow(): WindowStruct = windows.first()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppStruct) return false
        return windows.contentEquals(other.windows) && envPtr == other.envPtr
    }
    override fun hashCode(): Int = 31 * windows.contentHashCode() + envPtr.hashCode()
}

