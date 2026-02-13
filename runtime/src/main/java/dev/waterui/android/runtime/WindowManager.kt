package dev.waterui.android.runtime

import android.app.Activity
import android.app.Dialog
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private val state = WindowManagerState()
    private val sessions = ConcurrentHashMap<Long, WindowSession>()
    private val nextSessionId = AtomicLong(1L)
    private var hostViewRef: WeakReference<View>? = null

    /**
     * Attach the current host view and app environment.
     *
     * Called by [WaterUiRootView] during initialization and render.
     */
    fun attachHost(view: View, envPtr: Long) {
        hostViewRef = WeakReference(view)
        val attachResult = state.attach(System.identityHashCode(view), envPtr)
        for (sessionId in attachResult.replacedSessions) {
            val session = sessions.remove(sessionId) ?: continue
            session.closeFromHostDetach()
        }
    }

    /**
     * Detach the host and invalidate all pending/new window requests.
     *
     * Called by [WaterUiRootView] before dropping the app environment.
     */
    fun detachHost(view: View? = null, envPtr: Long = 0L) {
        val detachResult = state.detach(view?.let { System.identityHashCode(it) }, envPtr)
        if (!detachResult.detached) {
            return
        }
        hostViewRef = null
        for (sessionId in detachResult.sessionsToClose) {
            val session = sessions.remove(sessionId) ?: continue
            session.closeFromHostDetach()
        }
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
        toolbarPtr: Long,
        statePtr: Long,
        style: Int,
        backgroundTag: Int,
        backgroundColorPtr: Long
    ) {
        val expectedGeneration = state.captureGeneration()
        mainHandler.post {
            val hostView = hostViewRef?.get()
            val resolvedActivity = hostView?.findActivity()
            val decision = state.evaluateDispatch(
                expectedGeneration = expectedGeneration,
                hasHost = hostView != null,
                hostAttached = hostView?.isAttachedToWindow == true,
                hasActivity = resolvedActivity != null
            )
            if (decision != WindowDispatchDecision.ACCEPT) {
                android.util.Log.w(TAG, "showWindow: dropping window request ($decision)")
                dropIncomingPointers(titlePtr, contentPtr, toolbarPtr, statePtr, backgroundTag, backgroundColorPtr)
                return@post
            }
            val activity = resolvedActivity ?: run {
                dropIncomingPointers(titlePtr, contentPtr, toolbarPtr, statePtr, backgroundTag, backgroundColorPtr)
                return@post
            }

            val envPtr = state.currentEnvPtr()
            val sessionId = nextSessionId.getAndIncrement()
            if (!state.registerSession(sessionId, expectedGeneration)) {
                android.util.Log.w(TAG, "showWindow: generation changed before registration, dropping pointers")
                dropIncomingPointers(titlePtr, contentPtr, toolbarPtr, statePtr, backgroundTag, backgroundColorPtr)
                return@post
            }

            val env = WuiEnvironment.borrowed(envPtr)
            val registry = RenderRegistry.default()

            val session = WindowSession(
                activity = activity,
                env = env,
                registry = registry,
                titlePtr = titlePtr,
                closable = closable,
                resizable = resizable,
                contentPtr = contentPtr,
                toolbarPtr = toolbarPtr,
                statePtr = statePtr,
                style = WindowStyle.fromInt(style),
                backgroundTag = backgroundTag,
                backgroundColorPtr = backgroundColorPtr,
                onClosed = {
                    sessions.remove(sessionId)
                    state.unregisterSession(sessionId)
                }
            )
            sessions[sessionId] = session

            try {
                session.show()
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "showWindow: failed to show window session", t)
                sessions.remove(sessionId)
                state.unregisterSession(sessionId)
                session.closeFromHostDetach()
            }
        }
    }

    private fun dropIncomingPointers(
        titlePtr: Long,
        contentPtr: Long,
        toolbarPtr: Long,
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
        if (toolbarPtr != 0L) {
            WatcherJni.dropAnyview(toolbarPtr)
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
    private val toolbarPtr: Long,
    private val statePtr: Long,
    private val style: WindowStyle,
    private val backgroundTag: Int,
    private val backgroundColorPtr: Long,
    private val onClosed: () -> Unit
) {
    private var title: WuiComputed<String>? = null
    private var state: WuiBinding<Int>? = null
    private var background: WuiComputed<ResolvedColorStruct>? = null
    private var dialog: Dialog? = null
    private var closed = false
    private var propagateDismissToState = true

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

        val titleView: TextView? = if (toolbarPtr == 0L && (style == WindowStyle.TITLED || style == WindowStyle.FULL_SIZE_CONTENT_VIEW)) {
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

        if (toolbarPtr != 0L) {
            val toolbarContainer = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val toolbarView = inflateAnyView(activity, toolbarPtr, env, registry)
            toolbarContainer.addView(
                toolbarView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(toolbarContainer)
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

        // Apply background (if provided); keeps reactive updates alive for this session.
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
            if (propagateDismissToState) {
                state?.set(WindowState.CLOSED.value)
            }
            close()
        }

        dialog.show()
    }

    private fun applyBackground(dialog: Dialog) {
        if (backgroundTag == 1 && backgroundColorPtr != 0L) { // WuiWindowBackground_Color
            val resolved = WuiComputed.resolvedColor(backgroundColorPtr, env)
            background = resolved
            resolved.observe { color ->
                dialog.window?.setBackgroundDrawable(ColorDrawable(color.toColorInt()))
            }
        }

        // The incoming Color pointer is consumed by resolveColor (or unused for unsupported tags).
        if (backgroundColorPtr != 0L) {
            WatcherJni.dropColor(backgroundColorPtr)
        }
    }

    fun closeFromHostDetach() {
        propagateDismissToState = false
        val currentDialog = dialog
        if (currentDialog == null || !currentDialog.isShowing) {
            close()
            return
        }
        currentDialog.dismiss()
    }

    private fun close() {
        if (closed) {
            return
        }
        closed = true
        dialog = null

        title?.close()
        title = null

        state?.close()
        state = null

        background?.close()
        background = null

        env.close()
        onClosed()
    }
}
