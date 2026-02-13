package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.ImageView
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.toColorInt

private val systemIconTypeId: WuiTypeId by lazy { WatcherJni.systemIconId().toTypeId() }

private fun resolveSystemIconResId(name: String): Int {
    val base = name.removeSuffix(".fill")
    return when (base) {
        "house" -> android.R.drawable.ic_menu_view
        "gear", "gearshape" -> android.R.drawable.ic_menu_manage
        "magnifyingglass" -> android.R.drawable.ic_menu_search
        "chevron.left", "arrow.left" -> android.R.drawable.ic_media_previous
        "chevron.right", "arrow.right" -> android.R.drawable.ic_media_next
        "chevron.up", "arrow.up" -> android.R.drawable.arrow_up_float
        "chevron.down", "arrow.down" -> android.R.drawable.arrow_down_float
        "plus", "plus.circle" -> android.R.drawable.ic_input_add
        "minus", "minus.circle" -> android.R.drawable.ic_delete
        "trash" -> android.R.drawable.ic_menu_delete
        "xmark", "xmark.circle" -> android.R.drawable.ic_menu_close_clear_cancel
        "checkmark", "checkmark.circle" -> android.R.drawable.checkbox_on_background
        "pencil", "square.and.pencil" -> android.R.drawable.ic_menu_edit
        "person", "person.circle" -> android.R.drawable.ic_menu_myplaces
        "star", "star.circle" -> if (name.endsWith(".fill")) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        "heart", "heart.circle" -> if (name.endsWith(".fill")) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        "play" -> android.R.drawable.ic_media_play
        "pause" -> android.R.drawable.ic_media_pause
        "stop" -> android.R.drawable.ic_media_pause
        "envelope" -> android.R.drawable.ic_dialog_email
        "phone" -> android.R.drawable.ic_menu_call
        "message" -> android.R.drawable.ic_dialog_email
        "bell" -> android.R.drawable.ic_dialog_info
        else -> android.R.drawable.ic_menu_help
    }
}

private val systemIconRenderer = WuiRenderer { context, node, env, _ ->
    val struct = WatcherJni.forceAsSystemIcon(node.rawPtr)
    val resId = resolveSystemIconResId(struct.name)

    val imageView = ImageView(context).apply {
        setImageResource(resId)
        adjustViewBounds = true
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val foreground = ThemeBridge.foreground(env)
    foreground.observe { color ->
        imageView.imageTintList = ColorStateList.valueOf(color.toColorInt())
    }
    foreground.attachTo(imageView)

    imageView
}

internal fun RegistryBuilder.registerWuiSystemIcon() {
    register({ systemIconTypeId }, systemIconRenderer)
}
