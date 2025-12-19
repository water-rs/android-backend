package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.waterui.android.runtime.disposeWith
import java.nio.charset.StandardCharsets

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

    init {
        setupWebViewClient()
        setupWebChromeClient()
    }

    // ========== Navigation ==========

    fun goBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    fun goForward() {
        if (webView.canGoForward()) {
            webView.goForward()
        }
    }

    fun goTo(url: String) {
        webView.loadUrl(url)
    }

    fun stop() {
        webView.stopLoading()
    }

    fun refresh() {
        webView.reload()
    }

    // ========== State Queries ==========

    fun canGoBack(): Boolean = webView.canGoBack()

    fun canGoForward(): Boolean = webView.canGoForward()

    // ========== Configuration ==========

    fun setUserAgent(userAgent: String) {
        webView.settings.userAgentString = userAgent
    }

    fun injectScript(script: String, time: ScriptInjectionTime) {
        injectedScripts.add(script to time)

        // If time is DOCUMENT_END and we have a loaded page, inject immediately
        if (time == ScriptInjectionTime.DOCUMENT_END) {
            webView.evaluateJavascript(script, null)
        }
        // DOCUMENT_START scripts are injected in onPageStarted
    }

    // ========== Event Watching ==========

    fun setEventCallback(callback: WebViewEventCallback?) {
        this.eventCallback = callback
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
        webView.evaluateJavascript(script) { result ->
            // Result is a JSON string or "null"
            callback.onResult(true, result ?: "null")
        }
    }

    // ========== JS-to-Native Handlers ==========

    fun addHandler(name: String, handler: (ByteArray) -> ByteArray) {
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

    fun removeHandler(name: String) {
        jsHandlers.remove(name)
        webView.removeJavascriptInterface(name)
    }

    // ========== Cleanup ==========

    fun release() {
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearHistory()
        webView.destroy()
    }

    // ========== Private Setup ==========

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

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
}

// ========== WebView Controller Installation ==========

/**
 * TODO: Implement full JNI integration for WebView controller.
 *
 * The FFI layer expects:
 * 1. waterui_env_install_webview_controller(env, create_fn)
 *    - create_fn: C function pointer that returns WuiWebViewHandle
 *
 * 2. WuiWebViewHandle struct with function pointers:
 *    - go_back, go_forward, go_to, stop, refresh
 *    - can_go_back, can_go_forward
 *    - set_user_agent, set_redirects_enabled, inject_script
 *    - watch (set event callback)
 *    - run_javascript
 *    - drop
 *
 * Implementation would require:
 * 1. JNI functions in waterui_jni.cpp to create trampolines
 * 2. A global registry of WebViewWrapper instances
 * 3. Callback conversion from C function pointers to JNI calls
 *
 * For now, WebView is not fully integrated on Android.
 * The Kotlin wrapper (WebViewWrapper) is ready for integration.
 */

// Placeholder for future JNI integration
// fun installWebViewController(envPtr: Long) {
//     NativeBindings.waterui_env_install_webview_controller(envPtr, ...)
// }
