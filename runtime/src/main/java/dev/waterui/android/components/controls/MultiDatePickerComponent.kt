package dev.waterui.android.components

import android.view.Gravity
import android.view.ViewGroup
import android.widget.CalendarView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.DateStruct
import dev.waterui.android.runtime.MultiDatePickerStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val multiDatePickerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_multi_date_picker_id().toTypeId()
}

private val multiDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private val multiDatePickerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_multi_date_picker(node.rawPtr)
    val binding = WuiBinding.dateVec(struct.valuePtr, env)
    val decorated = WuiComputed.dateVec(struct.decoratedPtr, env)
    val rangeStart = struct.rangeStart.toLocalDate()
    val rangeEnd = struct.rangeEnd.toLocalDate()

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    val button = MaterialButton(context)

    container.addView(
        labelView,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    container.addView(button)

    fun updateButton(selected: Array<DateStruct>, decoratedDates: List<DateStruct>) {
        val summary = formatSelectionSummary(
            selected.map(DateStruct::toLocalDate),
            decoratedDates.map(DateStruct::toLocalDate).toSet()
        )
        button.text = summary
    }

    fun currentDecorated(): Set<LocalDate> = decorated.current().map(DateStruct::toLocalDate).toSet()

    button.setOnClickListener {
        presentMultiDateDialog(
            context = context,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            initialSelection = binding.current().map(DateStruct::toLocalDate),
            decoratedDates = currentDecorated(),
        ) { selected ->
            binding.set(selected.sorted().map(LocalDate::toStruct).toTypedArray())
        }
    }

    binding.observe { selected ->
        updateButton(selected, decorated.current())
    }
    decorated.observe { decoratedDates ->
        updateButton(binding.current(), decoratedDates)
    }
    updateButton(binding.current(), decorated.current())

    container.disposeWith(binding)
    container.disposeWith(decorated)
    container
}

private fun presentMultiDateDialog(
    context: android.content.Context,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    initialSelection: List<LocalDate>,
    decoratedDates: Set<LocalDate>,
    onPicked: (Set<LocalDate>) -> Unit
) {
    val selectedDates = initialSelection.toMutableSet()

    val calendarView = CalendarView(context).apply {
        minDate = rangeStart.atStartOfDay(zone()).toInstant().toEpochMilli()
        maxDate = rangeEnd.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli() - 1
        val initial = initialSelection.firstOrNull() ?: rangeStart
        date = initial.atStartOfDay(zone()).toInstant().toEpochMilli()
    }

    val helperText = TextView(context).apply {
        text = context.getString(
            R.string.wui_multi_date_picker_helper,
            decoratedDates.size
        )
    }

    val chips = ChipGroup(context).apply {
        isSingleLine = false
        chipSpacingHorizontal = 8
        chipSpacingVertical = 8
    }

    fun refreshChips() {
        chips.removeAllViews()
        selectedDates.sorted().forEach { date ->
            val chip = Chip(context).apply {
                text = multiDateFormatter.format(date)
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    selectedDates.remove(date)
                    refreshChips()
                }
            }
            chips.addView(chip)
        }
    }

    calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
        val selected = LocalDate.of(year, month + 1, dayOfMonth)
        if (!selectedDates.add(selected)) {
            selectedDates.remove(selected)
        }
        refreshChips()
    }

    refreshChips()

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 24, 24, 0)
        addView(helperText)
        addView(
            calendarView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            ScrollView(context).apply {
                addView(
                    chips,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    MaterialAlertDialogBuilder(context)
        .setTitle("Select Dates")
        .setView(content)
        .setNeutralButton("Clear") { _, _ ->
            onPicked(emptySet())
        }
        .setPositiveButton(android.R.string.ok) { _, _ ->
            onPicked(selectedDates)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun formatSelectionSummary(selected: List<LocalDate>, decorated: Set<LocalDate>): String {
    if (selected.isEmpty()) {
        return if (decorated.isEmpty()) {
            "Select dates"
        } else {
            "Select dates (${decorated.size} marked)"
        }
    }
    return when (selected.size) {
        1 -> multiDateFormatter.format(selected.first())
        2 -> "${multiDateFormatter.format(selected[0])}, ${multiDateFormatter.format(selected[1])}"
        else -> "${selected.size} dates selected"
    }
}

private fun DateStruct.toLocalDate(): LocalDate = LocalDate.of(year, month, day)

private fun LocalDate.toStruct(): DateStruct = DateStruct(year, monthValue, dayOfMonth)

private fun zone(): ZoneId = ZoneId.systemDefault()

internal fun RegistryBuilder.registerWuiMultiDatePicker() {
    register({ multiDatePickerTypeId }, multiDatePickerRenderer)
}
