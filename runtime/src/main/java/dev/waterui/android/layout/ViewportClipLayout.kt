package dev.waterui.android.layout

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * The parent that holds a view to its own bounds.
 *
 * Every WaterUI container clears [ViewGroup.setClipChildren] so a shadow or an
 * overlay may spill past the view that owns it, the way a `UIView` does. Android
 * spells that permission on the parent rather than on the view, so it does not
 * only free shadows: it frees everything the container holds, including a
 * scroll container, whose whole purpose is to show one window onto content
 * taller than itself. An unclipped scroll container paints its scrolled-away
 * content over whatever surrounds the viewport — over the status bar, once the
 * window root has inset it away from that edge — and reports that content to
 * accessibility as on screen, because [ViewGroup.getChildVisibleRect] narrows a
 * rect to a view's bounds only when that view's own parent clips its children.
 * The rect it then hands out for a row scrolled past the top is clamped one edge
 * at a time, so the row arrives with its bottom above its top.
 *
 * A viewport is not the surrounding layout's opinion, so a view that owns one
 * brings the parent that enforces it. This container is otherwise transparent:
 * it measures its single child against the proposal it was given itself and
 * lays it out over its whole area, so the WaterUI layout above it sees exactly
 * the sizes the child reports.
 */
@SuppressLint("ViewConstructor")
open class ViewportClipLayout(context: Context, content: View) : ViewGroup(context) {
    init {
        clipChildren = true
        clipToPadding = true
        addView(content)
    }

    private val content: View
        get() = checkNotNull(getChildAt(0)) { "ViewportClipLayout lost its content view" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = content
        child.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(child.measuredWidth, child.measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        content.layout(0, 0, right - left, bottom - top)
    }
}
