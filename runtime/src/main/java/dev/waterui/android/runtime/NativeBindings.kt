package dev.waterui.android.runtime

internal object NativeBindings {
    init {
        NativeLibraryLoader.ensureLoaded()
    }

    external fun waterui_init(): Long
    external fun waterui_main(): Long
    external fun waterui_view_id(anyViewPtr: Long): String
    external fun waterui_view_body(anyViewPtr: Long, envPtr: Long): Long
    external fun waterui_clone_env(envPtr: Long): Long
    external fun waterui_drop_env(envPtr: Long)
    external fun waterui_drop_anyview(viewPtr: Long)

    // View/component identifiers
    external fun waterui_empty_id(): String
    external fun waterui_text_id(): String
    external fun waterui_plain_id(): String
    external fun waterui_button_id(): String
    external fun waterui_color_id(): String
    external fun waterui_text_field_id(): String
    external fun waterui_stepper_id(): String
    external fun waterui_progress_id(): String
    external fun waterui_dynamic_id(): String
    external fun waterui_scroll_view_id(): String
    external fun waterui_spacer_id(): String
    external fun waterui_toggle_id(): String
    external fun waterui_slider_id(): String
    external fun waterui_renderer_view_id(): String
    external fun waterui_layout_container_id(): String
    external fun waterui_fixed_container_id(): String

    // Layout negotiation helpers
    external fun waterui_layout_propose(
        layoutPtr: Long,
        parent: ProposalStruct,
        children: Array<ChildMetadataStruct>
    ): Array<ProposalStruct>

    external fun waterui_layout_size(
        layoutPtr: Long,
        parent: ProposalStruct,
        children: Array<ChildMetadataStruct>
    ): SizeStruct

    external fun waterui_layout_place(
        layoutPtr: Long,
        bounds: RectStruct,
        proposal: ProposalStruct,
        children: Array<ChildMetadataStruct>
    ): Array<RectStruct>
}
