package dev.waterui.android.runtime

import android.view.View

private const val DEFAULT_ANIMATION_DURATION = 150L

fun View.applyRustAnimation(animation: WuiAnimation, update: () -> Unit) {
    if (!isAttachedToWindow || animation == WuiAnimation.NONE) {
        update()
        return
    }
    animate().cancel()
    animate()
        .alpha(0f)
        .setDuration(DEFAULT_ANIMATION_DURATION)
        .withEndAction {
            update()
            animate()
                .alpha(1f)
                .setDuration(DEFAULT_ANIMATION_DURATION)
                .start()
        }
        .start()
}
