package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import org.json.JSONTokener

/**
 * WebView event types matching WuiWebViewEventType in FFI.
 */
enum class WebViewEventType(val value: Int) {
    NONE(0),
    WILL_NAVIGATE(1),
    LOADING(2),
    LOADED(3),
    REDIRECT(4),
    SSL_ERROR(5),
    ERROR(6),
    STATE_CHANGED(7);

    companion object {
        fun fromInt(value: Int): WebViewEventType = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/**
 * Script injection time matching WuiScriptInjectionTime in FFI.
 */
enum class ScriptInjectionTime(val value: Int) {
    DOCUMENT_START(0),
    DOCUMENT_END(1);

    companion object {
        fun fromInt(value: Int): ScriptInjectionTime = entries.firstOrNull { it.value == value } ?: DOCUMENT_START
    }
}

/**
 * WebView event callback interface.
 */
interface WebViewEventCallback {
    fun onEvent(
        eventType: Int,
        url: String,
        url2: String,
        message: String,
        progress: Float,
        canGoBack: Boolean,
        canGoForward: Boolean
    )
}

/**
 * JavaScript result callback interface.
 */
interface JsResultCallback {
    fun onResult(success: Boolean, result: String)
}

internal class NativeWebViewEventCallback(private val nativePtr: Long) : WebViewEventCallback {
    override fun onEvent(
        eventType: Int,
        url: String,
        url2: String,
        message: String,
        progress: Float,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        nativeOnEvent(nativePtr, eventType, url, url2, message, progress, canGoBack, canGoForward)
    }

    private external fun nativeOnEvent(
        nativePtr: Long,
        eventType: Int,
        url: String,
        url2: String,
        message: String,
        progress: Float,
        canGoBack: Boolean,
        canGoForward: Boolean
    )
}

object WebViewManager {
    private var contextRef: WeakReference<Context>? = null
    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        contextRef = WeakReference(context)
        appContext = context.applicationContext
    }

    @JvmStatic
    fun create(): WebViewWrapper {
        val ctx = contextRef?.get() ?: appContext
            ?: error("WebViewManager not initialized")
        return WebViewWrapper(ctx)
    }
}

/**
 * Wrapper around Android WebView that implements the WaterUI WebViewHandle interface.
 *
 * This class provides:
 * - Navigation controls (go_back, go_forward, go_to, stop, refresh)
 * - State queries (can_go_back, can_go_forward)
 * - Configuration (set_user_agent, inject_script)
 * - Event watching (navigation events, loading progress, errors)
 * - JavaScript execution (run_javascript)
 *
 * Note: Full integration with the FFI layer requires JNI support that converts
 * these Kotlin callbacks to C function pointers. See the TODO comments for details.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewWrapper(context: Context) {
    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private var eventCallback: WebViewEventCallback? = null
    private val injectedScripts = mutableListOf<Pair<String, ScriptInjectionTime>>()
    private val jsHandlers = mutableMapOf<String, (ByteArray) -> ByteArray>()
    private var redirectsEnabled = true
    private var currentUrl: String = ""

    init {
        setupWebViewClient()
        setupWebChromeClient()
    }

    // ========== Navigation ==========

    fun goBack() {
        runOnUiThread {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
    }

    fun goForward() {
        runOnUiThread {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }
    }

    fun goTo(url: String) {
        runOnUiThread { webView.loadUrl(url) }
    }

    fun stop() {
        runOnUiThread { webView.stopLoading() }
    }

    fun refresh() {
        runOnUiThread { webView.reload() }
    }

    // ========== State Queries ==========

    fun canGoBack(): Boolean = webView.canGoBack()

    fun canGoForward(): Boolean = webView.canGoForward()

    // ========== Configuration ==========

    fun setUserAgent(userAgent: String) {
        runOnUiThread { webView.settings.userAgentString = userAgent }
    }

    fun injectScript(script: String, time: ScriptInjectionTime) {
        runOnUiThread {
            injectedScripts.add(script to time)

            // If time is DOCUMENT_END and we have a loaded page, inject immediately
            if (time == ScriptInjectionTime.DOCUMENT_END) {
                webView.evaluateJavascript(script, null)
            }
            // DOCUMENT_START scripts are injected in onPageStarted
        }
    }

    fun injectScript(script: String, time: Int) {
        injectScript(script, ScriptInjectionTime.fromInt(time))
    }

    fun setRedirectsEnabled(enabled: Boolean) {
        runOnUiThread { redirectsEnabled = enabled }
    }

    // ========== Event Watching ==========

    fun setEventCallback(callback: WebViewEventCallback?) {
        runOnUiThread { eventCallback = callback }
    }

    private fun emitEvent(
        eventType: WebViewEventType,
        url: String = "",
        url2: String = "",
        message: String = "",
        progress: Float = 0f
    ) {
        eventCallback?.onEvent(
            eventType.value,
            url,
            url2,
            message,
            progress,
            webView.canGoBack(),
            webView.canGoForward()
        )
    }

    private fun emitStateChanged() {
        eventCallback?.onEvent(
            WebViewEventType.STATE_CHANGED.value,
            "",
            "",
            "",
            0f,
            webView.canGoBack(),
            webView.canGoForward()
        )
    }

    // ========== JavaScript Execution ==========

    fun runJavaScript(script: String, callback: JsResultCallback) {
        runOnUiThread {
            webView.evaluateJavascript(script) { result ->
                // Result is JSON-encoded (strings include quotes).
                val normalized = decodeJsResult(result)
                callback.onResult(true, normalized)
            }
        }
    }

    fun runJavaScript(script: String, callbackData: Long, callbackFn: Long) {
        runJavaScript(script, object : JsResultCallback {
            override fun onResult(success: Boolean, result: String) {
                nativeCompleteJsResult(callbackData, callbackFn, success, result)
            }
        })
    }

    // ========== JS-to-Native Handlers ==========

    fun addHandler(name: String, handler: (ByteArray) -> ByteArray) {
        runOnUiThread {
            jsHandlers[name] = handler

            // Add JavaScript interface
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun postMessage(data: String) {
                    val inputBytes = data.toByteArray(StandardCharsets.UTF_8)
                    val resultBytes = handler(inputBytes)
                    // Note: Sending response back to JS requires additional work
                }
            }, name)
        }
    }

    fun removeHandler(name: String) {
        runOnUiThread {
            jsHandlers.remove(name)
            webView.removeJavascriptInterface(name)
        }
    }

    // ========== Cleanup ==========

    fun release() {
        runOnUiThread {
            webView.stopLoading()
            webView.clearCache(true)
            webView.clearHistory()
            webView.destroy()
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            webView.post { action() }
        }
    }

    private fun decodeJsResult(raw: String?): String {
        val value = raw ?: "null"
        if (value == "null") {
            return value
        }
        return try {
            val decoded = JSONTokener(value).nextValue()
            when (decoded) {
                null -> "null"
                is String -> decoded
                else -> decoded.toString()
            }
        } catch (_: Exception) {
            value
        }
    }

    // ========== Private Setup ==========

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url ?: currentUrl

                // Inject DOCUMENT_START scripts
                injectedScripts
                    .filter { it.second == ScriptInjectionTime.DOCUMENT_START }
                    .forEach { (script, _) ->
                        view?.evaluateJavascript(script, null)
                    }

                emitEvent(WebViewEventType.WILL_NAVIGATE, url = url ?: "")
                emitStateChanged()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentUrl = url ?: currentUrl

                // Inject DOCUMENT_END scripts
                injectedScripts
                    .filter { it.second == ScriptInjectionTime.DOCUMENT_END }
                    .forEach { (script, _) ->
                        view?.evaluateJavascript(script, null)
                    }

                emitEvent(WebViewEventType.LOADED, url = url ?: "")
                emitStateChanged()
            }

            @Deprecated("Deprecated in API 23")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                emitEvent(
                    WebViewEventType.ERROR,
                    url = failingUrl ?: "",
                    message = description ?: "Unknown error"
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    emitEvent(
                        WebViewEventType.ERROR,
                        url = request.url?.toString() ?: "",
                        message = error?.description?.toString() ?: "Unknown error"
                    )
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // Don't call handler.proceed() in production - it's a security risk
                handler?.cancel()
                emitEvent(
                    WebViewEventType.SSL_ERROR,
                    url = error?.url ?: "",
                    message = "SSL certificate error: ${error?.primaryError}"
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Let the WebView handle all HTTP/HTTPS URLs
                val url = request?.url?.toString() ?: return false
                val isRedirect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    request.isRedirect
                if (isRedirect) {
                    emitEvent(WebViewEventType.REDIRECT, url = currentUrl, url2 = url)
                    if (!redirectsEnabled) {
                        return true
                    }
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true
            }
        }
    }

    private fun setupWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) {
                    emitEvent(
                        WebViewEventType.LOADING,
                        progress = newProgress / 100f
                    )
                }
            }
        }
    }

    private external fun nativeCompleteJsResult(
        callbackData: Long,
        callbackFn: Long,
        success: Boolean,
        result: String
    )
}

// ========== WebView Renderer ==========

private val webViewTypeId: WuiTypeId by lazy { NativeBindings.waterui_webview_id().toTypeId() }

private val webViewRenderer = WuiRenderer { context, node, _, _ ->
    val webview = NativeBindings.waterui_force_as_webview(node.rawPtr)
    val handlePtr = NativeBindings.waterui_webview_native_handle(webview.webviewPtr)
    val view = NativeBindings.waterui_webview_native_view(handlePtr)

    if (view == null) {
        NativeBindings.waterui_drop_web_view(webview.webviewPtr)
        return@WuiRenderer TextView(context).apply {
            text = "WebView not available"
        }
    }

    (view.parent as? ViewGroup)?.removeView(view)
    view.disposeWith { NativeBindings.waterui_drop_web_view(webview.webviewPtr) }
    view
}

internal fun RegistryBuilder.registerWuiWebView() {
    register({ webViewTypeId }, webViewRenderer)
}
