package dev.waterui.android.components

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.Spinner
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.radiobutton.MaterialRadioButton
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.PickerItemStruct
import dev.waterui.android.runtime.PickerStyle
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.toModel

private val pickerTypeId: WuiTypeId by lazy { NativeBindings.waterui_picker_id().toTypeId() }

private data class PickerOption(val tag: Int, val label: CharSequence)

private fun PickerItemStruct.resolve(env: WuiEnvironment): PickerOption {
    val styled = label.toModel()
    val text = styled.toCharSequence(env)
    styled.close()
    return PickerOption(tag, text)
}

private val pickerRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_picker(node.rawPtr)
    val binding = WuiBinding.int(struct.selectionPtr, env)
    val itemsComputed = WuiComputed.pickerItems(struct.itemsPtr, env)
    val style = PickerStyle.fromInt(struct.style)

    val options = mutableListOf<PickerOption>()

    val view = when (style) {
        PickerStyle.AUTOMATIC -> buildSegmentedPicker(context, env, binding, itemsComputed, options)
        PickerStyle.MENU -> buildMenuPicker(context, env, binding, itemsComputed, options)
        PickerStyle.RADIO -> buildRadioPicker(context, env, binding, itemsComputed, options)
    }

    view.disposeWith(binding)
    view.disposeWith(itemsComputed)
    view
}

private fun buildMenuPicker(
    context: android.content.Context,
    env: WuiEnvironment,
    binding: WuiBinding<Int>,
    itemsComputed: WuiComputed<List<PickerItemStruct>>,
    options: MutableList<PickerOption>
): View {
    val spinner = Spinner(context)
    val adapter = ArrayAdapter<CharSequence>(
        context,
        android.R.layout.simple_spinner_item,
        arrayListOf()
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    spinner.adapter = adapter

    var suppressSelectionEvent = false

    itemsComputed.observe { items ->
        val previousSelection = binding.current()
        options.clear()
        adapter.clear()
        items.forEach { item ->
            val option = item.resolve(env)
            options += option
            adapter.add(option.label)
        }
        adapter.notifyDataSetChanged()
        val index = options.indexOfFirst { it.tag == previousSelection }
        if (index >= 0) {
            suppressSelectionEvent = true
            spinner.setSelection(index)
        }
    }

    binding.observe { value ->
        val index = options.indexOfFirst { it.tag == value }
        if (index >= 0 && spinner.selectedItemPosition != index) {
            suppressSelectionEvent = true
            spinner.setSelection(index)
        }
    }

    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (suppressSelectionEvent) {
                suppressSelectionEvent = false
                return
            }
            options.getOrNull(position)?.let { binding.set(it.tag) }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    return spinner
}

private fun buildSegmentedPicker(
    context: android.content.Context,
    env: WuiEnvironment,
    binding: WuiBinding<Int>,
    itemsComputed: WuiComputed<List<PickerItemStruct>>,
    options: MutableList<PickerOption>
): View {
    val group = MaterialButtonToggleGroup(context).apply {
        isSingleSelection = true
        isSelectionRequired = true
    }
    val idsByTag = mutableMapOf<Int, Int>()
    var suppressSelectionEvent = false

    itemsComputed.observe { items ->
        val previousSelection = binding.current()
        options.clear()
        idsByTag.clear()
        group.removeAllViews()

        items.forEach { item ->
            val option = item.resolve(env)
            val button = MaterialButton(context).apply {
                id = View.generateViewId()
                text = option.label
                isCheckable = true
            }
            options += option
            idsByTag[option.tag] = button.id
            group.addView(button)
        }

        idsByTag[previousSelection]?.let { buttonId ->
            suppressSelectionEvent = true
            group.check(buttonId)
        }
    }

    binding.observe { value ->
        val buttonId = idsByTag[value] ?: return@observe
        if (group.checkedButtonId != buttonId) {
            suppressSelectionEvent = true
            group.check(buttonId)
        }
    }

    group.addOnButtonCheckedListener { _, checkedId, isChecked ->
        if (!isChecked) {
            return@addOnButtonCheckedListener
        }
        if (suppressSelectionEvent) {
            suppressSelectionEvent = false
            return@addOnButtonCheckedListener
        }
        val selectedTag = idsByTag.entries.firstOrNull { it.value == checkedId }?.key ?: return@addOnButtonCheckedListener
        binding.set(selectedTag)
    }

    return group
}

private fun buildRadioPicker(
    context: android.content.Context,
    env: WuiEnvironment,
    binding: WuiBinding<Int>,
    itemsComputed: WuiComputed<List<PickerItemStruct>>,
    options: MutableList<PickerOption>
): View {
    val group = RadioGroup(context).apply {
        orientation = RadioGroup.VERTICAL
    }
    val idsByTag = mutableMapOf<Int, Int>()
    var suppressSelectionEvent = false

    itemsComputed.observe { items ->
        val previousSelection = binding.current()
        options.clear()
        idsByTag.clear()
        group.removeAllViews()

        items.forEach { item ->
            val option = item.resolve(env)
            val radio = MaterialRadioButton(context).apply {
                id = View.generateViewId()
                text = option.label
            }
            options += option
            idsByTag[option.tag] = radio.id
            group.addView(radio)
        }

        idsByTag[previousSelection]?.let { radioId ->
            suppressSelectionEvent = true
            group.check(radioId)
        }
    }

    binding.observe { value ->
        val radioId = idsByTag[value] ?: return@observe
        if (group.checkedRadioButtonId != radioId) {
            suppressSelectionEvent = true
            group.check(radioId)
        }
    }

    group.setOnCheckedChangeListener { _, checkedId ->
        if (checkedId == View.NO_ID) {
            return@setOnCheckedChangeListener
        }
        if (suppressSelectionEvent) {
            suppressSelectionEvent = false
            return@setOnCheckedChangeListener
        }
        val selectedTag = idsByTag.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
        binding.set(selectedTag)
    }

    return group
}

internal fun RegistryBuilder.registerWuiPicker() {
    register({ pickerTypeId }, pickerRenderer)
}
