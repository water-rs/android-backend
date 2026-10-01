package dev.waterui.android.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.graphics.withTranslation
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.PathCommandStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ShapeKindStruct
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.toColorInt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

private val metadataShadowTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_shadow_id().toTypeId()
}

/**
 * Renderer for Metadata<Shadow>.
 *
 * Applies the platform elevation shadow to the wrapped view.
 */
private val metadataShadowRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_shadow(node.rawPtr)

    // `colorPtr` is an unresolved WuiColor, not a computed. It has to be
    // resolved against this environment before it can be read as one; handing
    // the raw colour to `colorFromComputed` makes Rust reinterpret it as a
    // signal and dereference whatever the misread layout points at.
    val resolvedPtr = NativeBindings.waterui_resolve_color(metadata.colorPtr, env.raw())
    NativeBindings.waterui_drop_color(metadata.colorPtr)

    val container = PassThroughFrameLayout(context)
    val density = context.resources.displayMetrics.density

    // The elevation shadow follows the view outline; the default provider is
    // the rectangular bounds, so without this a non-rectangular caster throws
    // a square-cornered shadow. The silhouette arrives as the same (kind,
    // commands) pair a clip shape carries, resolved by the shared builder.
    // `clipToOutline` stays off — the outline shapes the shadow only, children
    // may still draw outside it.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        container.elevation = metadata.radius.dp(context)
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width == 0 || view.height == 0) return
                outline.setPath(
                    buildShapePath(
                        metadata.silhouetteKind,
                        metadata.silhouetteCommands,
                        view.width.toFloat(),
                        view.height.toFloat(),
                        density
                    )
                )
            }
        }
    }

    val shadowDrawable = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        // Outlines are convex-only before API 30 (`setConvexPath` throws
        // `IllegalArgumentException: path must be convex` on a concave
        // silhouette), so the shadow is painted directly: an underlay sibling
        // inflated past the content's bounds by the shadow's reach, on a
        // software layer so `Paint.setShadowLayer` blurs, hosting a drawable
        // that draws the silhouette path. Every WaterUI container leaves
        // `clipChildren` unset, so the inflated underlay is not clipped.
        val blurPx = metadata.radius.dp(context)
        val dxPx = metadata.offsetX.dp(context)
        val dyPx = metadata.offsetY.dp(context)
        val extent = (ceil(blurPx * 3f) + ceil(max(abs(dxPx), abs(dyPx))) + 1f).toInt()
        SilhouetteShadowDrawable(
            metadata.silhouetteKind,
            metadata.silhouetteCommands,
            extent.toFloat(),
            blurPx,
            dxPx,
            dyPx,
            density
        ).also { drawable ->
            val underlay = View(context)
            underlay.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            underlay.background = drawable
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            lp.setMargins(-extent, -extent, -extent, -extent)
            container.addView(underlay, 0, lp)
        }
    } else {
        null
    }

    container.attachMetadataContent(context, metadata.contentPtr, env, registry)

    WuiComputed.colorFromComputed(resolvedPtr, env).also { color ->
        color.observe { resolvedColor ->
            val shadowColor = resolvedColor.toColorInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                container.outlineAmbientShadowColor = shadowColor
                container.outlineSpotShadowColor = shadowColor
            }
            shadowDrawable?.shadowColor = shadowColor
        }
        color.attachTo(container)
    }

    container
}

/**
 * Draws a shadow for the silhouette on API < 30, where `Outline` cannot take
 * an arbitrary path.
 *
 * The drawable's bounds are the host underlay's — the content rect inflated by
 * [inset] — and it fills the silhouette (offset inward by [inset] so it lands
 * on the content's footprint) with a transparent paint carrying a
 * `setShadowLayer` blur. Only the blurred silhouette shows; drawing it on the
 * underlay's software layer is what lets the blur render at all, and the
 * inflation gives the halo room outside the content rect.
 */
private class SilhouetteShadowDrawable(
    private val kind: ShapeKindStruct,
    private val commands: Array<PathCommandStruct>,
    private val inset: Float,
    private val blur: Float,
    private val dx: Float,
    private val dy: Float,
    private val density: Float
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private var path: Path? = null

    var shadowColor: Int = Color.BLACK
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: Rect) {
        path = null
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val width = bounds.width() - 2 * inset
        val height = bounds.height() - 2 * inset
        if (width <= 0f || height <= 0f) return
        val silhouette = path ?: buildShapePath(kind, commands, width, height, density)
            .also { path = it }
        canvas.withTranslation(bounds.left + inset, bounds.top + inset) {
            paint.setShadowLayer(blur, dx, dy, shadowColor)
            drawPath(silhouette, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT
}

internal fun RegistryBuilder.registerWuiShadow() {
    registerMetadata({ metadataShadowTypeId }, metadataShadowRenderer)
}
