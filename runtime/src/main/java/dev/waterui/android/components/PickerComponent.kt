package dev.waterui.android.components

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.PickerItemStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

import dev.waterui.android.runtime.toModel

private val pickerTypeId: WuiTypeId by lazy { NativeBindings.waterui_picker_id().toTypeId() }

private data class PickerOption(val tag: Int, val label: CharSequence)

/**
 * Picker style constants matching WuiPickerStyle enum values.
 */
private object PickerStyle {
    const val AUTOMATIC = 0
    const val MENU = 1
    const val RADIO = 2
}

private val pickerRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_picker(node.rawPtr)
    val binding = WuiBinding.int(struct.selectionPtr, env)
    val itemsComputed = WuiComputed.pickerItems(struct.itemsPtr, env)

    val options = mutableListOf<PickerOption>()
    var suppressSelectionEvent = false

    fun PickerItemStruct.resolve(): PickerOption {
        val styled = label.toModel()
        val text = styled.toCharSequence(env)
        styled.close()
        return PickerOption(tag, text)
    }

    when (struct.style) {
        PickerStyle.RADIO -> createRadioPicker(context, binding, itemsComputed, options, { suppressSelectionEvent }, { suppressSelectionEvent = it }, ::resolve)
        else -> createSpinnerPicker(context, binding, itemsComputed, options, { suppressSelectionEvent }, { suppressSelectionEvent = it }, ::resolve)
    }
}

/**
 * Creates a Spinner-based picker (used for Automatic and Menu styles).
 */
private fun createSpinnerPicker(
    context: Context,
    binding: WuiBinding<Int>,
    itemsComputed: WuiComputed<List<PickerItemStruct>>,
    options: MutableList<PickerOption>,
    getSuppressEvent: () -> Boolean,
    setSuppressEvent: (Boolean) -> Unit,
    resolve: PickerItemStruct.() -> PickerOption
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

    itemsComputed.observe { items ->
        val previousSelection = binding.current()
        options.clear()
        adapter.clear()
        items.forEach { item ->
            val option = item.resolve()
            options += option
            adapter.add(option.label)
        }
        adapter.notifyDataSetChanged()
        val index = options.indexOfFirst { it.tag == previousSelection }
        if (index >= 0) {
            setSuppressEvent(true)
            spinner.setSelection(index)
        }
    }

    binding.observe { value ->
        val index = options.indexOfFirst { it.tag == value }
        if (index >= 0 && spinner.selectedItemPosition != index) {
            setSuppressEvent(true)
            spinner.setSelection(index)
        }
    }

    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (getSuppressEvent()) {
                setSuppressEvent(false)
                return
            }
            options.getOrNull(position)?.let { binding.set(it.tag) }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    spinner.disposeWith(binding)
    spinner.disposeWith(itemsComputed)
    return spinner
}

/**
 * Creates a RadioGroup-based picker (used for Radio style).
 */
private fun createRadioPicker(
    context: Context,
    binding: WuiBinding<Int>,
    itemsComputed: WuiComputed<List<PickerItemStruct>>,
    options: MutableList<PickerOption>,
    getSuppressEvent: () -> Boolean,
    setSuppressEvent: (Boolean) -> Unit,
    resolve: PickerItemStruct.() -> PickerOption
): View {
    val radioGroup = RadioGroup(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    // Map from option tag to radio button ID
    val tagToButtonId = mutableMapOf<Int, Int>()

    itemsComputed.observe { items ->
        val previousSelection = binding.current()
        options.clear()
        radioGroup.removeAllViews()
        tagToButtonId.clear()

        items.forEachIndexed { index, item ->
            val option = item.resolve()
            options += option

            val radioButton = RadioButton(context).apply {
                id = View.generateViewId()
                text = option.label
                tag = option.tag
            }
            tagToButtonId[option.tag] = radioButton.id
            radioGroup.addView(radioButton)

            // Restore selection if this was the previously selected item
            if (option.tag == previousSelection) {
                setSuppressEvent(true)
                radioButton.isChecked = true
            }
        }
    }

    binding.observe { value ->
        val buttonId = tagToButtonId[value]
        if (buttonId != null && radioGroup.checkedRadioButtonId != buttonId) {
            setSuppressEvent(true)
            radioGroup.check(buttonId)
        }
    }

    radioGroup.setOnCheckedChangeListener { _, checkedId ->
        if (getSuppressEvent()) {
            setSuppressEvent(false)
            return@setOnCheckedChangeListener
        }
        val radioButton = radioGroup.findViewById<RadioButton>(checkedId)
        val tag = radioButton?.tag as? Int
        if (tag != null) {
            binding.set(tag)
        }
    }

    radioGroup.disposeWith(binding)
    radioGroup.disposeWith(itemsComputed)
    return radioGroup
}

internal fun RegistryBuilder.registerWuiPicker() {
    register({ pickerTypeId }, pickerRenderer)
}
