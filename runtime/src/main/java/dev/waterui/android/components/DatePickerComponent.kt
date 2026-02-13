package dev.waterui.android.components

import android.app.DatePickerDialog
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.DatePickerType
import dev.waterui.android.runtime.DateStruct
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val datePickerTypeId: WuiTypeId by lazy { WatcherJni.datePickerId().toTypeId() }

private val datePickerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = WatcherJni.forceAsDatePicker(node.rawPtr)
    val binding = WuiBinding.date(struct.valuePtr, env)
    val pickerType = struct.type()
    val range = struct.range

    val density = context.resources.displayMetrics.density
    val spacingPx = (8 * density).toInt()

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // Add label if present
    if (struct.labelPtr != 0L) {
        val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
        val labelParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginEnd = spacingPx
        }
        container.addView(labelView, labelParams)
    }

    // Date/time button
    val dateButton = MaterialButton(context).apply {
        minHeight = (44 * density).toInt()
    }

    container.addView(dateButton)

    // Format helpers
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun formatDate(date: DateStruct): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.month - 1)
            set(Calendar.DAY_OF_MONTH, date.day)
        }
        // Android backend currently only supports date (year/month/day). Time-based picker
        // variants in the FFI are treated as date-only.
        return dateFormat.format(calendar.time)
    }

    fun updateButtonText() {
        dateButton.text = formatDate(binding.current())
    }

    updateButtonText()

    binding.observe { _ ->
        updateButtonText()
    }

    dateButton.setOnClickListener {
        val current = binding.current()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, current.year)
            set(Calendar.MONTH, current.month - 1)
            set(Calendar.DAY_OF_MONTH, current.day)
        }

        when (pickerType) {
            DatePickerType.DATE,
            DatePickerType.HOUR_AND_MINUTE,
            DatePickerType.HOUR_MINUTE_AND_SECOND,
            DatePickerType.DATE_HOUR_AND_MINUTE,
            DatePickerType.DATE_HOUR_MINUTE_AND_SECOND -> {
                DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        binding.set(DateStruct(year, month + 1, dayOfMonth))
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                ).apply {
                    // Set min/max dates
                    val minCal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, range.start.year)
                        set(Calendar.MONTH, range.start.month - 1)
                        set(Calendar.DAY_OF_MONTH, range.start.day)
                    }
                    val maxCal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, range.end.year)
                        set(Calendar.MONTH, range.end.month - 1)
                        set(Calendar.DAY_OF_MONTH, range.end.day)
                    }
                    datePicker.minDate = minCal.timeInMillis
                    datePicker.maxDate = maxCal.timeInMillis
                }.show()
            }
        }
    }

    // Apply theme colors
    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        dateButton.setBackgroundColor(color.toColorInt())
    }
    accent.attachTo(container)

    val accentForeground = ThemeBridge.accentForeground(env)
    accentForeground.observe { color ->
        dateButton.setTextColor(color.toColorInt())
    }
    accentForeground.attachTo(container)

    container.disposeWith(binding)
    container
}

internal fun RegistryBuilder.registerWuiDatePicker() {
    register({ datePickerTypeId }, datePickerRenderer)
}
