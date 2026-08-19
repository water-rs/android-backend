package dev.waterui.android.runtime

/**
 * Data classes that mirror native FFI structs.
 *
 * These are used for marshalling data between Kotlin and native code.
 */

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
        fun fromInt(value: Int): StretchAxis = entries.firstOrNull { it.value == value }
            ?: error("unknown stretch axis: $value")
    }
}

enum class HorizontalAlignment(val value: Int) {
    LEADING(0),
    CENTER(1),
    TRAILING(2);

    companion object {
        fun fromInt(value: Int): HorizontalAlignment = entries.firstOrNull { it.value == value }
            ?: error("unknown horizontal alignment: $value")
    }
}

enum class VerticalAlignment(val value: Int) {
    TOP(0),
    CENTER(1),
    BOTTOM(2),
    FIRST_BASELINE(3),
    LAST_BASELINE(4);

    companion object {
        fun fromInt(value: Int): VerticalAlignment = entries.firstOrNull { it.value == value }
            ?: error("unknown vertical alignment: $value")
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

data class ProposalStruct(var width: Float, var height: Float)

data class SizeStruct(val width: Float, val height: Float)

data class HorizontalGuideStruct(val alignment: HorizontalAlignment, val value: Float)

data class VerticalGuideStruct(val alignment: VerticalAlignment, val value: Float)

data class ViewDimensionsStruct(
    val size: SizeStruct,
    val horizontalGuides: Array<HorizontalGuideStruct>,
    val verticalGuides: Array<VerticalGuideStruct>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewDimensionsStruct) return false
        return size == other.size
            && horizontalGuides.contentEquals(other.horizontalGuides)
            && verticalGuides.contentEquals(other.verticalGuides)
    }

    override fun hashCode(): Int {
        var result = size.hashCode()
        result = 31 * result + horizontalGuides.contentHashCode()
        result = 31 * result + verticalGuides.contentHashCode()
        return result
    }
}

data class RectStruct(var x: Float, var y: Float, var width: Float, var height: Float)

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
     * @return Measured dimensions in dp for the Rust layout engine
     */
    @Suppress("unused") // Called from native code
    fun measureForLayout(proposalWidth: Float, proposalHeight: Float): ViewDimensionsStruct {
        // Convert dp proposal to pixel MeasureSpec
        val widthSpec = proposalToMeasureSpec(proposalWidth * density)
        val heightSpec = proposalToMeasureSpec(proposalHeight * density)
        view.measure(widthSpec, heightSpec)
        // Convert pixel result back to dp for Rust
        return ViewDimensionsStruct(
            size = SizeStruct(
                view.measuredWidth.toFloat() / density,
                view.measuredHeight.toFloat() / density
            ),
            horizontalGuides = emptyArray(),
            verticalGuides = emptyArray()
        )
    }

    private fun proposalToMeasureSpec(proposalPx: Float): Int {
        return when {
            proposalPx.isNaN() -> android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            proposalPx.isInfinite() -> android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            else -> android.view.View.MeasureSpec.makeMeasureSpec(proposalPx.toInt().coerceAtLeast(0), android.view.View.MeasureSpec.AT_MOST)
        }
    }
}

// ========== Watcher Structs ==========

/**
 * Common watcher envelope for bindings/computed values.
 * Contains pointers to the callback data, call function, and drop function.
 */
data class WatcherStruct(val dataPtr: Long, val callPtr: Long, val dropPtr: Long)

// ========== View Structs ==========

data class ButtonStruct(
    val labelPtr: Long,
    val actionPtr: Long,
    val style: Int,
    val accessibilityLabelPtr: Long
)

data class TextStruct(val contentPtr: Long, val paragraphAlignmentPtr: Long)

data class PlainStruct(val text: String)

data class TextFieldStruct(
    val labelPtr: Long,
    val accessibilityLabelPtr: Long,
    val valuePtr: Long,
    val promptPtr: Long,
    val promptAlignmentPtr: Long,
    val keyboardType: Int,
    val selectionMenuPtr: Long,
    /**
     * Maximum number of lines the field accepts: `1` is single-line, a larger
     * value caps a multi-line field, and `0` means no limit.
     */
    val lineLimit: Int
)

enum class MenuItemTag(val value: Int) {
    COMMAND(0),
    DIVIDER(1),
    MENU(2);

    companion object {
        fun fromInt(value: Int): MenuItemTag = entries.firstOrNull { it.value == value }
            ?: error("unsupported menu item tag: $value")
    }
}

/**
 * Android intentionally omits `SystemIcon` from semantic menu nodes.
 *
 * `SystemIcon` is not a reliable cross-platform contract here, and icon-pack based icons currently live in the
 * regular view layer rather than the semantic menu payload.
 */
data class MenuItemStruct(
    val tag: Int,
    val labelPtr: Long,
    val actionPtr: Long,
    val disabledPtr: Long,
    val selectedPtr: Long,
    val keyEquivalent: String?,
    val command: Boolean,
    val shift: Boolean,
    val option: Boolean,
    val control: Boolean,
    val itemsPtr: Long
)

data class MenuStruct(
    val labelPtr: Long,
    val itemsPtr: Long,
    val accessibilityLabelPtr: Long
)

data class MetadataContextMenuStruct(
    val contentPtr: Long,
    val itemsPtr: Long
)

data class SecureFieldStruct(
    val labelPtr: Long,
    val accessibilityLabelPtr: Long,
    val valuePtr: Long
)

data class ToggleStruct(
    val labelPtr: Long,
    val accessibilityLabelPtr: Long,
    val bindingPtr: Long,
    val style: Int
)

data class SliderStruct(
    val labelPtr: Long,
    val accessibilityLabelPtr: Long,
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
    val accessibilityLabelPtr: Long,
    val valueFormatterPtr: Long,
    val rangeStart: Int,
    val rangeEnd: Int
)

data class ProgressStruct(
    val labelPtr: Long,
    val valueLabelPtr: Long,
    val valuePtr: Long,
    val style: Int,
    val fourColor: Boolean
)

data class ScrollStruct(
    val axis: Int,
    val contentPtr: Long,
    val targetXPtr: Long,
    val targetYPtr: Long,
    val scrollGenerationPtr: Long
)

data class DynamicStruct(val dynamicPtr: Long)

data class DateStruct(val year: Int, val month: Int, val day: Int)

data class DateTimeStruct(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int
)

enum class PickerStyle(val value: Int) {
    AUTOMATIC(0),
    MENU(1),
    RADIO(2),
    SEGMENTED(3);

    companion object {
        fun fromInt(value: Int): PickerStyle = entries.firstOrNull { it.value == value }
            ?: error("unknown picker style: $value")
    }
}

enum class DatePickerType(val value: Int) {
    DATE(0),
    HOUR_AND_MINUTE(1),
    HOUR_MINUTE_AND_SECOND(2),
    DATE_HOUR_AND_MINUTE(3),
    DATE_HOUR_MINUTE_AND_SECOND(4);

    companion object {
        fun fromInt(value: Int): DatePickerType = entries.firstOrNull { it.value == value }
            ?: error("unknown date picker type: $value")
    }
}

data class PickerStruct(val itemsPtr: Long, val selectionPtr: Long, val style: Int)

data class DatePickerStruct(
    val labelPtr: Long,
    val accessibilityLabelPtr: Long,
    val valuePtr: Long,
    val rangeStart: DateTimeStruct,
    val rangeEnd: DateTimeStruct,
    val type: Int
)

data class MultiDatePickerStruct(
    val labelPtr: Long,
    val accessibilityLabelPtr: Long,
    val valuePtr: Long,
    val rangeStart: DateStruct,
    val rangeEnd: DateStruct,
    val decoratedPtr: Long
)

data class ColorPickerStruct(
    val labelPtr: Long,
    val accessibilityLabelPtr: Long,
    val valuePtr: Long,
    val supportAlpha: Boolean,
    val supportHdr: Boolean
)

/**
 * Metadata<Environment> struct for WithEnv component.
 * Provides a new environment for child views.
 */
data class MetadataEnvStruct(val contentPtr: Long, val envPtr: Long)

data class MetadataNavigationTransitionStruct(val contentPtr: Long, val id: Int)

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
    THEN(5),
    SIMULTANEOUS(6),
    EXCLUSIVE(7);

    companion object {
        fun fromInt(value: Int): GestureType =
            entries.firstOrNull { it.value == value }
                ?: error("Unsupported GestureType value: $value")
    }
}

data class GestureStruct(
    val gestureType: Int,
    val gestureData: GestureDataStruct
)

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
 * Event type enum matching WuiEvent in FFI.
 */
enum class EventType(val value: Int) {
    HOVER_ENTER(0),
    HOVER_MOVE(1),
    HOVER_EXIT(2);

    companion object {
        fun fromInt(value: Int): EventType = entries.firstOrNull { it.value == value }
            ?: error("unknown event type: $value")
    }
}

/**
 * Metadata<OnEvent> struct for repeatable interaction event handlers.
 */
data class MetadataOnEventStruct(
    val contentPtr: Long,
    val eventType: Int,
    val handlerPtr: Long
)

/** Lifecycle event enum matching WuiLifecycle in FFI. */
enum class LifecycleType(val value: Int) {
    APPEAR(0),
    DISAPPEAR(1);

    companion object {
        fun fromInt(value: Int): LifecycleType = entries.firstOrNull { it.value == value }
            ?: error("unknown lifecycle type: $value")
    }
}

/** Metadata<LifecycleHook> struct for one-shot attach/detach handlers. */
data class MetadataLifecycleHookStruct(
    val contentPtr: Long,
    val lifecycleType: Int,
    val handlerPtr: Long
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

data class MetadataBorderStruct(
    val contentPtr: Long,
    val colorPtr: Long,
    val width: Float,
    val cornerRadius: Float,
    val edges: Int
)

data class MetadataScaleStruct(
    val contentPtr: Long,
    val scaleXPtr: Long,
    val scaleYPtr: Long,
    val anchorX: Float,
    val anchorY: Float
)

data class MetadataRotationStruct(
    val contentPtr: Long,
    val anglePtr: Long,
    val anchorX: Float,
    val anchorY: Float
)

data class MetadataOffsetStruct(
    val contentPtr: Long,
    val offsetXPtr: Long,
    val offsetYPtr: Long
)

data class MetadataCursorStruct(val contentPtr: Long, val stylePtr: Long)

data class MetadataAccessibilityIdentifierStruct(val contentPtr: Long, val identifier: String)
data class MetadataAccessibilityLabelStruct(val contentPtr: Long, val labelPtr: Long)
data class MetadataAccessibilityValueStruct(val contentPtr: Long, val value: Int)

/**
 * A navigation link's marker.
 *
 * The marker carries nothing but its content: it exists so a backend that draws
 * an affordance around the row a link sits in — the iOS disclosure chevron — can
 * recognise one. Material lists have no such affordance, so nothing here
 * registers the type and the marker falls through to its content; the struct
 * exists because the JNI cast is generated for every ignorable metadata.
 */
data class NavigationLinkHintStruct(val contentPtr: Long)
data class MetadataAccessibilityStateStruct(
    val contentPtr: Long,
    val disabledPtr: Long,
    val selectedPtr: Long,
    val checkedPtr: Long,
    val expandedPtr: Long,
    val busyPtr: Long,
    val hiddenPtr: Long,
)

data class MetadataClipShapeStruct(
    val contentPtr: Long,
    val commands: Array<PathCommandStruct>
)

data class MetadataHittableStruct(val contentPtr: Long, val enabledPtr: Long)

/**
 * Metadata<Opacity> struct for alpha blending.
 */
data class MetadataOpacityStruct(
    val contentPtr: Long,
    val valuePtr: Long
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

data class PickerItemStruct(val tag: Int, val labelPtr: Long)

/** Opaque WebView wrapper pointer consumed by the Android runtime host view. */
data class WebViewStruct(val webviewPtr: Long)

/**
 * GpuSurface component data.
 * - rendererPtr: Opaque pointer to the boxed GpuSurface. Consumed during state creation.
 * - hasHdrPreference/prefersHdr: Optional renderer-level surface range override.
 */
data class GpuSurfaceStruct(
    val rendererPtr: Long,
    val hasHdrPreference: Boolean,
    val prefersHdr: Boolean,
    val hasPictureInPictureHostId: Boolean,
    val pictureInPictureHostId: Long
)

data class AndroidVideoSurfaceHostStruct(
    val contentPtr: Long,
    val bridgePtr: Long
)

data class MetadataDynamicRangeStruct(val contentPtr: Long)

// ========== Resolved Value Structs ==========

data class ResolvedColorStruct(
    val red: Float,
    val green: Float,
    val blue: Float,
    val opacity: Float,
    val headroom: Float
)

data class ResolvedGradientStopStruct(
    val position: Float,
    val color: ResolvedColorStruct
)

data class ResolvedGradientStruct(
    val gradientType: Int,
    val stops: Array<ResolvedGradientStopStruct>,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val startValue: Float,
    val endValue: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedGradientStruct) return false
        return gradientType == other.gradientType &&
            stops.contentEquals(other.stops) &&
            startX == other.startX &&
            startY == other.startY &&
            endX == other.endX &&
            endY == other.endY &&
            startValue == other.startValue &&
            endValue == other.endValue
    }

    override fun hashCode(): Int {
        var result = gradientType
        result = 31 * result + stops.contentHashCode()
        result = 31 * result + startX.hashCode()
        result = 31 * result + startY.hashCode()
        result = 31 * result + endX.hashCode()
        result = 31 * result + endY.hashCode()
        result = 31 * result + startValue.hashCode()
        result = 31 * result + endValue.hashCode()
        return result
    }
}

data class ShapeKindStruct(
    val tag: Int,
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float
)

data class PathCommandStruct(
    val tag: Int,
    val x: Float,
    val y: Float,
    val cx: Float,
    val cy: Float,
    val c1x: Float,
    val c1y: Float,
    val c2x: Float,
    val c2y: Float,
    val rx: Float,
    val ry: Float,
    val start: Float,
    val sweep: Float
)

data class ResolvedShapeStruct(
    val kind: ShapeKindStruct,
    val commands: Array<PathCommandStruct>,
    val fillPtr: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedShapeStruct) return false
        return kind == other.kind &&
            commands.contentEquals(other.commands) &&
            fillPtr == other.fillPtr
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + commands.contentHashCode()
        result = 31 * result + fillPtr.hashCode()
        return result
    }
}

data class ResolvedFontStruct(
    val size: Float,
    val weight: Int,
    val family: String?
)

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
 * NavigationStack component data.
 * Contains the root view of the navigation stack.
 */
data class NavigationStackStruct(
    val rootPtr: Long,
    val transition: Int,
    val transitionSourceId: Int
)

/**
 * Search configuration rendered inside navigation chrome.
 */
data class NavigationSearchStruct(
    val textPtr: Long,
    val promptPtr: Long
)

/** One semantic native navigation toolbar item. */
data class NavigationToolbarItemStruct(
    val placement: Int,
    val contentPtr: Long
)

/**
 * Navigation bar configuration.
 * - titleContentPtr: Computed<StyledStr> pointer for title text
 * - colorPtr: Computed<Color> pointer for bar color
 * - hiddenPtr: Computed<bool> pointer for bar visibility
 */
data class BarStruct(
    val titlePtr: Long,
    val subtitlePtr: Long,
    val toolbar: Array<NavigationToolbarItemStruct>,
    val search: NavigationSearchStruct?,
    val colorPtr: Long,
    val hiddenPtr: Long,
    val displayMode: Int
)

/**
 * NavigationView component data.
 * Contains bar configuration and content view.
 */
data class NavigationViewStruct(
    val bar: BarStruct,
    val contentPtr: Long,
    val popEnabledPtr: Long,
    val popAttemptedPtr: Long,
    val appearPtr: Long,
    val disappearPtr: Long,
    val popPtr: Long
)

/**
 * Split navigation shell rendered by platform backends.
 */
data class SplitNavigationContainerStruct(
    val sidebarPtr: Long,
    val placeholderPtr: Long,
    val primarySelectionPtr: Long,
    val contentPtr: Long,
    val secondarySelectionPtr: Long,
    val detailPtr: Long,
    val columnVisibilityPtr: Long,
    val sidebarMinWidth: Float,
    val sidebarIdealWidth: Float,
    val sidebarMaxWidth: Float,
    val style: Int
)

/**
 * Individual tab data.
 * - id: Unique tab identifier (u64)
 * - labelPtr: AnyView pointer for tab label
 * - contentPtr: WuiTabContent pointer for lazy content building
 * - systemIconPtr: WuiSystemIcon pointer, or 0 when the icon is not a platform
 *   symbol. A tab item takes an icon separately from its title, so it cannot be
 *   read back out of the label view.
 * - iconPtr: AnyView pointer for an icon that is not a platform symbol, or 0.
 */
data class TabStruct(
    val id: Long,
    val labelPtr: Long,
    val contentPtr: Long,
    val badgePtr: Long,
    val enabledPtr: Long,
    val systemIconPtr: Long,
    val iconPtr: Long
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
    val style: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TabsStruct) return false
        return selectionPtr == other.selectionPtr &&
               tabs.contentEquals(other.tabs) &&
               style == other.style
    }
    override fun hashCode(): Int = 31 * (31 * selectionPtr.hashCode() + tabs.contentHashCode()) + style
}

// ========== List Structs ==========

/**
 * List component data.
 * - contentsPtr: WuiAnyViews pointer containing ListItem views
 * - editingPtr: Computed<Boolean> pointer for edit mode
 * - onDeletePtr: IndexAction pointer (0 if unsupported)
 * - onMovePtr: MoveAction pointer (0 if unsupported)
 * - targetIndexPtr: Computed<Int> requested row (0 if uncontrolled)
 * - scrollGenerationPtr: Computed<Int> request generation (0 if uncontrolled)
 */
data class ListStruct(
    val contentsPtr: Long,
    val editingPtr: Long,
    val onDeletePtr: Long,
    val onMovePtr: Long,
    val targetIndexPtr: Long,
    val scrollGenerationPtr: Long,
    val usesSections: Boolean
)

/**
 * ListItem component data.
 * - contentPtr: AnyView pointer for item content
 * - deletablePtr: Computed<Boolean> pointer controlling item delete ability
 * - selectedPtr: Computed<Boolean> pointer marking the row as the current selection
 */
data class ListItemStruct(
    val contentPtr: Long,
    val deletablePtr: Long,
    val selectedPtr: Long,
    val sectionLabel: String?,
    val sectionFooter: String?
)

// ========== App Struct ==========

/** Move-only Android projection of the app's main content and environment. */
class AppStruct(contentPtr: Long, envPtr: Long) {
    private var ownedContentPtr = contentPtr
    private var ownedEnvironmentPtr = envPtr

    init {
        require(contentPtr != 0L) { "AppStruct.contentPtr is null" }
        require(envPtr != 0L) { "AppStruct.envPtr is null" }
    }

    fun takeContent(): Long = takeOwnedPointer(
        pointer = ownedContentPtr,
        name = "AppStruct.contentPtr"
    ).also { ownedContentPtr = 0L }

    fun takeEnvironment(): Long = takeOwnedPointer(
        pointer = ownedEnvironmentPtr,
        name = "AppStruct.envPtr"
    ).also { ownedEnvironmentPtr = 0L }

    private fun takeOwnedPointer(pointer: Long, name: String): Long {
        check(pointer != 0L) { "$name ownership was already transferred" }
        return pointer
    }
}
