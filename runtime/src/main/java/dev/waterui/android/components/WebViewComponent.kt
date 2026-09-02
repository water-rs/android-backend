package dev.waterui.android.components

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.annotation.RawRes
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
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
import org.json.JSONException
import org.json.JSONObject

/** Shared with [WaterUiWebViewClient], which owns the definition. */
private val LOG_TAG = WaterUiWebViewClient.LOG_TAG

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

/**
 * Creates the web views a WaterUI app opens.
 *
 * The context is kept as it was handed over rather than reduced to the
 * application context: JavaScript dialogs, `<select>` popups, the file chooser
 * and night-mode theming all need a context with a window behind it, and the
 * application context silently disables all four.
 */
@Keep
class WebViewFactory(private val context: Context) {
    init {
        if (context.findActivity() == null) {
            Log.w(
                LOG_TAG,
                "WaterUI web views were created without an Activity context: JavaScript dialogs, " +
                    "<select> popups and the file chooser cannot open"
            )
        }
    }

    fun create(): WebViewWrapper = WebViewWrapper(context)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
    internal val nativePtr: Long
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
    private val cookieManager = CookieManager.getInstance()
    private val webView = WebView(context)
    private val transportScript = context.readRawText(R.raw.waterui_webview_transport)
        .replace(BRIDGE_OBJECT_TOKEN, BRIDGE_OBJECT)
    private val asyncCallTemplate = context.readRawText(R.raw.waterui_webview_async_call)
        .replace(ASYNC_RESULT_OBJECT_TOKEN, ASYNC_RESULT_OBJECT)
        .replace(ASYNC_CALL_SENTINEL_TOKEN, ASYNC_CALL_SENTINEL)
    private val documentEndTemplate = context.readRawText(R.raw.waterui_webview_document_end)

    /** Document-start sources by key, in the order they were first injected. */
    private val documentStartSources = linkedMapOf<String, String>()

    /** Handles for the sources currently installed, dropped on every rebuild. */
    private val installedScripts = mutableListOf<ScriptHandler>()

    /** Native completions still owed a result, keyed by call id. */
    private val pendingCalls = linkedMapOf<Long, JsCompletion>()
    private var nextCallId = 1L

    private var eventCallback: WebViewEventCallback? = null

    /** The `AndroidWebViewHandle` every bridge message is routed to. */
    private var nativeHandlePtr = 0L
    private var redirectPolicy: WuiComputed<Boolean>? = null
    private var redirectsEnabled = true

    /**
     * The navigation already reported to Rust, cleared once it is over.
     *
     * Several client callbacks see one navigation, so this is what keeps them
     * from reporting it several times over.
     */
    private var announcedNavigationUrl: String? = null

    /**
     * The bridge origin policy, as the rule tokens `OriginPolicy::wire` renders.
     *
     * Empty denies every document, which is a policy and not the absence of one.
     */
    private var originRules: List<String> = emptyList()
    private var bridgeListenerInstalled = false
    private var released = false

    init {
        cookieManager.setAcceptCookie(true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        installAsyncResultListener()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                emitLoading(newProgress / 100f)
            }
        }
        webView.webViewClient = object : WaterUiWebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) {
                    return false
                }

                val targetUrl = request.url.toString()
                if (!redirectsEnabled && request.isRedirect) {
                    // The URL this navigation started from, not `webView.url`:
                    // that is null until a document commits, so a server
                    // redirect on the very first load crashed the application.
                    // With nothing announced yet there is no earlier URL to
                    // name, and the target stands in for it — the same answer
                    // the macOS bridge gives (`macos.rs`,
                    // didReceiveServerRedirectForProvisionalNavigation).
                    emitRedirect(announcedNavigationUrl ?: targetUrl, targetUrl)
                    return true
                }

                announceNavigation(targetUrl)
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // The previous document is gone, and with it every promise a
                // script evaluated in it was still waiting on.
                abandonPendingCalls("the document was replaced before the script ran")
                announceNavigation(
                    requireNotNull(url) { "WebView started a navigation without a URL" }
                )
                emitLoading(0f)
                emitStateChanged()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                // Android's only signal for a same-document navigation:
                // `history.pushState`, `replaceState` and a fragment change reach
                // no other client callback. Rust re-seeds mirrored state on every
                // navigation it is told about, so one it is not told about is a
                // document left reading the values of the previous one.
                announceNavigation(
                    requireNotNull(url) { "WebView updated its history without a URL" }
                )
                finishNavigationAnnouncement()
                emitStateChanged()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                finishNavigationAnnouncement()
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
                finishNavigationAnnouncement()
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

            /**
             * Reports the renderer's death as the page failure it is, then
             * tears the wrapper down for it.
             *
             * [release] does everything the base client does and settles what
             * the bridge still owes as well, so the pending native calls this
             * renderer will never answer are failed instead of left suspended.
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.w(LOG_TAG, "web content process gone (crashed=${detail.didCrash()})")
                finishNavigationAnnouncement()
                emitEvent(
                    eventType = EVENT_ERROR,
                    message = if (detail.didCrash()) {
                        "the web content process crashed"
                    } else {
                        "the system reclaimed the web content process"
                    }
                )
                release()
                return true
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
        // The same `AndroidWebViewHandle` a handler registration carries. Rust
        // installs its watcher when the web view is created, before any document
        // can load, which is what lets a bridge call reach Rust even when no
        // handler has been registered — the reply for an unknown name is Rust's
        // to render, and a call that is simply dropped here never settles.
        nativeHandlePtr = when (callback) {
            null -> 0L
            is NativeWebViewEventCallback -> callback.nativePtr
            else -> error(
                "WaterUI web views require a NativeWebViewEventCallback: the bridge routes page " +
                    "messages through the same native handle"
            )
        }
    }

    /**
     * Injects a script that runs on every page load, replacing whatever was
     * injected under `key`.
     *
     * Replacement is what the mirrored-state seed depends on: its values are only
     * correct for the document it was rendered for, so Rust re-renders and
     * re-injects it under the same key before every navigation. Adding a second
     * copy instead would leave a view that navigated ten times running ten seed
     * scripts, each staler than the last.
     */
    fun injectScript(key: String, script: String, time: Int) {
        documentStartSources[key] = when (time) {
            SCRIPT_INJECTION_TIME_DOCUMENT_START -> script
            SCRIPT_INJECTION_TIME_DOCUMENT_END ->
                documentEndTemplate.replace(DOCUMENT_END_SCRIPT_TOKEN, script)

            else -> error("unsupported script injection time: $time")
        }
        rebuildDocumentStartScripts()
    }

    /**
     * Records the native handle a bridge call is routed to.
     *
     * The handler table itself lives in Rust: it owns the names and it is what
     * answers a call for a name nobody registered. A second copy here only made
     * an empty table look like a reason to drop the message, which left the
     * page's promise pending forever.
     */
    fun addHandler(name: String, nativePtr: Long) {
        require(nativePtr != 0L) {
            "WaterUI handler `$name` was registered without a native handle"
        }
        nativeHandlePtr = nativePtr
    }

    /**
     * Reports that a handler is gone.
     *
     * There is nothing to forget on this side: Rust owns the handler names, and
     * the native handle stays valid until the web view is released.
     */
    fun removeHandler(name: String) {
        Log.d(LOG_TAG, "handler `$name` was removed; its name lives in Rust")
    }

    /**
     * Restricts which documents may reach the bridge.
     *
     * `rules` is newline-separated rule tokens as `OriginPolicy::wire` renders
     * them: `*` for every origin, `file:` for any local document, and otherwise
     * an exact `scheme://host[:port]` to compare a calling frame's origin
     * against. **The empty string denies every document** — it is what the
     * default policy resolves to for a view opened at a `file:` or `data:` URL —
     * and reading it as `*` hands every registered handler to whatever the view
     * navigates to next.
     */
    fun setBridgeOrigins(rules: String) {
        originRules = rules.split('\n').filter(String::isNotEmpty)
        installBridge()
    }

    /**
     * Stores one `Set-Cookie` header value.
     *
     * The cookie is scoped to the domain it names rather than to whatever page
     * happens to be loaded. `CookieManager.setCookie` derives the domain from the
     * URL it is given, and Chromium rejects a cookie whose `Domain` does not
     * match that URL, so passing the current page silently dropped every cookie
     * set for a domain the view had not visited.
     */
    fun setCookie(cookie: String) {
        val url = cookieUrl(cookie)
        if (url == null) {
            Log.w(
                LOG_TAG,
                "a cookie naming no Domain was set before any page was loaded; there is no " +
                    "origin to scope it to"
            )
            return
        }
        cookieManager.setCookie(url, cookie)
        cookieManager.flush()
    }

    /**
     * Returns the current cookies as newline-separated Set-Cookie strings.
     *
     * Each line is only `name=value`. `CookieManager.getCookie` returns the
     * request-header form, and Android exposes no other way to enumerate the
     * store, so domain, path, expiry, Secure, HttpOnly and SameSite are simply
     * not available to us here. `WebViewHandle::get_cookies` documents this for
     * the Rust side; do not synthesise attributes to close the gap, because a
     * guess is indistinguishable from a fact once it crosses the bridge.
     *
     * Before the first navigation there is no document to read cookies for and
     * no URL to ask the store about, and the honest answer is that this web view
     * has none — asking anyway is what made `get_cookies` throw inside the JNI
     * call on a view that had not navigated yet.
     */
    fun getCookies(callbackData: Long, callbackFn: Long) {
        val cookies = webView.url
            ?.let(cookieManager::getCookie)
            ?.split(';')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.joinToString("\n")
            .orEmpty()
        nativeCompleteCookies(callbackData, callbackFn, cookies)
    }

    /**
     * Runs a script and hands back whatever the engine marshals for it.
     *
     * This is the raw path, and it stays raw: everything typed — `WebView::eval`,
     * `WebView::exec` and every mirrored-state push — goes through
     * [callAsyncJavaScript], which awaits the promise the shared wrapper returns.
     * Running the caller's script inside a second, private wrapper here is what
     * made all three fail: the shared wrapper is `async`, so the private one
     * stringified a `Promise` and Rust received `{}`.
     */
    fun runJavaScript(script: String, callbackData: Long, callbackFn: Long) {
        val id = registerCall(callbackData, callbackFn)
        webView.evaluateJavascript(script) { result ->
            if (result == null) {
                completeCall(id, success = false, payload = "WebView returned no result")
                return@evaluateJavascript
            }
            completeCall(id, success = true, payload = result)
        }
    }

    /**
     * Runs `body` as the body of an `async` function and awaits its promise.
     *
     * Android has no awaiting evaluation API, so the promise is resolved in
     * JavaScript and the settled value is posted back through the
     * [ASYNC_RESULT_OBJECT] web message listener, which carries the envelope the
     * shared wrapper produced across unmodified.
     */
    fun callAsyncJavaScript(body: String, callbackData: Long, callbackFn: Long) {
        val id = registerCall(callbackData, callbackFn)
        val script = asyncCallTemplate
            .replace(ASYNC_CALL_ID_TOKEN, id.toString())
            .replace(ASYNC_CALL_BODY_TOKEN, body)
        webView.evaluateJavascript(script) { result ->
            // The launcher's own value. A script that fails to parse never runs a
            // line of itself, so nothing else would ever settle this call.
            if (result != ASYNC_CALL_STARTED) {
                completeCall(
                    id,
                    success = false,
                    payload = "WaterUI could not start an async evaluation: $result"
                )
            }
        }
    }

    fun getWebView(): WebView = webView

    /** Evaluates a reply script rendered by the shared bridge on the Rust side. */
    fun evaluateBridgeScript(script: String) {
        webView.evaluateJavascript(script, null)
    }

    @SuppressLint("RequiresFeature")
    fun release() {
        // A dead render process tears this view down where it is reported, and
        // the Rust handle that owns the view is dropped afterwards all the same.
        if (released) {
            return
        }
        released = true
        redirectPolicy?.close()
        redirectPolicy = null
        eventCallback = null
        nativeHandlePtr = 0L
        // Every native callback still owed a result has to be settled before the
        // web view goes away: the Rust side is waiting on a channel whose sender
        // it handed us, and a callback that is dropped rather than called leaves
        // that sender leaked and its task suspended forever.
        abandonPendingCalls("the web view was closed")
        originRules = emptyList()
        documentStartSources.clear()
        // Nothing is admitted and nothing is left to inject, so this removes the
        // bridge listener and every script currently installed.
        installBridge()
        WebViewCompat.removeWebMessageListener(webView, ASYNC_RESULT_OBJECT)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WaterUiWebViewClient()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    // =========================================================================
    // Bridge installation
    // =========================================================================

    /**
     * Installs, or reinstalls, everything the origin policy admits.
     *
     * The bridge, the evaluation wrapper and the state mirror are one script, and
     * the user scripts and the mirrored-state seed carry the values the app
     * exposed, so all of them are restricted to the admitted origins and none of
     * them is injected while the policy admits nothing.
     */
    private fun installBridge() {
        installBridgeListener()
        rebuildDocumentStartScripts()
    }

    @SuppressLint("RequiresFeature")
    private fun installBridgeListener() {
        requireWebMessageListener()
        if (bridgeListenerInstalled) {
            WebViewCompat.removeWebMessageListener(webView, BRIDGE_OBJECT)
            bridgeListenerInstalled = false
        }
        val rules = injectionRules()
        if (rules.isEmpty()) {
            return
        }
        // Android injects the object only into frames whose origin matches, which
        // is the enforcement `addJavascriptInterface` could not do: it hands the
        // object to *every* frame, so any document — a cross-origin iframe
        // included — could call a WaterUI handler directly.
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_OBJECT,
            rules
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            onBridgeMessage(message, sourceOrigin, isMainFrame)
        }
        bridgeListenerInstalled = true
    }

    /**
     * Receives one settled [callAsyncJavaScript] promise.
     *
     * Unlike the bridge this is installed for every origin. It grants a document
     * nothing — the value it reports is the value of a script the application
     * chose to run in that document — and a document the bridge policy excludes
     * still has to be able to report that the evaluation wrapper is not there,
     * rather than leaving the caller waiting forever.
     */
    @SuppressLint("RequiresFeature")
    private fun installAsyncResultListener() {
        requireWebMessageListener()
        WebViewCompat.addWebMessageListener(
            webView,
            ASYNC_RESULT_OBJECT,
            setOf(ORIGIN_RULE_ANY)
        ) { _, message, _, isMainFrame, _ ->
            onAsyncResult(message, isMainFrame)
        }
    }

    private fun requireWebMessageListener() {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "Android WebView backend requires WebViewFeature.WEB_MESSAGE_LISTENER to " +
                "authenticate the frame a bridge call arrives from"
        }
    }

    /**
     * Reinstalls every document-start script under the current policy.
     *
     * Android has no "replace this script" call, so the whole set is rebuilt.
     * That is what makes injecting under a key already in use replace that script
     * rather than stack a second, staler copy in front of it, and what keeps the
     * bridge in front of everything else — the mirrored-state seed calls
     * `__wateruiState.define`, so a seed that runs before `state.js` throws a
     * `TypeError` and leaves the mirror dead for the life of the document.
     */
    @SuppressLint("RequiresFeature")
    private fun rebuildDocumentStartScripts() {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "Android WebView backend requires WebViewFeature.DOCUMENT_START_SCRIPT for " +
                "document-start injection"
        }
        installedScripts.forEach(ScriptHandler::remove)
        installedScripts.clear()
        val rules = injectionRules()
        if (rules.isEmpty()) {
            return
        }
        // Transport first: the shared script calls `__wateruiSend`, so the adapter
        // onto Android's injected object has to exist before it runs.
        val sources = sequenceOf(transportScript, nativeBridgeScript()) +
            documentStartSources.values
        sources.mapTo(installedScripts) { source ->
            WebViewCompat.addDocumentStartJavaScript(webView, source, rules)
        }
    }

    /**
     * The policy in Android's own origin-rule form.
     *
     * Android matches an *origin* — `scheme://host[:port]` — which is the shape
     * the tokens already carry; only the local-file token differs, because
     * Android spells a rule with a scheme and no host `file://`. The URI globs
     * `OriginRule::injection_pattern` renders are for engines that filter on a URL
     * pattern: a path is not part of an origin and Android rejects a rule that
     * carries one.
     */
    private fun injectionRules(): Set<String> = originRules
        .map { rule -> if (rule == ORIGIN_RULE_LOCAL_FILES) ANDROID_LOCAL_FILE_RULE else rule }
        .toSet()

    /**
     * Whether a frame reporting `origin` may use the bridge.
     *
     * The comparison `OriginPolicy::allows_origin` defines: an exact
     * `scheme://host[:port]` match, `file:` for any local document, `*` for every
     * origin. An opaque origin — a `data:` document, a sandboxed frame — reports
     * an empty origin and matches nothing but `*`, which is the point: it cannot
     * be authenticated.
     */
    private fun originMayUseBridge(origin: String): Boolean = originRules.any { rule ->
        when (rule) {
            ORIGIN_RULE_ANY -> true
            ORIGIN_RULE_LOCAL_FILES -> origin.startsWith(ANDROID_LOCAL_FILE_RULE)
            else -> rule == origin
        }
    }

    /**
     * Receives one `waterui.invoke(...)` envelope from page script.
     *
     * The envelope is handed to Rust unparsed: its format belongs to
     * `waterui_webview::bridge` and is not duplicated here. Rust also decides how
     * to reject an unknown handler, so page script cannot crash the app through
     * this entry point.
     *
     * The origin authenticated here is the *calling frame's own*, which is what
     * `webView.url` — the top-level document's URL — could never be: a
     * third-party iframe inside an admitted page passed that check. Only the main
     * frame may call, because a reply is evaluated in the main frame and could
     * not reach a subframe anyway; the transport script refuses in a subframe
     * before a promise is even created, so a page that plays by the rules sees a
     * rejection rather than a call that never settles.
     */
    private fun onBridgeMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean
    ) {
        if (!isMainFrame) {
            Log.w(LOG_TAG, "a subframe tried to call a WaterUI handler; the bridge is main frame only")
            return
        }
        val origin = sourceOrigin.toString()
        if (!originMayUseBridge(origin)) {
            Log.w(LOG_TAG, "a document at $origin is outside the WaterUI bridge origin policy")
            return
        }
        // The type is read before the payload: `getData` is only defined for a
        // string message and throws for an array buffer, and what a page posts is
        // page input.
        val envelope = message.takeIf { it.type == WebMessageCompat.TYPE_STRING }?.data
        if (envelope == null) {
            Log.w(LOG_TAG, "a WaterUI bridge envelope arrived as something other than a string")
            return
        }
        check(nativeHandlePtr != 0L) {
            "page script reached the WaterUI bridge before the native handle was registered"
        }
        nativeOnBridgeMessage(nativeHandlePtr, envelope)
    }

    private fun onAsyncResult(message: WebMessageCompat, isMainFrame: Boolean) {
        if (!isMainFrame) {
            Log.w(LOG_TAG, "a subframe reported a WaterUI evaluation result; only the main frame is evaluated")
            return
        }
        val payload = message.takeIf { it.type == WebMessageCompat.TYPE_STRING }?.data
        if (payload == null) {
            Log.w(LOG_TAG, "a WaterUI evaluation result arrived as something other than a string")
            return
        }
        val result = try {
            val envelope = JSONObject(payload)
            AsyncResult(
                id = envelope.getLong(ASYNC_RESULT_ID),
                success = envelope.getBoolean(ASYNC_RESULT_OK),
                payload = envelope.getString(ASYNC_RESULT_VALUE)
            )
        } catch (error: JSONException) {
            Log.w(LOG_TAG, "a WaterUI evaluation reported a malformed result", error)
            return
        }
        completeCall(result.id, result.success, result.payload)
    }

    // =========================================================================
    // Native completions
    // =========================================================================

    private class AsyncResult(val id: Long, val success: Boolean, val payload: String)

    /** One native callback, invoked exactly once. */
    private inner class JsCompletion(
        private val callbackData: Long,
        private val callbackFn: Long
    ) {
        fun settle(success: Boolean, payload: String) {
            nativeCompleteJsResult(callbackData, callbackFn, success, payload)
        }
    }

    private fun registerCall(callbackData: Long, callbackFn: Long): Long {
        val id = nextCallId
        nextCallId += 1
        pendingCalls[id] = JsCompletion(callbackData, callbackFn)
        return id
    }

    private fun completeCall(id: Long, success: Boolean, payload: String) {
        // A missing entry is a call that has already been settled — its promise
        // beat the launcher's own value back, or the document it was running in
        // was replaced — and a native callback may only be invoked once.
        val completion = pendingCalls.remove(id) ?: return
        completion.settle(success, payload)
    }

    private fun abandonPendingCalls(reason: String) {
        if (pendingCalls.isEmpty()) {
            return
        }
        val abandoned = pendingCalls.values.toList()
        pendingCalls.clear()
        abandoned.forEach { completion -> completion.settle(success = false, payload = reason) }
    }

    // =========================================================================
    // Events
    // =========================================================================

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

    /**
     * Reports one navigation, once.
     *
     * Three client callbacks see a single cross-document navigation, so a URL
     * already announced for the navigation in flight is not announced again.
     * Deduplicating against the *last* URL instead, as this used to, silently
     * dropped a reload and every renavigation to the URL already shown — and
     * Rust re-seeds mirrored state on the navigations it is told about, so those
     * documents loaded with the values of the one before them.
     */
    private fun announceNavigation(url: String) {
        require(url.isNotEmpty()) { "WebView navigation URL must not be empty" }
        if (announcedNavigationUrl == url) {
            return
        }
        announcedNavigationUrl = url
        emitEvent(
            eventType = EVENT_WILL_NAVIGATE,
            url = url
        )
    }

    /** Ends the announcement, so the same URL can be navigated to again. */
    private fun finishNavigationAnnouncement() {
        announcedNavigationUrl = null
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

    // =========================================================================
    // Cookies
    // =========================================================================

    /**
     * The URL a `Set-Cookie` value is stored against.
     *
     * A cookie that names its own `Domain` is stored against that domain, over
     * `https` so that a `Secure` cookie is accepted; only a cookie without one
     * falls back to the document's URL, which is `null` before the first
     * navigation.
     */
    private fun cookieUrl(cookie: String): String? {
        val domain = cookieDomain(cookie) ?: return webView.url
        return "https://" + domain.removePrefix(".")
    }

    /** The `Domain` attribute of a `Set-Cookie` value, when it has one. */
    private fun cookieDomain(cookie: String): String? = cookie.split(';')
        .asSequence()
        .map(String::trim)
        .firstOrNull { attribute -> attribute.startsWith(COOKIE_DOMAIN_ATTRIBUTE, ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf(String::isNotEmpty)

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

    private external fun nativeOnBridgeMessage(nativePtr: Long, envelope: String)

    private external fun nativeBridgeScript(): String

    companion object {
        private const val BRIDGE_OBJECT = "__wateruiBridge"
        private const val ASYNC_RESULT_OBJECT = "__wateruiAsyncResult"

        private const val BRIDGE_OBJECT_TOKEN = "__WATERUI_BRIDGE_OBJECT__"
        private const val ASYNC_RESULT_OBJECT_TOKEN = "__WATERUI_ASYNC_RESULT_OBJECT__"
        private const val ASYNC_CALL_ID_TOKEN = "__WATERUI_ASYNC_CALL_ID__"
        private const val ASYNC_CALL_BODY_TOKEN = "__WATERUI_ASYNC_CALL_BODY__"
        private const val ASYNC_CALL_SENTINEL_TOKEN = "__WATERUI_ASYNC_CALL_SENTINEL__"
        private const val DOCUMENT_END_SCRIPT_TOKEN = "__WATERUI_DOCUMENT_END_SCRIPT__"

        private const val ASYNC_CALL_SENTINEL = "__wateruiAsyncCallStarted"

        /** The sentinel as `evaluateJavascript` reports it: JSON, so quoted. */
        private const val ASYNC_CALL_STARTED = "\"$ASYNC_CALL_SENTINEL\""

        private const val ASYNC_RESULT_ID = "id"
        private const val ASYNC_RESULT_OK = "ok"
        private const val ASYNC_RESULT_VALUE = "value"

        /** Rule tokens as `OriginPolicy::wire` renders them. */
        private const val ORIGIN_RULE_ANY = "*"
        private const val ORIGIN_RULE_LOCAL_FILES = "file:"

        /** Android's spelling of a rule with a scheme and no host. */
        private const val ANDROID_LOCAL_FILE_RULE = "file://"

        private const val COOKIE_DOMAIN_ATTRIBUTE = "domain="

        private const val EVENT_WILL_NAVIGATE = 1
        private const val EVENT_LOADING = 2
        private const val EVENT_LOADED = 3
        private const val EVENT_REDIRECT = 4
        private const val EVENT_SSL_ERROR = 5
        private const val EVENT_ERROR = 6
        private const val EVENT_STATE_CHANGED = 7

        private const val SCRIPT_INJECTION_TIME_DOCUMENT_START = 0
        private const val SCRIPT_INJECTION_TIME_DOCUMENT_END = 1
    }
}

private fun Context.readRawText(@RawRes resource: Int): String =
    resources.openRawResource(resource).bufferedReader().use { it.readText() }

internal fun RegistryBuilder.registerWuiWebView() {
    register({ webViewTypeId }, webViewRenderer)
}
