package dev.waterui.android.runtime

/**
 * A view hosting WaterUI-managed content that accepts the size proposal a Rust
 * parent layout selected for its slot during placement.
 *
 * The shared layout contract carries the selected proposal next to each
 * child's resolved frame: two children may be assigned identical bounds yet
 * have been negotiated under different proposals. A view that lays out WaterUI
 * content of its own — a nested `RustLayoutViewGroup`, or a transparent
 * wrapper around one — must receive the proposal the Rust parent selected,
 * not one reconstructed from its final frame.
 */
interface WuiProposalAware {
    /**
     * Delivers the proposal the enclosing Rust layout selected for this view,
     * in density-independent points. [Float.NaN] marks an axis the layout left
     * unspecified; [Float.POSITIVE_INFINITY] marks an unbounded axis.
     */
    fun setWuiSelectedProposal(proposalWidth: Float, proposalHeight: Float)
}

/**
 * A view that answers a Rust layout probe itself, by the WaterUI layout
 * contract, instead of being squeezed through [android.view.View.MeasureSpec].
 *
 * Two kinds implement it. A view hosting WaterUI content — a nested
 * `RustLayoutViewGroup`, or a transparent wrapper standing in its content's
 * slot — forwards the probe losslessly, keeping an unspecified axis (NaN)
 * distinct from an unbounded one (infinity) and carrying the alignment
 * guides the nested layout reports. And a leaf whose contract answer a
 * MeasureSpec cannot express — text, which a height proposal must never cap,
 * or a labelled control, whose content negotiates the offer minus its
 * platform chrome — computes its own.
 */
interface WuiMeasurableLayout {
    fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct
}

/**
 * A view whose WaterUI slot traits — the stretch axis and layout priority a
 * Rust parent layout reads — come from live state, not a tag fixed when the
 * view was inflated.
 *
 * Two kinds implement it. A transparent wrapper stands in its content's slot,
 * so it answers as the content answers now, whatever the content has become
 * since the wrapper attached. And a `RustLayoutViewGroup` recomputes
 * `Layout::stretch_axis(children)` on every read: the contract takes the
 * children as an argument precisely so a layout never works from a copy that
 * went stale the moment its membership changed.
 */
interface WuiLiveSlotTraits {
    /** The stretch answer this view gives a WaterUI parent right now. */
    fun resolveWuiStretchAxis(): StretchAxis

    /** The layout priority this view reports absent an explicit override. */
    fun resolveWuiLayoutPriority(): Int = 0
}
