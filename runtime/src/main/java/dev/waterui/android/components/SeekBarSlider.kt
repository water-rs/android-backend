package dev.waterui.android.components

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.material.slider.Slider

/**
 * The Material slider WaterUI renders, publishing itself as one seek control.
 *
 * `BaseSlider.onInitializeAccessibilityNodeInfo` ends with an unconditional
 * `info.setVisibleToUser(false)`. That is deliberate in Material's own model:
 * the control lives on the virtual thumb nodes its `ExploreByTouchHelper`
 * serves, and the view's own node is meant to be unreachable so the two do not
 * both answer. WaterUI publishes the slider itself instead — one node carrying
 * the label, the range and the adjustment actions — so that discarded value has
 * to be put back, or the node exists but no screen reader and no
 * accessibility-driven test can ever reach it.
 *
 * An accessibility delegate cannot do this: `BaseSlider` clobbers the node after
 * the delegate has run. The delegate records what the platform computed on its
 * way past ([platformVisibleToUser]) and this override restores it afterwards,
 * so the published value is still the platform's own, never a guess.
 */
internal class SeekBarSlider(context: Context) : Slider(context) {
    /**
     * What `View` computed for this node before [Slider] discarded it. Written
     * by the accessibility delegate installed in [installSliderAccessibility],
     * which runs inside the superclass's own population pass.
     */
    var platformVisibleToUser: Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isVisibleToUser = platformVisibleToUser
    }
}
