package dev.waterui.android.runtime

import android.app.Activity
import android.app.Dialog
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import java.lang.ref.WeakReference

/**
 * Runtime multi-window implementation for Android.
 *
 * Android doesn't have multiple top-level windows in the same sense as desktop platforms,
 * so WaterUI maps "windows" to separate dialogs hosted by the current Activity.
 *
 * This is invoked from native code via JNI when Rust renders a `Window` view.
 */
object WindowManager {
    private const val TAG = "WaterUI.WindowManager"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hostViewRef: WeakReference<View>? = null
    private var appEnvPtr: Long = 0L

    /**
     * Attach the current host view and app environment.
     *
     * Called by [WaterUiRootView] during initialization and render.
     */
    fun attachHost(view: View, envPtr: Long) {
        hostViewRef = WeakReference(view)
        appEnvPtr = envPtr
    }

    /**
     * Called from C++ (via JNI) to display a new window.
     *
     * All pointers are owned and must be released by the native side once the window closes.
     */
    @JvmStatic
    fun showWindow(
        titlePtr: Long,
        closable: Boolean,
        resizable: Boolean,
        contentPtr: Long,
        statePtr: Long,
        style: Int,
        backgroundTag: Int,
        backgroundColorPtr: Long
    ) {
        mainHandler.post {
            val hostView = hostViewRef?.get()
            val activity = hostView?.findActivity()
            if (hostView == null || activity == null || appEnvPtr == 0L) {
                android.util.Log.w(TAG, "showWindow: missing host/activity/env, dropping pointers")
                dropIncomingPointers(titlePtr, contentPtr, statePtr, backgroundTag, backgroundColorPtr)
                return@post
            }

            val env = WuiEnvironment.borrowed(appEnvPtr)
            val registry = RenderRegistry.default()

            val session = WindowSession(
                activity = activity,
                env = env,
                registry = registry,
                titlePtr = titlePtr,
                closable = closable,
                resizable = resizable,
                contentPtr = contentPtr,
                statePtr = statePtr,
                style = WindowStyle.fromInt(style),
                backgroundTag = backgroundTag,
                backgroundColorPtr = backgroundColorPtr
            )

            session.show()
        }
    }

    private fun dropIncomingPointers(
        titlePtr: Long,
        contentPtr: Long,
        statePtr: Long,
        backgroundTag: Int,
        backgroundColorPtr: Long
    ) {
        if (titlePtr != 0L) {
            WatcherJni.dropComputedStr(titlePtr)
        }
        if (statePtr != 0L) {
            WatcherJni.dropBindingWindowState(statePtr)
        }
        if (contentPtr != 0L) {
            WatcherJni.dropAnyview(contentPtr)
        }
        if (backgroundTag != 0 && backgroundColorPtr != 0L) {
            WatcherJni.dropColor(backgroundColorPtr)
        }
    }
}

private class WindowSession(
    private val activity: Activity,
    private val env: WuiEnvironment,
    private val registry: RenderRegistry,
    private val titlePtr: Long,
    private val closable: Boolean,
    @Suppress("UNUSED_PARAMETER") private val resizable: Boolean,
    private val contentPtr: Long,
    private val statePtr: Long,
    private val style: WindowStyle,
    private val backgroundTag: Int,
    private val backgroundColorPtr: Long
) {
    private var title: WuiComputed<String>? = null
    private var state: WuiBinding<Int>? = null
    private var dialog: Dialog? = null

    fun show() {
        val dialog = Dialog(activity)
        this.dialog = dialog

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView: TextView? = if (style == WindowStyle.TITLED || style == WindowStyle.FULL_SIZE_CONTENT_VIEW) {
            TextView(activity).also { tv ->
                tv.textSize = 18f
                val padding = (12f * activity.resources.displayMetrics.density).toInt()
                tv.setPadding(padding, padding, padding, padding)
                root.addView(
                    tv,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        } else {
            null
        }

        val contentContainer = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Consume the AnyView pointer by inflating it into an Android view.
        val contentView = inflateAnyView(activity, contentPtr, env, registry)
        contentContainer.addView(
            contentView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(contentContainer)

        dialog.setContentView(root)
        dialog.setCancelable(closable)
        dialog.setCanceledOnTouchOutside(closable)

        if (!closable) {
            dialog.setOnKeyListener { _, _, _ -> true }
        }

        // Apply background once (if provided).
        applyBackground(dialog)

        // Title updates.
        if (titlePtr != 0L) {
            title = WuiComputed.string(titlePtr, env).also { computed ->
                computed.observe { newTitle ->
                    titleView?.text = newTitle
                    dialog.setTitle(newTitle)
                }
            }
        }

        // State binding (close/minimize/fullscreen).
        if (statePtr != 0L) {
            state = WuiBinding.windowState(statePtr, env).also { binding ->
                binding.observe { value ->
                    when (WindowState.fromInt(value)) {
                        WindowState.CLOSED -> {
                            if (dialog.isShowing) {
                                dialog.dismiss()
                            }
                        }
                        WindowState.MINIMIZED -> {
                            if (dialog.isShowing) {
                                dialog.hide()
                            }
                        }
                        WindowState.FULLSCREEN -> {
                            dialog.show()
                            dialog.window?.setLayout(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                        WindowState.NORMAL -> {
                            if (!dialog.isShowing) {
                                dialog.show()
                            }
                            dialog.window?.setLayout(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            // Propagate native close to Rust and release resources.
            state?.set(WindowState.CLOSED.value)
            close()
        }

        dialog.show()
    }

    private fun applyBackground(dialog: Dialog) {
        if (backgroundTag == 1 && backgroundColorPtr != 0L) { // WuiWindowBackground_Color
            try {
                val resolved = WuiComputed.resolvedColor(backgroundColorPtr, env)
                val colorInt = resolved.current().toColorInt()
                dialog.window?.setBackgroundDrawable(ColorDrawable(colorInt))
                resolved.close()
            } finally {
                WatcherJni.dropColor(backgroundColorPtr)
            }
        }
    }

    private fun close() {
        dialog = null

        title?.close()
        title = null

        state?.close()
        state = null

        env.close()
    }
}

private fun View.findActivity(): Activity? {
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

