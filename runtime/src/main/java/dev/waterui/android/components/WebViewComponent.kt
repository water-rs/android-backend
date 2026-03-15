package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

private val webViewTypeId: WuiTypeId by lazy { NativeBindings.waterui_web_view_id().toTypeId() }

private val webViewRenderer = WuiRenderer { context, node, env, registry ->
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

object WebViewManager {
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    @JvmStatic
    fun create(): WebViewWrapper {
        val context = checkNotNull(applicationContext) {
            "WebViewManager.create called before WaterUiRootView initialized the application context"
        }
        return WebViewWrapper(context)
    }
}

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

class NativeWebViewEventCallback(
    private val nativePtr: Long
) : WebViewEventCallback {
    override fun onEvent(
        eventType: Int,
        url: String,
        url2: String,
        message: String,
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
        url: String,
        url2: String,
        message: String,
        progress: Float,
        canGoBack: Boolean,
        canGoForward: Boolean
    )
}

@SuppressLint("SetJavaScriptEnabled")
class WebViewWrapper(
    context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cookieManager = CookieManager.getInstance()
    private val documentStartScripts = mutableMapOf<Long, ScriptHandler>()
    private val documentEndScripts = linkedMapOf<Long, String>()
    private val handlerNativePtrs = linkedMapOf<String, Long>()
    private val webView = WebView(context)
    private var eventCallback: WebViewEventCallback? = null
    private var redirectsEnabled = true
    private var lastNavigationUrl: String? = null
    private var nextScriptId = 1L
    private var bridgeInstalled = false
    private val bridge = JsBridge()

    init {
        runOnMainBlocking {
            cookieManager.setAcceptCookie(true)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.javaScriptCanOpenWindowsAutomatically = true
            webView.addJavascriptInterface(bridge, BRIDGE_OBJECT)
            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    emitLoading(newProgress.coerceIn(0, 100) / 100f)
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
                    emitWillNavigate(url.orEmpty(), allowRepeat = false)
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
                        message = error.description?.toString().orEmpty()
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
                        url = error.url ?: currentUrl(),
                        message = error.toString()
                    )
                    handler.cancel()
                }
            }
        }
    }

    fun goBack() {
        runOnMainAsync {
            webView.goBack()
        }
    }

    fun goForward() {
        runOnMainAsync {
            webView.goForward()
        }
    }

    fun goTo(url: String) {
        runOnMainAsync {
            webView.loadUrl(url)
        }
    }

    fun stop() {
        runOnMainAsync {
            webView.stopLoading()
        }
    }

    fun refresh() {
        runOnMainAsync {
            webView.reload()
        }
    }

    fun canGoBack(): Boolean = runOnMainBlocking { webView.canGoBack() }

    fun canGoForward(): Boolean = runOnMainBlocking { webView.canGoForward() }

    fun setUserAgent(userAgent: String) {
        runOnMainAsync {
            webView.settings.userAgentString = userAgent
        }
    }

    fun setRedirectsEnabled(enabled: Boolean) {
        redirectsEnabled = enabled
    }

    fun setEventCallback(callback: WebViewEventCallback?) {
        eventCallback = callback
    }

    fun injectScript(script: String, time: Int) {
        runOnMainBlocking {
            when (time) {
                SCRIPT_INJECTION_TIME_DOCUMENT_START -> {
                    check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        "Android WebView backend requires WebViewFeature.DOCUMENT_START_SCRIPT for document-start injection"
                    }
                    val scriptId = nextScriptId
                    nextScriptId += 1
                    val handler = WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        script,
                        mutableSetOf("*")
                    )
                    documentStartScripts[scriptId] = handler
                }
                SCRIPT_INJECTION_TIME_DOCUMENT_END -> {
                    val scriptId = nextScriptId
                    nextScriptId += 1
                    documentEndScripts[scriptId] = script
                }
                else -> error("unsupported script injection time: $time")
            }
        }
    }

    fun addHandler(name: String, nativePtr: Long) {
        runOnMainBlocking {
            handlerNativePtrs[name] = nativePtr
            ensureBridgeInstalled()
            injectScript(buildHandlerScript(name), SCRIPT_INJECTION_TIME_DOCUMENT_START)
        }
    }

    fun removeHandler(name: String) {
        runOnMainAsync {
            handlerNativePtrs.remove(name)
        }
    }

    fun setCookie(cookie: String) {
        runOnMainBlocking {
            cookieManager.setCookie(currentUrl(), cookie)
            cookieManager.flush()
        }
    }

    fun getCookies(): String = runOnMainBlocking {
        cookieManager.getCookie(currentUrl())
            ?.split(';')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.joinToString("\n")
            .orEmpty()
    }

    fun runJavaScript(script: String, callbackData: Long, callbackFn: Long) {
        runOnMainAsync {
            webView.evaluateJavascript(script) { result ->
                nativeCompleteJsResult(
                    callbackData,
                    callbackFn,
                    true,
                    result ?: "null"
                )
            }
        }
    }

    fun getWebView(): WebView = runOnMainBlocking { webView }

    fun resolveMessage(requestId: String, success: Boolean, payloadBase64: String) {
        runOnMainAsync {
            val js = "window.__wateruiResolve(${JSONObject.quote(requestId)}, ${if (success) "true" else "false"}, ${JSONObject.quote(payloadBase64)});"
            webView.evaluateJavascript(js, null)
        }
    }

    fun release() {
        runOnMainBlocking {
            eventCallback = null
            handlerNativePtrs.clear()
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
    }

    private fun ensureBridgeInstalled() {
        if (bridgeInstalled) {
            return
        }
        bridgeInstalled = true
        injectScript(BASE_BRIDGE_SCRIPT, SCRIPT_INJECTION_TIME_DOCUMENT_START)
    }

    private fun currentUrl(): String = webView.url ?: DEFAULT_COOKIE_URL

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
        if (url.isEmpty()) {
            return
        }
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
        url: String = "",
        url2: String = "",
        message: String = "",
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

    private fun runOnMainAsync(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        mainHandler.post(action)
    }

    private fun <T> runOnMainBlocking(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action()
        }

        val latch = CountDownLatch(1)
        var value: Result<T>? = null
        mainHandler.post {
            value = runCatching(action)
            latch.countDown()
        }
        latch.await()
        return value!!.getOrThrow()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun postMessage(name: String, requestId: String, payloadBase64: String) {
            val nativePtr = runOnMainBlocking {
                handlerNativePtrs[name]
                    ?: error("WebViewWrapper bridge received message for missing handler '$name'")
            }
            nativeOnMessage(nativePtr, name, requestId, payloadBase64)
        }
    }

    private external fun nativeCompleteJsResult(
        callbackData: Long,
        callbackFn: Long,
        success: Boolean,
        result: String
    )

    private external fun nativeOnMessage(
        nativePtr: Long,
        name: String,
        requestId: String,
        payloadBase64: String
    )

    companion object {
        private const val BRIDGE_OBJECT = "__wateruiBridge"
        private const val DEFAULT_COOKIE_URL = "https://localhost/"

        private const val EVENT_WILL_NAVIGATE = 1
        private const val EVENT_LOADING = 2
        private const val EVENT_LOADED = 3
        private const val EVENT_REDIRECT = 4
        private const val EVENT_SSL_ERROR = 5
        private const val EVENT_ERROR = 6
        private const val EVENT_STATE_CHANGED = 7

        private const val SCRIPT_INJECTION_TIME_DOCUMENT_START = 0
        private const val SCRIPT_INJECTION_TIME_DOCUMENT_END = 1

        private val BASE_BRIDGE_SCRIPT = listOf(
            "(function(){",
            "  if (window.__waterui) { return; }",
            "  function toBase64Utf8(s){ return btoa(unescape(encodeURIComponent(s))); }",
            "  function fromBase64Utf8(b64){ return decodeURIComponent(escape(atob(b64))); }",
            "  window.__waterui = { pending: Object.create(null), toBase64Utf8: toBase64Utf8, fromBase64Utf8: fromBase64Utf8 };",
            "  window.__wateruiResolve = function(id, ok, payload){",
            "    var p = window.__waterui.pending[id];",
            "    if (!p) { return; }",
            "    delete window.__waterui.pending[id];",
            "    if (ok) { p.resolve(payload); } else { p.reject(payload); }",
            "  };",
            "})();"
        ).joinToString("\n")

        private fun buildHandlerScript(name: String): String {
            val escaped = name
                .replace("\\", "\\\\")
                .replace("'", "\\'")
            return listOf(
                "(function(){",
                "  var name = '$escaped';",
                "  if (!window.__waterui || !window.__wateruiResolve) { return; }",
                "  if (window[name] && window[name].__wateruiWrapped) { return; }",
                "  function send(data){",
                "    var id = String(Date.now()) + '_' + String(Math.random()).slice(2);",
                "    var text = (typeof data === 'string') ? data : JSON.stringify(data);",
                "    var b64 = window.__waterui.toBase64Utf8(text);",
                "    return new Promise(function(resolve, reject){",
                "      window.__waterui.pending[id] = { resolve: resolve, reject: reject };",
                "      window.__wateruiBridge.postMessage(name, id, b64);",
                "    });",
                "  }",
                "  window[name] = {",
                "    __wateruiWrapped: true,",
                "    postMessageRaw: function(data){ return send(data); },",
                "    postMessage: function(data){",
                "      return send(data).then(function(replyB64){",
                "        return window.__waterui.fromBase64Utf8(replyB64);",
                "      });",
                "    }",
                "  };",
                "})();"
            ).joinToString("\n")
        }
    }
}

internal fun RegistryBuilder.registerWuiWebView() {
    register({ webViewTypeId }, webViewRenderer)
}
