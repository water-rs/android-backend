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
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private val multiDatePickerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_multi_date_picker_id().toTypeId()
}

private val multiDatePickerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_multi_date_picker(node.rawPtr)
    val binding = WuiBinding.dateVec(struct.valuePtr)
    val decorated = WuiComputed.dateVec(struct.decoratedPtr)
    val rangeStart = struct.rangeStart.toLocalDate()
    val rangeEnd = struct.rangeEnd.toLocalDate()
    require(rangeStart <= rangeEnd) { "multi-date picker range must not be empty" }

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
    installSemanticAccessibilityLabel(
        target = button,
        content = labelView,
        labelPtr = struct.accessibilityLabelPtr,
        env = env
    )

    fun updateButton(selected: Array<DateStruct>, decoratedDates: List<DateStruct>) {
        val summary = formatSelectionSummary(
            context,
            selected.map(DateStruct::toLocalDate),
            decoratedDates.map(DateStruct::toLocalDate).toSet()
        )
        button.text = summary
    }

    var currentSelection: Array<DateStruct> = emptyArray()
    var currentDecorated: List<DateStruct> = emptyList()

    fun refreshButton() {
        updateButton(currentSelection, currentDecorated)
    }

    binding.observe { selected ->
        require(selected.all { it.toLocalDate() in rangeStart..rangeEnd }) {
            "multi-date picker selection contains a date outside its range"
        }
        currentSelection = selected
        refreshButton()
    }
    decorated.observe { decoratedDates ->
        require(decoratedDates.all { it.toLocalDate() in rangeStart..rangeEnd }) {
            "multi-date picker decoration contains a date outside its range"
        }
        currentDecorated = decoratedDates
        refreshButton()
    }

    button.setOnClickListener {
        presentMultiDateDialog(
            context = context,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            initialSelection = currentSelection.map(DateStruct::toLocalDate),
            decoratedDates = currentDecorated.map(DateStruct::toLocalDate).toSet(),
        ) { selected ->
            binding.set(selected.sorted().map(LocalDate::toStruct).toTypedArray())
        }
    }

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
        chipSpacingHorizontal = 8f.dp(context).roundToInt()
        chipSpacingVertical = 8f.dp(context).roundToInt()
    }

    fun refreshChips() {
        chips.removeAllViews()
        selectedDates.sorted().forEach { date ->
            val chip = Chip(context).apply {
                text = formatDate(context, date)
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
        val padding = 24f.dp(context).roundToInt()
        setPadding(padding, padding, padding, 0)
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
        .setTitle(R.string.wui_select_dates)
        .setView(content)
        .setNeutralButton(R.string.wui_clear) { _, _ ->
            onPicked(emptySet())
        }
        .setPositiveButton(android.R.string.ok) { _, _ ->
            onPicked(selectedDates)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun formatSelectionSummary(
    context: android.content.Context,
    selected: List<LocalDate>,
    decorated: Set<LocalDate>
): String {
    if (selected.isEmpty()) {
        return if (decorated.isEmpty()) {
            context.getString(R.string.wui_select_dates)
        } else {
            context.resources.getQuantityString(
                R.plurals.wui_select_dates_marked,
                decorated.size,
                decorated.size
            )
        }
    }
    return when (selected.size) {
        1 -> formatDate(context, selected.first())
        2 -> "${formatDate(context, selected[0])}, ${formatDate(context, selected[1])}"
        else -> context.resources.getQuantityString(
            R.plurals.wui_dates_selected,
            selected.size,
            selected.size
        )
    }
}

private fun formatDate(context: android.content.Context, date: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(context.resources.configuration.locales[0])
        .format(date)

private fun DateStruct.toLocalDate(): LocalDate = LocalDate.of(year, month, day)

private fun LocalDate.toStruct(): DateStruct = DateStruct(year, monthValue, dayOfMonth)

private fun zone(): ZoneId = ZoneId.systemDefault()

internal fun RegistryBuilder.registerWuiMultiDatePicker() {
    register({ multiDatePickerTypeId }, multiDatePickerRenderer)
}
