package dev.waterui.android.components

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RangeAccessibilityTest {
    private fun themedContext(): ContextThemeWrapper = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        com.google.android.material.R.style.Theme_Material3_DayNight,
    )

    private fun AccessibilityNodeInfo.hasAction(action: AccessibilityActionCompat): Boolean =
        actionList.any { it.id == action.id }

    private fun slider(
        from: Float,
        to: Float,
        value: Float,
    ): Pair<SeekBarSlider, MutableList<Double>> {
        val slider = SeekBarSlider(themedContext()).apply {
            valueFrom = from
            valueTo = to
            stepSize = 0f
            this.value = value
        }
        val writes = mutableListOf<Double>()
        installSliderAccessibility(
            target = slider,
            range = SliderAccessibilityRange(
                start = from.toDouble(),
                end = to.toDouble(),
                read = { slider.value.toDouble() },
                write = { written ->
                    writes.add(written)
                    slider.value = written.toFloat()
                },
            ),
        )
        return slider to writes
    }

    @Test
    fun sliderPublishesASeekBarNodeCarryingItsRangeAndAdjustmentActions() {
        val (slider, _) = slider(from = 0f, to = 200f, value = 50f)

        val info = slider.createAccessibilityNodeInfo()
        assertNotNull(info)
        checkNotNull(info)

        assertEquals(SEEK_BAR_CLASS_NAME, info.className)
        val range = info.rangeInfo
        assertNotNull(range)
        checkNotNull(range)
        assertEquals(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, range.type)
        assertEquals(0f, range.min, 0f)
        assertEquals(200f, range.max, 0f)
        assertEquals(50f, range.current, 0f)

        assertTrue(info.hasAction(AccessibilityActionCompat.ACTION_SET_PROGRESS))
        assertTrue(info.hasAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD))
        assertTrue(info.hasAction(AccessibilityActionCompat.ACTION_SCROLL_BACKWARD))
    }

    @Test
    fun performingSetProgressWritesTheRequestedValueBack() {
        val (slider, writes) = slider(from = 0f, to = 200f, value = 50f)

        val arguments = Bundle().apply {
            putFloat(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_PROGRESS_VALUE, 120f)
        }
        assertTrue(
            slider.performAccessibilityAction(
                AccessibilityActionCompat.ACTION_SET_PROGRESS.id,
                arguments,
            ),
        )

        assertEquals(listOf(120.0), writes)
        assertEquals(120f, slider.value, 0f)
        assertEquals(
            120f,
            checkNotNull(slider.createAccessibilityNodeInfo()).rangeInfo.current,
            0f,
        )
    }

    @Test
    fun scrollActionsStepByOnePercentOfTheRangeAndStopAtTheBounds() {
        val (slider, writes) = slider(from = 0f, to = 200f, value = 0f)

        assertTrue(
            slider.performAccessibilityAction(
                AccessibilityActionCompat.ACTION_SCROLL_FORWARD.id,
                null,
            ),
        )
        assertEquals(listOf(2.0), writes)

        assertTrue(
            slider.performAccessibilityAction(
                AccessibilityActionCompat.ACTION_SCROLL_BACKWARD.id,
                null,
            ),
        )
        assertEquals(listOf(2.0, 0.0), writes)

        // Already at the minimum: nothing to move, so the action is not performed.
        assertFalse(
            slider.performAccessibilityAction(
                AccessibilityActionCompat.ACTION_SCROLL_BACKWARD.id,
                null,
            ),
        )
        assertEquals(listOf(2.0, 0.0), writes)
    }

    @Test
    fun aDisabledSliderOffersNoAdjustmentActionsAndRefusesThem() {
        val (slider, writes) = slider(from = 0f, to = 200f, value = 50f)
        slider.isEnabled = false

        val info = checkNotNull(slider.createAccessibilityNodeInfo())
        assertFalse(info.hasAction(AccessibilityActionCompat.ACTION_SET_PROGRESS))
        assertFalse(info.hasAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD))
        assertFalse(
            slider.performAccessibilityAction(
                AccessibilityActionCompat.ACTION_SCROLL_FORWARD.id,
                null,
            ),
        )
        assertEquals(emptyList<Double>(), writes)
    }

    /**
     * The regression behind issue #401: `BaseSlider.onInitializeAccessibilityNodeInfo`
     * ends with an unconditional `setVisibleToUser(false)`, which drops the whole
     * control out of the tree a screen reader or an accessibility-driven test walks.
     * [SeekBarSlider] publishes what the platform computed instead.
     */
    @Test
    fun theSliderNodeKeepsTheVisibilityMaterialDiscards() {
        val context = themedContext()

        val plain = Slider(context)
        assertFalse(checkNotNull(plain.createAccessibilityNodeInfo()).isVisibleToUser)

        val waterui = SeekBarSlider(context)
        waterui.platformVisibleToUser = true
        assertTrue(checkNotNull(waterui.createAccessibilityNodeInfo()).isVisibleToUser)

        waterui.platformVisibleToUser = false
        assertFalse(checkNotNull(waterui.createAccessibilityNodeInfo()).isVisibleToUser)
    }

    @Test
    fun progressPublishesAProgressBarNodeLabelledByItsOwnLabelView() {
        val context = themedContext()
        val indicator = LinearProgressIndicator(context).apply { max = 1000 }
        val label = TextView(context).apply { text = "Downloading" }
        var reading = 0.25

        installProgressAccessibility(
            target = indicator,
            label = { accessibilityTextOf(label) },
            value = { reading },
        )

        val info = checkNotNull(indicator.createAccessibilityNodeInfo())
        assertEquals(PROGRESS_BAR_CLASS_NAME, info.className)
        assertEquals("Downloading", info.contentDescription)
        val range = checkNotNull(info.rangeInfo)
        assertEquals(0f, range.min, 0f)
        assertEquals(1f, range.max, 0f)
        assertEquals(0.25f, range.current, 0f)
        assertTrue(info.actionList.none { it.id == AccessibilityActionCompat.ACTION_SET_PROGRESS.id })

        reading = 0.75
        assertEquals(
            0.75f,
            checkNotNull(checkNotNull(indicator.createAccessibilityNodeInfo()).rangeInfo).current,
            0f,
        )
    }

    @Test
    fun anIndeterminateProgressPublishesNoReading() {
        val indicator = LinearProgressIndicator(themedContext()).apply { max = 1000 }

        installProgressAccessibility(
            target = indicator,
            label = { "Working" },
            value = { Double.POSITIVE_INFINITY },
        )

        val info = checkNotNull(indicator.createAccessibilityNodeInfo())
        assertEquals(PROGRESS_BAR_CLASS_NAME, info.className)
        assertNull(info.rangeInfo)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_YES,
            indicator.importantForAccessibility,
        )
    }
}
