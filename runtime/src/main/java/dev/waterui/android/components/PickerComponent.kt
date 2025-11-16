package dev.waterui.android.components

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.PickerItemStruct
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId
import dev.waterui.android.runtime.toModel

private val pickerTypeId: WuiTypeId by lazy { NativeBindings.waterui_picker_id().toTypeId() }

private data class PickerOption(val tag: Int, val label: CharSequence)

private val pickerRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_picker(node.rawPtr)
    val binding = WuiBinding.int(struct.selectionPtr, env)
    val itemsComputed = WuiComputed.pickerItems(struct.itemsPtr, env)

    val spinner = Spinner(context)
    val adapter = ArrayAdapter<CharSequence>(
        context,
        android.R.layout.simple_spinner_item,
        arrayListOf()
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    spinner.adapter = adapter

    val options = mutableListOf<PickerOption>()
    var suppressSelectionEvent = false

    fun PickerItemStruct.resolve(): PickerOption {
        val styled = label.toModel()
        val text = styled.toCharSequence(env)
        styled.close()
        return PickerOption(tag, text)
    }

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

    spinner.disposeWith(binding)
    spinner.disposeWith(itemsComputed)
    spinner
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiPicker() {
    register({ pickerTypeId }, pickerRenderer)
}
