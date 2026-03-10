package dev.waterui.android.components

import android.app.DatePickerDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.DatePickerStruct
import dev.waterui.android.runtime.DatePickerType
import dev.waterui.android.runtime.DateTimeStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val datePickerTypeId: WuiTypeId by lazy { NativeBindings.waterui_date_picker_id().toTypeId() }

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val timeWithSecondsFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val dateTimeWithSecondsFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private val datePickerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_date_picker(node.rawPtr)
    val binding = WuiBinding.dateTime(struct.valuePtr, env)
    val pickerType = DatePickerType.fromInt(struct.type)
    val rangeStart = struct.rangeStart.toLocalDateTime()
    val rangeEnd = struct.rangeEnd.toLocalDateTime()

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

    fun updateButton(value: DateTimeStruct) {
        button.text = formatValue(value.toLocalDateTime(), pickerType)
    }

    button.setOnClickListener {
        val current = binding.current().toLocalDateTime()
        presentPicker(
            context = context,
            current = current,
            pickerType = pickerType,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
        ) { picked ->
            binding.set(picked.clamp(rangeStart, rangeEnd).toStruct())
        }
    }

    binding.observe(::updateButton)
    container.disposeWith(binding)
    container
}

private fun presentPicker(
    context: android.content.Context,
    current: LocalDateTime,
    pickerType: DatePickerType,
    rangeStart: LocalDateTime,
    rangeEnd: LocalDateTime,
    onPicked: (LocalDateTime) -> Unit
) {
    when (pickerType) {
        DatePickerType.DATE -> {
            showDateDialog(context, current.toLocalDate(), rangeStart.toLocalDate(), rangeEnd.toLocalDate()) { date ->
                onPicked(LocalDateTime.of(date, current.toLocalTime()))
            }
        }
        DatePickerType.HOUR_AND_MINUTE -> {
            showTimeDialog(context, current.toLocalTime(), includeSeconds = false) { time ->
                onPicked(LocalDateTime.of(current.toLocalDate(), time))
            }
        }
        DatePickerType.HOUR_MINUTE_AND_SECOND -> {
            showTimeDialog(context, current.toLocalTime(), includeSeconds = true) { time ->
                onPicked(LocalDateTime.of(current.toLocalDate(), time))
            }
        }
        DatePickerType.DATE_HOUR_AND_MINUTE -> {
            showDateDialog(context, current.toLocalDate(), rangeStart.toLocalDate(), rangeEnd.toLocalDate()) { date ->
                showTimeDialog(context, current.toLocalTime(), includeSeconds = false) { time ->
                    onPicked(LocalDateTime.of(date, time))
                }
            }
        }
        DatePickerType.DATE_HOUR_MINUTE_AND_SECOND -> {
            showDateDialog(context, current.toLocalDate(), rangeStart.toLocalDate(), rangeEnd.toLocalDate()) { date ->
                showTimeDialog(context, current.toLocalTime(), includeSeconds = true) { time ->
                    onPicked(LocalDateTime.of(date, time))
                }
            }
        }
    }
}

private fun showDateDialog(
    context: android.content.Context,
    initial: LocalDate,
    min: LocalDate,
    max: LocalDate,
    onPicked: (LocalDate) -> Unit
) {
    val dialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            onPicked(LocalDate.of(year, month + 1, dayOfMonth))
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    )
    dialog.datePicker.minDate = min.atStartOfDay(zone()).toInstant().toEpochMilli()
    dialog.datePicker.maxDate = max.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli() - 1
    dialog.show()
}

private fun showTimeDialog(
    context: android.content.Context,
    initial: LocalTime,
    includeSeconds: Boolean,
    onPicked: (LocalTime) -> Unit
) {
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(24, 24, 24, 0)
    }
    val hourPicker = newPicker(context, 0, 23, initial.hour)
    val minutePicker = newPicker(context, 0, 59, initial.minute)
    val secondPicker = newPicker(context, 0, 59, initial.second)

    layout.addView(hourPicker)
    layout.addView(minutePicker)
    if (includeSeconds) {
        layout.addView(secondPicker)
    }

    MaterialAlertDialogBuilder(context)
        .setTitle(if (includeSeconds) "Select Time" else "Select Time")
        .setView(layout)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val time = LocalTime.of(
                hourPicker.value,
                minutePicker.value,
                if (includeSeconds) secondPicker.value else 0,
            )
            onPicked(time)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun newPicker(
    context: android.content.Context,
    min: Int,
    max: Int,
    value: Int
): NumberPicker {
    return NumberPicker(context).apply {
        minValue = min
        maxValue = max
        this.value = value.coerceIn(min, max)
        wrapSelectorWheel = false
    }
}

private fun formatValue(value: LocalDateTime, type: DatePickerType): String {
    return when (type) {
        DatePickerType.DATE -> dateFormatter.format(value.toLocalDate())
        DatePickerType.HOUR_AND_MINUTE -> timeFormatter.format(value.toLocalTime())
        DatePickerType.HOUR_MINUTE_AND_SECOND -> timeWithSecondsFormatter.format(value.toLocalTime())
        DatePickerType.DATE_HOUR_AND_MINUTE -> dateTimeFormatter.format(value)
        DatePickerType.DATE_HOUR_MINUTE_AND_SECOND -> dateTimeWithSecondsFormatter.format(value)
    }
}

private fun DateTimeStruct.toLocalDateTime(): LocalDateTime {
    return LocalDateTime.of(year, month, day, hour, minute, second)
}

private fun LocalDateTime.toStruct(): DateTimeStruct {
    return DateTimeStruct(
        year = year,
        month = monthValue,
        day = dayOfMonth,
        hour = hour,
        minute = minute,
        second = second,
    )
}

private fun LocalDateTime.clamp(min: LocalDateTime, max: LocalDateTime): LocalDateTime {
    return when {
        this < min -> min
        this > max -> max
        else -> this
    }
}

private fun zone(): ZoneId = ZoneId.systemDefault()

internal fun RegistryBuilder.registerWuiDatePicker() {
    register({ datePickerTypeId }, datePickerRenderer)
}
