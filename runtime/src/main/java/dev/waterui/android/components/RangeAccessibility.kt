package dev.waterui.android.components

import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.RangeInfoCompat

/**
 * The platform class a WaterUI slider claims. Android screen readers key their
 * seek-control gestures off `SeekBar`, so the Material slider view is published
 * under that name rather than its own.
 */
internal const val SEEK_BAR_CLASS_NAME = "android.widget.SeekBar"

/** The platform class a WaterUI progress indicator claims. */
internal const val PROGRESS_BAR_CLASS_NAME = "android.widget.ProgressBar"

/**
 * How many accessibility steps span a slider's whole range. One percent of the
 * span per step matches the hydrolysis renderer's `slider_step_for_range`, so a
 * screen-reader swipe moves a WaterUI slider by the same amount on every backend.
 */
private const val ACCESSIBILITY_STEPS_PER_RANGE = 100.0

/**
 * The value arithmetic behind a slider's accessibility actions, kept apart from
 * any view so it can be exercised without one.
 */
internal class SliderAccessibilityRange(
    val start: Double,
    val end: Double,
    private val read: () -> Double,
    private val write: (Double) -> Unit,
) {
    init {
        require(start.isFinite() && end.isFinite() && start < end) {
            "slider accessibility range must contain finite increasing bounds"
        }
    }

    /** One screen-reader increment or decrement. */
    val step: Double get() = (end - start) / ACCESSIBILITY_STEPS_PER_RANGE

    /** The value the control currently shows, clamped into the range. */
    val current: Double get() = read().coerceIn(start, end)

    /**
     * Moves the slider to [value], clamped into the range. Returns whether the
     * value actually moved, which is what an accessibility action reports back:
     * a request that leaves the slider where it already was was not performed.
     */
    fun setValue(value: Double): Boolean {
        if (!value.isFinite()) {
            return false
        }
        val clamped = value.coerceIn(start, end)
        if (clamped == current) {
            return false
        }
        write(clamped)
        return true
    }

    fun increment(): Boolean = setValue(current + step)

    fun decrement(): Boolean = setValue(current - step)
}

/**
 * Publishes [target] as a seek control: a `SeekBar`-classed node carrying the
 * live value and its bounds, plus the actions a screen reader uses to adjust it.
 *
 * Installing this delegate also replaces the Material slider's own
 * `ExploreByTouchHelper`, so the virtual thumb node stops competing with the
 * node published here, and it records the platform's visibility for
 * [SeekBarSlider] to restore — see that class for why.
 */
internal fun installSliderAccessibility(
    target: SeekBarSlider,
    range: SliderAccessibilityRange,
) {
    target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    installAccessibilityDelegate(
        target,
        mutate = { info ->
            target.platformVisibleToUser = info.isVisibleToUser
            info.className = SEEK_BAR_CLASS_NAME
            info.rangeInfo = RangeInfoCompat(
                RangeInfoCompat.RANGE_TYPE_FLOAT,
                range.start.toFloat(),
                range.end.toFloat(),
                range.current.toFloat(),
            )
            // The view's own state is what decides, not the node's: whatever
            // delegate ran before this one may not have populated that flag yet.
            if (target.isEnabled) {
                info.addAction(AccessibilityActionCompat.ACTION_SET_PROGRESS)
                info.addAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                info.addAction(AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
            }
        },
        performAction = { action, arguments ->
            val handled = target.isEnabled && when (action) {
                AccessibilityActionCompat.ACTION_SET_PROGRESS.id -> {
                    val key = AccessibilityNodeInfoCompat.ACTION_ARGUMENT_PROGRESS_VALUE
                    val requested = arguments?.takeIf { it.containsKey(key) }?.getFloat(key)
                    requested != null && range.setValue(requested.toDouble())
                }
                AccessibilityActionCompat.ACTION_SCROLL_FORWARD.id -> range.increment()
                AccessibilityActionCompat.ACTION_SCROLL_BACKWARD.id -> range.decrement()
                else -> false
            }
            if (handled) {
                // What a platform SeekBar sends when its progress moves; it is
                // how a screen reader learns to re-announce the new value.
                target.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
            }
            handled
        },
    )
}

/**
 * Publishes [target] as a progress indicator: a `ProgressBar`-classed node
 * carrying the live reading between 0 and 1, and no actions, because a progress
 * indicator reports work rather than accepting input.
 *
 * [label] supplies the announced text, and an indeterminate reading
 * (a non-finite [value]) publishes the node with no range at all rather than
 * inventing a reading it does not have.
 */
internal fun installProgressAccessibility(
    target: View,
    label: () -> CharSequence?,
    value: () -> Double,
) {
    target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    installAccessibilityMutation(target) { info ->
        info.className = PROGRESS_BAR_CLASS_NAME
        if (info.contentDescription.isNullOrEmpty()) {
            info.contentDescription = label()
        }
        val current = value()
        // A platform progress bar publishes a range of its own in track units.
        // WaterUI's reading is normalized, and an indeterminate indicator has
        // none at all, so the inherited one is always replaced or cleared.
        if (current.isFinite()) {
            info.rangeInfo = RangeInfoCompat(
                RangeInfoCompat.RANGE_TYPE_FLOAT,
                0f,
                1f,
                current.coerceIn(0.0, 1.0).toFloat(),
            )
        } else {
            // The compat wrapper cannot clear a range, and an indeterminate
            // indicator must not carry the platform widget's track-unit one.
            info.unwrap().rangeInfo = null
        }
    }
}
