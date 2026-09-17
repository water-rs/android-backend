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
 * A child's resolved frame plus the proposal the Rust layout selected for it.
 * Returned by waterui_layout_place_subviews.
 *
 * The proposal axes carry the shared contract's encoding: [Float.NaN] for an
 * axis the layout left unspecified, [Float.POSITIVE_INFINITY] for an
 * unbounded probe, and a finite value for the offer the layout negotiated
 * under. The frame is the allocation; the proposal is the negotiation — they
 * are not interchangeable, and the proposal must never be re-derived from the
 * frame.
 */
data class SubviewPlacementStruct(
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var proposalWidth: Float,
    var proposalHeight: Float
)

/**
 * SubView metadata for the new 2-phase layout system.
 * Used with waterui_layout_size_that_fits and waterui_layout_place_subviews.
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
     * The probe answers this bridge object has computed within one
     * negotiation, keyed by the proposal's raw bits. Rust layout containers
     * probe their children freely — ideal, minimum, and allocated offers — and
     * a probe repeated under the same proposal must return the remembered
     * answer instead of measuring the child again: every re-measure of a
     * nested container re-enters its whole subtree, which multiplies identical
     * probes into an exponential measurement storm (the Apple backend's
     * `SubViewProxy` memoizes the same way). The memo dies with this object:
     * the owning group hands the Rust layout a fresh bridge set for every
     * `waterui_layout_*` call, so no answer can outlive the synchronous
     * negotiation it was computed in — no invalidation propagation is needed
     * or relied upon.
     */
    private val measurements = HashMap<Long, ViewDimensionsStruct>()

    /**
     * Proposals currently being answered. A repeat before the first answer
     * returns means a container is recursively measuring this child under the
     * same proposal — a broken layout contract that must not resolve quietly.
     */
    private val activeMeasurements = HashSet<Long>()

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
        // Raw bits, not float equality: NaN (unspecified) never equals itself,
        // and -0.0, +0.0, and the infinities are distinct proposals.
        val key = proposalWidth.toRawBits().toLong() shl 32 or
            (proposalHeight.toRawBits().toLong() and 0xFFFF_FFFFL)
        measurements[key]?.let { return it }
        check(activeMeasurements.add(key)) {
            "WaterUI: recursive layout measurement for proposal ($proposalWidth, $proposalHeight)"
        }
        try {
            val dimensions = view.answerProposal(ProposalStruct(proposalWidth, proposalHeight), density)
            measurements[key] = dimensions
            return dimensions
        } finally {
            activeMeasurements.remove(key)
        }
    }
}

/**
 * The probe answer the WaterUI leaf contract requires of this view, in dp.
 *
 * A view that implements the contract itself — a view hosting WaterUI
 * content, or a leaf whose answer MeasureSpec cannot express (text, which a
 * height proposal must never cap, or a labelled control whose content
 * negotiates the offer minus its platform chrome) — answers through
 * [WuiMeasurableLayout]. Any other view is squeezed through
 * [measureForProposal].
 */
internal fun android.view.View.answerProposal(proposal: ProposalStruct, density: Float): ViewDimensionsStruct {
    if (this is WuiMeasurableLayout) {
        return measureForLayout(proposal)
    }
    return measureForProposal(proposal, density)
}

/**
 * The proposal one axis of a control's WaterUI content hears: the control's
 * own offer minus the room its platform chrome takes on that axis, floored at
 * zero. An unspecified (NaN) or unbounded (infinity) offer has no extent to
 * subtract from, so it passes through untouched.
 */
internal fun ProposalStruct.minusChrome(horizontalDp: Float, verticalDp: Float): ProposalStruct =
    ProposalStruct(
        width = if (width.isFinite()) (width - horizontalDp).coerceAtLeast(0f) else width,
        height = if (height.isFinite()) (height - verticalDp).coerceAtLeast(0f) else height
    )

/**
 * The answer one axis of a leaf gives a Rust layout probe, in dp.
 *
 * On an axis the leaf does not stretch, the answer is the intrinsic extent —
 * measured under the proposal, so a wrapped label's height reflects the wrap
 * — never the offer itself: a leaf echoing the proposal on an axis it does
 * not fill claims room it will not draw, and clamping the intrinsic to the
 * offer is what made text report zero height to a stack's minimum query. On
 * an axis the leaf fills, a finite offer is the negotiated extent and is
 * answered whole — floored at the intrinsic extent, since content cannot
 * compress below itself — while an unspecified probe reports the intrinsic
 * and an unbounded one reports the axis as able to absorb any size.
 */
internal fun leafAxisAnswer(proposalDp: Float, intrinsicDp: Float, fillsAxis: Boolean): Float =
    when {
        !fillsAxis -> intrinsicDp
        proposalDp.isInfinite() -> Float.POSITIVE_INFINITY
        proposalDp.isNaN() -> intrinsicDp
        else -> maxOf(proposalDp, intrinsicDp)
    }

/**
 * The probe answer for a view that is all platform chrome — a picker group,
 * a dropdown field: it measures at its platform intrinsic size and answers
 * that on each axis it does not fill. See [leafAxisAnswer].
 */
internal fun android.view.View.platformIntrinsicAnswer(proposal: ProposalStruct): ViewDimensionsStruct {
    val density = resources.displayMetrics.density
    measure(
        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
    )
    val axis = getTag(TAG_STRETCH_AXIS) as? StretchAxis ?: StretchAxis.NONE
    return ViewDimensionsStruct(
        size = SizeStruct(
            width = leafAxisAnswer(proposal.width, measuredWidth.toFloat() / density, axis.mayFillHorizontal()),
            height = leafAxisAnswer(proposal.height, measuredHeight.toFloat() / density, axis.mayFillVertical())
        ),
        horizontalGuides = emptyArray(),
        verticalGuides = emptyArray()
    )
}

/**
 * Whether a proposal or placement frame on the horizontal axis can be an
 * extent this view stretched to fill. `MAIN_AXIS` and `CROSS_AXIS` are
 * resolved against the parent stack's orientation, which is not visible at
 * this boundary, so both count: on the axis the parent did not stretch, the
 * frame is the child's own measured extent and treating it as filled changes
 * nothing.
 */
internal fun StretchAxis.mayFillHorizontal(): Boolean =
    this != StretchAxis.NONE && this != StretchAxis.VERTICAL

/** See [mayFillHorizontal]. */
internal fun StretchAxis.mayFillVertical(): Boolean =
    this != StretchAxis.NONE && this != StretchAxis.HORIZONTAL

/**
 * Answers a Rust layout probe on behalf of a plain Android view, in dp.
 *
 * Only a view implementing the contract itself ([WuiMeasurableLayout])
 * answers a probe losslessly; anything else is squeezed through MeasureSpec,
 * which cannot tell an unbounded probe (infinity) from an unspecified one
 * (NaN) and carries no alignment guides.
 */
internal fun android.view.View.measureForProposal(proposal: ProposalStruct, density: Float): ViewDimensionsStruct {
    // Convert dp proposal to pixel MeasureSpec
    measure(
        proposalToMeasureSpec(proposal.width * density),
        proposalToMeasureSpec(proposal.height * density)
    )
    // Convert pixel result back to dp for Rust
    return ViewDimensionsStruct(
        size = SizeStruct(
            measuredWidth.toFloat() / density,
            measuredHeight.toFloat() / density
        ),
        horizontalGuides = emptyArray(),
        verticalGuides = emptyArray()
    )
}

/**
 * The MeasureSpec a Rust proposal axis spells for a plain Android child, in
 * pixels: an unspecified (NaN) or unbounded (infinity) axis leaves the child
 * free, while a finite offer caps it at the offered size.
 */
internal fun proposalToMeasureSpec(proposalPx: Float): Int {
    return when {
        proposalPx.isNaN() -> android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        proposalPx.isInfinite() -> android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        else -> android.view.View.MeasureSpec.makeMeasureSpec(kotlin.math.ceil(proposalPx).toInt().coerceAtLeast(0), android.view.View.MeasureSpec.AT_MOST)
    }
}

/**
 * The proposal axis a MeasureSpec spells for a Rust layout, in pixels: only a
 * spec that names a bound is an offer; UNSPECIFIED leaves the axis
 * unspecified.
 */
internal fun measureSpecToProposalPx(spec: Int): Float = when (android.view.View.MeasureSpec.getMode(spec)) {
    android.view.View.MeasureSpec.UNSPECIFIED -> Float.NaN
    else -> android.view.View.MeasureSpec.getSize(spec).toFloat()
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

/**
 * `lineLimit` mirrors `TextConfig::line_limit`: `0` means no limit, and a
 * limited text truncates its last visible line with an ellipsis.
 */
data class TextStruct(
    val contentPtr: Long,
    val paragraphAlignmentPtr: Long,
    val lineLimit: Int,
)

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

data class BadgeStruct(
    val valuePtr: Long,
    val contentPtr: Long,
    val colorPtr: Long
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

/**
 * Metadata<LayoutPriority> struct.
 * Carries the explicit layout priority a `layoutPriority` modifier assigns
 * to its content.
 */
data class MetadataLayoutPriorityStruct(val contentPtr: Long, val value: Int)

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
    val radius: Float,
    val cornerRadius: Float
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
data class MetadataAccessibilityValueStruct(val contentPtr: Long, val valuePtr: Long)
data class MetadataAccessibilityIntStruct(val contentPtr: Long, val value: Int)

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
    /// What the shape is, rather than what its unit-space commands trace. A
    /// corner radius is a fraction of the shorter side, which the commands
    /// cannot express: they stretch it with the clipped rect's aspect ratio.
    val kind: ShapeKindStruct,
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

/**
 * Metadata<Draggable> struct for drag sources.
 * draggablePtr is an opaque handle released via WatcherJni.dropDraggable.
 */
data class MetadataDraggableStruct(
    val contentPtr: Long,
    val draggablePtr: Long
)

/**
 * Metadata<DropDestination> struct for drop targets.
 * destinationPtr is an opaque handle released via WatcherJni.dropDropDestination.
 */
data class MetadataDropDestinationStruct(
    val contentPtr: Long,
    val destinationPtr: Long
)

/** Drag payload: tag 0 = plain text, 1 = URL. */
data class DragDataStruct(val tag: Int, val value: String)

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

/**
 * `AppliedFilter` metadata: the subtree to capture and the semantic filter over it.
 */
data class AppliedFilterStruct(
    /** The child subtree whose rendered pixels the filter reads. */
    val contentPtr: Long,
    /** The semantic filter, consumed once by `appliedFilterCreate`. */
    val filterPtr: Long
)

/**
 * A `ViewEffect` view: the subtree to capture, the effect over it, and the size
 * of the effect's output.
 *
 * The output size is a Rust enum with per-variant payloads, so it crosses
 * flattened: [outputSizeKind] selects which of the remaining fields carry
 * meaning — `0` matches the input and reads none of them, `1` is a fixed pixel
 * size and reads [outputWidth] and [outputHeight], `2` is a scale factor and
 * reads [outputScale]. All four are handed straight back to `viewEffectCreate`,
 * which rebuilds the enum from them.
 */
data class ViewEffectStruct(
    /** The child subtree whose rendered pixels the effect reads. */
    val contentPtr: Long,
    /** The semantic effect renderer, consumed once by `viewEffectCreate`. */
    val effectPtr: Long,
    val outputSizeKind: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val outputScale: Float
)

/**
 * A `Picture` view: the handle the view keeps until `dropPicture`, and its size in dp.
 */
data class PictureStruct(
    val picturePtr: Long,
    val width: Float,
    val height: Float,
    /** The name the drawing offers a screen reader; empty when it offers none. */
    val label: String,
    /** The semantic content the drawing offers; empty when it offers none. */
    val value: String
)

/**
 * Premultiplied RGBA8 pixels of a rasterised picture, viewed through a direct buffer over the
 * Rust allocation that `handlePtr` owns; `dropBitmap` releases it once the pixels are copied.
 */
data class BitmapStruct(
    val width: Int,
    val height: Int,
    val pixels: java.nio.ByteBuffer,
    val handlePtr: Long
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
    val family: String?,
    /** Ordinal of `WuiFontDesign`: which platform face to use when [family] is null. */
    val design: Int,
    /** Absolute line height in sp; `0f` keeps the face's natural metrics. */
    val lineHeight: Float = 0f,
    /** Additional spacing between adjacent glyphs in sp. */
    val letterSpacing: Float = 0f
) {
    val isMonospaced: Boolean
        get() = when (design) {
            FONT_DESIGN_DEFAULT -> false
            FONT_DESIGN_MONOSPACED -> true
            else -> error("unknown font design: $design")
        }

    companion object {
        const val FONT_DESIGN_DEFAULT = 0
        const val FONT_DESIGN_MONOSPACED = 1
    }
}

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
    val popPtr: Long,
    /// How this destination asked to arrive, mirroring
    /// `WuiNavigationTransitionKind`. `INHERIT` means it asked for nothing and
    /// the stack's transition stands — which is the common case, because a
    /// destination only declares one when it names a matched pair the stack
    /// cannot name for every push.
    val transitionKind: Int,
    /// The zoom source id, meaningful only when `transitionKind` is zoom.
    val transitionSourceId: Int
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
/**
 * A list row, plus the section break it may open.
 *
 * `hasSection` distinguishes "this row starts a new section" from "this row
 * continues the previous one"; a section with neither header nor footer is a
 * pure divider, so both text pointers are then `0`. The two pointers are
 * reactive styled-text signals, not resolved strings: a section title
 * localizes and can be driven by app state.
 */
data class ListItemStruct(
    val contentPtr: Long,
    val deletablePtr: Long,
    val selectedPtr: Long,
    val hasSection: Boolean,
    val sectionLabelPtr: Long,
    val sectionFooterPtr: Long
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
