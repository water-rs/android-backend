package dev.waterui.android.components

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NativeViewCollection
import dev.waterui.android.runtime.NativeViewItem
import dev.waterui.android.runtime.PickerStyle
import dev.waterui.android.runtime.ReactiveStyledText
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import java.io.Closeable

private val pickerTypeId: WuiTypeId by lazy { NativeBindings.waterui_picker_id().toTypeId() }
private val pickerItemTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_picker_item_id().toTypeId()
}

private class PickerOption(
    val tag: Int,
    labelPtr: Long,
    env: WuiEnvironment
) : Closeable {
    private val label = ReactiveStyledText(labelPtr, env)
    var text: CharSequence = ""
        private set

    fun attach(update: (CharSequence) -> Unit) {
        label.attach { value ->
            text = value
            update(value)
        }
    }

    override fun close() {
        label.close()
    }
}

private val pickerRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_picker(node.rawPtr)
    val selection = WuiBinding.id(struct.selectionPtr)
    val items = NativeViewCollection(
        handle = struct.itemsPtr,
        expectedType = pickerItemTypeId
    ) { viewPtr ->
        val item = NativeBindings.waterui_force_as_picker_item(viewPtr)
        check(item.labelPtr != 0L) { "picker item has no label signal" }
        PickerOption(item.tag, item.labelPtr, env)
    }

    val view = when (PickerStyle.fromInt(struct.style)) {
        PickerStyle.AUTOMATIC,
        PickerStyle.MENU -> buildMenuPicker(context, selection, items)
        PickerStyle.RADIO -> buildRadioPicker(context, selection, items)
        PickerStyle.SEGMENTED -> buildSegmentedPicker(context, selection, items)
    }
    view.disposeWith {
        items.close()
        selection.close()
    }
    view
}

private fun buildMenuPicker(
    context: Context,
    selection: WuiBinding<Int>,
    source: NativeViewCollection<PickerOption>
): View {
    // Compose M3's menu picker is the exposed dropdown menu: a read-only
    // filled text field with a trailing chevron opening an elevated M3 menu.
    // The legacy Spinner used framework item layouts and no Material chrome.
    val layout = TextInputLayout(
        context,
        null,
        com.google.android.material.R.attr.textInputFilledExposedDropdownMenuStyle
    ).apply { isHintEnabled = false }
    val field = MaterialAutoCompleteTextView(layout.context).apply {
        inputType = InputType.TYPE_NULL
    }
    layout.addView(
        field,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    )

    var options = emptyList<PickerOption>()
    var selectedTag: Int? = null
    var reconciling = false

    fun syncSelection() {
        if (options.isEmpty()) {
            return
        }
        val tag = requireNotNull(selectedTag) { "picker selection did not initialize" }
        val index = options.indexOfFirst { it.tag == tag }
        require(index >= 0) { "picker selection tag $tag is not present" }
        field.setText(options[index].text, false)
    }

    fun publishLabels() {
        if (reconciling) {
            return
        }
        field.setSimpleItems(options.map { it.text.toString() }.toTypedArray())
        syncSelection()
    }

    selection.observe { tag ->
        selectedTag = tag
        syncSelection()
    }
    source.observe { items ->
        reconciling = true
        options = items.map { it.value }
        val tags = HashSet<Int>(options.size)
        options.forEach { option ->
            check(tags.add(option.tag)) { "picker contains duplicate tag ${option.tag}" }
            option.attach { publishLabels() }
        }
        reconciling = false
        publishLabels()
    }
    field.setOnItemClickListener { _, _, position, _ ->
        val tag = options[position].tag
        if (tag != selectedTag) {
            selection.set(tag)
        }
    }
    return layout
}

private fun buildSegmentedPicker(
    context: Context,
    selection: WuiBinding<Int>,
    source: NativeViewCollection<PickerOption>
): View {
    val group = MaterialButtonToggleGroup(context).apply {
        isSingleSelection = true
        isSelectionRequired = true
    }
    return buildChoicePicker(
        group = group,
        selection = selection,
        source = source,
        createButton = {
            // Segmented buttons need the outlined style for the group to draw
            // its shared outline and inter-segment dividers; default filled
            // buttons render as a row of separate pills.
            MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                id = View.generateViewId()
                isCheckable = true
            }
        },
        checkedId = group::getCheckedButtonId,
        check = group::check,
        installListener = { onChecked ->
            group.addOnButtonCheckedListener { _, buttonId, checked ->
                if (checked) {
                    onChecked(buttonId)
                }
            }
        }
    )
}

private fun buildRadioPicker(
    context: Context,
    selection: WuiBinding<Int>,
    source: NativeViewCollection<PickerOption>
): View {
    val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
    return buildChoicePicker(
        group = group,
        selection = selection,
        source = source,
        createButton = {
            MaterialRadioButton(context).apply { id = View.generateViewId() }
        },
        checkedId = group::getCheckedRadioButtonId,
        check = group::check,
        installListener = { onChecked ->
            group.setOnCheckedChangeListener { _, buttonId ->
                if (buttonId != View.NO_ID) {
                    onChecked(buttonId)
                }
            }
        }
    )
}

private fun <Group : ViewGroup, Button : TextView> buildChoicePicker(
    group: Group,
    selection: WuiBinding<Int>,
    source: NativeViewCollection<PickerOption>,
    createButton: () -> Button,
    checkedId: () -> Int,
    check: (Int) -> Unit,
    installListener: ((Int) -> Unit) -> Unit
): Group {
    val buttonsByItemId = mutableMapOf<Int, Button>()
    val tagsByButtonId = mutableMapOf<Int, Int>()
    var options = emptyList<PickerOption>()
    var selectedTag: Int? = null

    fun syncSelection() {
        if (options.isEmpty()) {
            return
        }
        val tag = requireNotNull(selectedTag) { "picker selection did not initialize" }
        val buttonId = tagsByButtonId.entries.firstOrNull { it.value == tag }?.key
            ?: error("picker selection tag $tag is not present")
        if (checkedId() != buttonId) {
            check(buttonId)
        }
    }

    selection.observe { tag ->
        selectedTag = tag
        syncSelection()
    }
    source.observe { items ->
        val aliveIds = items.mapTo(HashSet(items.size)) { it.id }
        buttonsByItemId.keys.retainAll(aliveIds)
        tagsByButtonId.clear()
        group.removeAllViews()
        options = items.map { it.value }
        val tags = HashSet<Int>(options.size)
        items.forEach { item ->
            val option = item.value
            check(tags.add(option.tag)) { "picker contains duplicate tag ${option.tag}" }
            val button = buttonsByItemId.getOrPut(item.id, createButton)
            option.attach { button.text = it }
            tagsByButtonId[button.id] = option.tag
            group.addView(button)
        }
        syncSelection()
    }
    installListener { buttonId ->
        val tag = tagsByButtonId[buttonId]
            ?: error("picker selected an unknown native button id $buttonId")
        if (tag != selectedTag) {
            selection.set(tag)
        }
    }
    return group
}

internal fun RegistryBuilder.registerWuiPicker() {
    register({ pickerTypeId }, pickerRenderer)
}
