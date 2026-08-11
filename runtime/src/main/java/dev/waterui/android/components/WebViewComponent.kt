package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.annotation.RawRes
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import org.json.JSONObject

private val webViewTypeId: WuiTypeId by lazy { NativeBindings.waterui_web_view_id().toTypeId() }

private val webViewRenderer = WuiRenderer { context, node, _, _ ->
    val struct = NativeBindings.waterui_force_as_web_view(node.rawPtr)
    val handlePtr = NativeBindings.waterui_webview_native_handle(struct.webviewPtr)
    check(handlePtr != 0L) { "Android WebView backend returned null native handle" }

    val webView = NativeBindings.waterui_webview_native_view(handlePtr)
    (webView.parent as? ViewGroup)?.removeView(webView)

    WebViewHostView(context, webView).apply {
        disposeWith {
            NativeBindings.waterui_drop_web_view(struct.webviewPtr)
        }
    }
}

@SuppressLint("ViewConstructor")
private class WebViewHostView(
    context: Context,
    private val webView: WebView
) : FrameLayout(context) {
    init {
        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val defaultWidthPx = DEFAULT_WIDTH_DP.dp(context).toInt()
        val defaultHeightPx = DEFAULT_HEIGHT_DP.dp(context).toInt()

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
            else -> defaultWidthPx
        }
        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> heightSize
            else -> defaultHeightPx
        }

        val childWidthSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        webView.measure(childWidthSpec, childHeightSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        webView.layout(0, 0, right - left, bottom - top)
    }

    companion object {
        private const val DEFAULT_WIDTH_DP = 320f
        private const val DEFAULT_HEIGHT_DP = 480f
    }
}

@Keep
class WebViewFactory(context: Context) {
    private val applicationContext = context.applicationContext

    fun create(): WebViewWrapper = WebViewWrapper(applicationContext)
}

@Keep
interface WebViewEventCallback {
    fun onEvent(
        eventType: Int,
        url: String?,
        url2: String?,
        message: String?,
        progress: Float,
        canGoBack: Boolean,
        canGoForward: Boolean
    )
}

@Keep
class NativeWebViewEventCallback(
    private val nativePtr: Long
) : WebViewEventCallback {
    override fun onEvent(
        eventType: Int,
        url: String?,
        url2: String?,
        message: String?,
        progress: Float,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        nativeOnEvent(
            nativePtr,
            eventType,
            url,
            url2,
            message,
            progress,
            canGoBack,
            canGoForward
        )
    }

    private external fun nativeOnEvent(
        nativePtr: Long,
        eventType: Int,
        url: String?,
        url2: String?,
        message: String?,
        progress: Float,
        canGoBack: Boolean,
        canGoForward: Boolean
    )
}

@Keep
@SuppressLint("SetJavaScriptEnabled")
class WebViewWrapper(
    context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridgeHandler = Handler(Looper.getMainLooper())
    private val cookieManager = CookieManager.getInstance()
    private val documentStartScripts = mutableMapOf<Long, ScriptHandler>()
    private val documentEndScripts = linkedMapOf<Long, String>()
    private val handlerNativePtrs = linkedMapOf<String, Long>()
    private val handlerScriptIds = linkedMapOf<String, Long>()
    private val webView = WebView(context)
    private val baseBridgeScript = context.readRawText(R.raw.waterui_webview_bridge)
    private val handlerScriptTemplate = context.readRawText(R.raw.waterui_webview_handler)
    private val evalScriptTemplate = context.readRawText(R.raw.waterui_webview_eval)
    private var eventCallback: WebViewEventCallback? = null
    private var redirectPolicy: WuiComputed<Boolean>? = null
    private var redirectsEnabled = true
    private var lastNavigationUrl: String? = null
    private var nextScriptId = 1L
    private var bridgeInstalled = false
    private val bridge = JsBridge()

    init {
        cookieManager.setAcceptCookie(true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.addJavascriptInterface(bridge, BRIDGE_OBJECT)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                emitLoading(newProgress / 100f)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) {
                    return false
                }

                val targetUrl = request.url.toString()
                val isRedirect = request.isRedirect
                if (!redirectsEnabled && isRedirect) {
                    emitRedirect(currentUrl(), targetUrl)
                    return true
                }

                emitWillNavigate(targetUrl, allowRepeat = isRedirect)
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                emitWillNavigate(
                    requireNotNull(url) { "WebView started a navigation without a URL" },
                    allowRepeat = false
                )
                emitLoading(0f)
                emitStateChanged()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                documentEndScripts.values.forEach { script ->
                    view.evaluateJavascript(script, null)
                }
                emitEvent(
                    eventType = EVENT_LOADED,
                    progress = 1f
                )
                emitStateChanged()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) {
                    return
                }
                emitEvent(
                    eventType = EVENT_ERROR,
                    message = requireNotNull(error.description) {
                        "WebView reported a main-frame error without a description"
                    }.toString()
                )
                emitStateChanged()
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                emitEvent(
                    eventType = EVENT_SSL_ERROR,
                    url = requireNotNull(error.url) {
                        "WebView reported an SSL error without a URL"
                    },
                    message = error.toString()
                )
                handler.cancel()
            }
        }
    }

    fun goBack() {
        webView.goBack()
    }

    fun goForward() {
        webView.goForward()
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

    fun canGoBack(): Boolean = webView.canGoBack()

    fun canGoForward(): Boolean = webView.canGoForward()

    fun setUserAgent(userAgent: String) {
        webView.settings.userAgentString = userAgent
    }

    fun setRedirectsEnabled(computedPtr: Long) {
        redirectPolicy?.close()
        redirectPolicy = WuiComputed.bool(computedPtr).also { policy ->
            policy.observe { redirectsEnabled = it }
        }
    }

    fun setEventCallback(callback: WebViewEventCallback?) {
        eventCallback = callback
    }

    @SuppressLint("RequiresFeature")
    fun injectScript(script: String, time: Int) {
        injectScriptOnMain(script, time)
    }

    fun addHandler(name: String, nativePtr: Long) {
        // Re-registering a name replaces the handler, so drop the previous wrapper
        // script instead of stacking a second copy on every page load.
        removeHandlerScript(name)
        handlerNativePtrs[name] = nativePtr
        ensureBridgeInstalled()
        handlerScriptIds[name] =
            injectScriptOnMain(buildHandlerScript(name), SCRIPT_INJECTION_TIME_DOCUMENT_START)
    }

    fun removeHandler(name: String) {
        handlerNativePtrs.remove(name)
        // Without this the wrapper stays injected and the page keeps a callable
        // `window.<name>` whose messages have no handler behind them.
        removeHandlerScript(name)
    }

    private fun removeHandlerScript(name: String) {
        val scriptId = handlerScriptIds.remove(name) ?: return
        documentStartScripts.remove(scriptId)?.remove()
        documentEndScripts.remove(scriptId)
    }

    fun setCookie(cookie: String) {
        cookieManager.setCookie(currentUrl(), cookie)
        cookieManager.flush()
    }

    fun getCookies(callbackData: Long, callbackFn: Long) {
        val url = currentUrl()
        mainHandler.post {
            val cookies = cookieManager.getCookie(url)
                ?.split(';')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.joinToString("\n")
                .orEmpty()
            nativeCompleteCookies(callbackData, callbackFn, cookies)
        }
    }

    fun runJavaScript(script: String, callbackData: Long, callbackFn: Long) {
        val wrapped = evalScriptTemplate.replace(EVAL_SOURCE_TOKEN, JSONObject.quote(script))
        webView.evaluateJavascript(wrapped) { result ->
            val outcome = parseEvalOutcome(result)
            nativeCompleteJsResult(
                callbackData,
                callbackFn,
                outcome.success,
                outcome.payload
            )
        }
    }

    private class EvalOutcome(val success: Boolean, val payload: String)

    /**
     * Decodes the envelope produced by `waterui_webview_eval.js`.
     *
     * `evaluateJavascript` reports nothing when a script throws, so without the
     * envelope a failure was indistinguishable from a null result and every
     * evaluation was reported as a success.
     */
    private fun parseEvalOutcome(raw: String?): EvalOutcome {
        if (raw == null) {
            return EvalOutcome(false, "WebView returned no result")
        }
        // evaluateJavascript JSON-encodes the wrapper's own return value, so the
        // envelope arrives as a quoted string.
        val envelopeText = try {
            JSONObject("{\"e\":$raw}").getString("e")
        } catch (error: org.json.JSONException) {
            Log.w(LOG_TAG, "WebView eval returned a malformed envelope", error)
            return EvalOutcome(false, "WebView returned a malformed result")
        }
        return try {
            val envelope = JSONObject(envelopeText)
            if (envelope.optBoolean("ok", false)) {
                EvalOutcome(true, envelope.optString("value", "null"))
            } else {
                EvalOutcome(false, envelope.optString("message", "JavaScript evaluation failed"))
            }
        } catch (error: org.json.JSONException) {
            Log.w(LOG_TAG, "WebView eval envelope was not an object", error)
            EvalOutcome(false, "WebView returned a malformed result")
        }
    }

    fun getWebView(): WebView = webView

    fun resolveMessage(requestId: String, success: Boolean, payloadBase64: String) {
        val js = "window.__wateruiResolve(${JSONObject.quote(requestId)}, ${if (success) "true" else "false"}, ${JSONObject.quote(payloadBase64)});"
        webView.evaluateJavascript(js, null)
    }

    fun release() {
        bridgeHandler.removeCallbacksAndMessages(null)
        redirectPolicy?.close()
        redirectPolicy = null
        eventCallback = null
        handlerNativePtrs.clear()
        handlerScriptIds.clear()
        documentStartScripts.values.forEach(ScriptHandler::remove)
        documentStartScripts.clear()
        documentEndScripts.clear()
        webView.removeJavascriptInterface(BRIDGE_OBJECT)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun ensureBridgeInstalled() {
        if (bridgeInstalled) {
            return
        }
        bridgeInstalled = true
        injectScriptOnMain(baseBridgeScript, SCRIPT_INJECTION_TIME_DOCUMENT_START)
    }

    @SuppressLint("RequiresFeature")
    private fun injectScriptOnMain(script: String, time: Int): Long {
        val scriptId = nextScriptId
        nextScriptId += 1
        when (time) {
            SCRIPT_INJECTION_TIME_DOCUMENT_START -> {
                check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    "Android WebView backend requires WebViewFeature.DOCUMENT_START_SCRIPT for document-start injection"
                }
                documentStartScripts[scriptId] = WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    script,
                    mutableSetOf("*")
                )
            }
            SCRIPT_INJECTION_TIME_DOCUMENT_END -> documentEndScripts[scriptId] = script
            else -> error("unsupported script injection time: $time")
        }
        return scriptId
    }

    private fun currentUrl(): String = requireNotNull(webView.url) {
        "WebView URL is unavailable before the first navigation"
    }

    private fun buildHandlerScript(name: String): String = handlerScriptTemplate.replace(
        HANDLER_NAME_TOKEN,
        JSONObject.quote(name)
    )

    private fun emitLoading(progress: Float) {
        emitEvent(
            eventType = EVENT_LOADING,
            progress = progress
        )
    }

    private fun emitStateChanged() {
        emitEvent(
            eventType = EVENT_STATE_CHANGED,
            canGoBack = webView.canGoBack(),
            canGoForward = webView.canGoForward()
        )
    }

    private fun emitWillNavigate(url: String, allowRepeat: Boolean) {
        require(url.isNotEmpty()) { "WebView navigation URL must not be empty" }
        if (!allowRepeat && lastNavigationUrl == url) {
            return
        }
        lastNavigationUrl = url
        emitEvent(
            eventType = EVENT_WILL_NAVIGATE,
            url = url
        )
    }

    private fun emitRedirect(fromUrl: String, toUrl: String) {
        emitEvent(
            eventType = EVENT_REDIRECT,
            url = fromUrl,
            url2 = toUrl
        )
    }

    private fun emitEvent(
        eventType: Int,
        url: String? = null,
        url2: String? = null,
        message: String? = null,
        progress: Float = 0f,
        canGoBack: Boolean = false,
        canGoForward: Boolean = false
    ) {
        eventCallback?.onEvent(
            eventType,
            url,
            url2,
            message,
            progress,
            canGoBack,
            canGoForward
        )
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun postMessage(name: String, requestId: String, payloadBase64: String) {
            bridgeHandler.post {
                // This entry point is reachable from ordinary page script, so an
                // unknown handler name is rejected back to JavaScript rather than
                // crashing the host application.
                val nativePtr = handlerNativePtrs[name]
                if (nativePtr == null) {
                    Log.w(LOG_TAG, "page script called WaterUI handler '$name', which is not registered")
                    resolveMessage(requestId, false, "no WaterUI handler named '$name'")
                    return@post
                }
                nativeOnMessage(nativePtr, name, requestId, payloadBase64)
            }
        }
    }

    private external fun nativeCompleteJsResult(
        callbackData: Long,
        callbackFn: Long,
        success: Boolean,
        result: String
    )

    private external fun nativeCompleteCookies(
        callbackData: Long,
        callbackFn: Long,
        result: String
    )

    private external fun nativeOnMessage(
        nativePtr: Long,
        name: String,
        requestId: String,
        payloadBase64: String
    )

    companion object {
        private const val LOG_TAG = "WaterUIWebView"
        private const val BRIDGE_OBJECT = "__wateruiBridge"
        private const val EVENT_WILL_NAVIGATE = 1
        private const val EVENT_LOADING = 2
        private const val EVENT_LOADED = 3
        private const val EVENT_REDIRECT = 4
        private const val EVENT_SSL_ERROR = 5
        private const val EVENT_ERROR = 6
        private const val EVENT_STATE_CHANGED = 7

        private const val SCRIPT_INJECTION_TIME_DOCUMENT_START = 0
        private const val SCRIPT_INJECTION_TIME_DOCUMENT_END = 1

        private const val HANDLER_NAME_TOKEN = "__WATERUI_HANDLER_NAME_JSON__"
        private const val EVAL_SOURCE_TOKEN = "__WATERUI_EVAL_SOURCE__"
    }
}

private fun Context.readRawText(@RawRes resource: Int): String =
    resources.openRawResource(resource).bufferedReader().use { it.readText() }

internal fun RegistryBuilder.registerWuiWebView() {
    register({ webViewTypeId }, webViewRenderer)
}
