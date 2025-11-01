package dev.waterui.android.runtime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Constraints

/**
 * Mirrors the `WuiProposalSize` struct exposed by the C ABI.
 *
 * `Float.NaN` means "unspecified".
 */
data class ProposalStruct(
    val width: Float,
    val height: Float
) {
    companion object {
        fun fromConstraints(constraints: Constraints): ProposalStruct {
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.NaN
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.NaN
            return ProposalStruct(width, height)
        }
    }

    fun toConstraints(): Constraints {
        val minWidth = if (width.isNaN()) 0 else width.toInt()
        val maxWidth = if (width.isNaN()) Constraints.Infinity else width.toInt()
        val minHeight = if (height.isNaN()) 0 else height.toInt()
        val maxHeight = if (height.isNaN()) Constraints.Infinity else height.toInt()
        return Constraints(
            minWidth = minWidth,
            maxWidth = maxWidth,
            minHeight = minHeight,
            maxHeight = maxHeight
        )
    }
}

/**
 * Mirrors `WuiChildMetadata`.
 */
data class ChildMetadataStruct(
    val proposal: ProposalStruct,
    val priority: Int,
    val stretch: Boolean
)

/**
 * Mirrors `WuiSize`.
 */
data class SizeStruct(
    val width: Float,
    val height: Float
) {
    fun toSize(): Size = Size(width, height)
}

/**
 * Mirrors `WuiRect`.
 */
data class RectStruct(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    fun toOffset(): Offset = Offset(x, y)
    fun toSize(): Size = Size(width, height)

    companion object {
        fun fromOffsetAndSize(offset: Offset, size: Size): RectStruct =
            RectStruct(offset.x, offset.y, size.width, size.height)
    }
}
