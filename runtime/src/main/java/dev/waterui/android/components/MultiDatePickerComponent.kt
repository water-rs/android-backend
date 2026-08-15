package dev.waterui.android.components

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.DateStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyResolvedFont
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private val multiDatePickerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_multi_date_picker_id().toTypeId()
}

// Compose-style multi-date selection: the running selection lives inline as
// removable M3 input chips, and dates are added (or toggled off) through the
// native MaterialDatePicker dialog. The previous realization embedded the
// pre-Material framework CalendarView in a hand-built dialog.
private val multiDatePickerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_multi_date_picker(node.rawPtr)
    val binding = WuiBinding.dateVec(struct.valuePtr)
    val decorated = WuiComputed.dateVec(struct.decoratedPtr)
    val rangeStart = struct.rangeStart.toLocalDate()
    val rangeEnd = struct.rangeEnd.toLocalDate()
    require(rangeStart <= rangeEnd) { "multi-date picker range must not be empty" }

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    val headerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    val button = MaterialButton(context).apply {
        text = context.getString(R.string.wui_select_dates)
    }
    headerRow.addView(
        labelView,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    headerRow.addView(button)
    container.addView(
        headerRow,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    val helperText = TextView(context)
    val helperFont = ThemeBridge.bodyFont(env)
    helperFont.observe(helperText::applyResolvedFont)
    helperFont.attachTo(helperText)
    val helperColor = ThemeBridge.mutedForeground(env)
    helperColor.observe { color -> helperText.setTextColor(color.toColorInt()) }
    helperColor.attachTo(helperText)
    container.addView(helperText)

    val chips = ChipGroup(context).apply {
        isSingleLine = false
        chipSpacingHorizontal = 8f.dp(context).roundToInt()
        chipSpacingVertical = 8f.dp(context).roundToInt()
    }
    container.addView(
        chips,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    installSemanticAccessibilityLabel(
        target = button,
        content = labelView,
        labelPtr = struct.accessibilityLabelPtr,
        env = env
    )

    var currentSelection: List<LocalDate> = emptyList()
    var currentDecorated: Set<LocalDate> = emptySet()

    fun publish(selection: Collection<LocalDate>) {
        val ordered = selection.sorted()
        binding.set(Array(ordered.size) { index -> ordered[index].toStruct() })
    }

    fun refresh() {
        helperText.text = context.getString(
            R.string.wui_multi_date_picker_helper,
            currentDecorated.size
        )
        helperText.visibility = if (currentDecorated.isEmpty()) View.GONE else View.VISIBLE
        chips.removeAllViews()
        currentSelection.forEach { date ->
            val chip = Chip(context).apply {
                text = formatDate(context, date)
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    publish(currentSelection - date)
                }
            }
            chips.addView(chip)
        }
    }

    binding.observe { selected ->
        val dates = selected.map(DateStruct::toLocalDate)
        require(dates.all { it in rangeStart..rangeEnd }) {
            "multi-date picker selection contains a date outside its range"
        }
        currentSelection = dates.sorted()
        refresh()
    }
    decorated.observe { decoratedDates ->
        val dates = decoratedDates.mapTo(HashSet(decoratedDates.size), DateStruct::toLocalDate)
        require(dates.all { it in rangeStart..rangeEnd }) {
            "multi-date picker decoration contains a date outside its range"
        }
        currentDecorated = dates
        refresh()
    }

    button.setOnClickListener {
        showMaterialDatePicker(
            context = context,
            initial = currentSelection.firstOrNull() ?: rangeStart,
            min = rangeStart,
            max = rangeEnd
        ) { picked ->
            // Picking an already-selected date toggles it off, mirroring the
            // toggle semantics of an inline multi-select calendar.
            if (picked in currentSelection) {
                publish(currentSelection - picked)
            } else {
                publish(currentSelection + picked)
            }
        }
    }

    container.disposeWith(binding)
    container.disposeWith(decorated)
    container
}

private fun formatDate(context: android.content.Context, date: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(context.resources.configuration.locales[0])
        .format(date)

private fun DateStruct.toLocalDate(): LocalDate = LocalDate.of(year, month, day)

private fun LocalDate.toStruct(): DateStruct = DateStruct(year, monthValue, dayOfMonth)

internal fun RegistryBuilder.registerWuiMultiDatePicker() {
    register({ multiDatePickerTypeId }, multiDatePickerRenderer)
}
