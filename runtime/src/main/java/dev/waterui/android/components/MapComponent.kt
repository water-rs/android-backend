package dev.waterui.android.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.AnnotationStruct
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RegionStruct
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private val mapTypeId: WuiTypeId by lazy { WatcherJni.mapId().toTypeId() }

private val mapRenderer = WuiRenderer { context, node, env, _ ->
    val struct = WatcherJni.forceAsMap(node.rawPtr)

    val mapView = WuiMapWebView(context, defaultWidthDp = 320f, defaultHeightDp = 480f).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    @SuppressLint("SetJavaScriptEnabled")
    mapView.settings.javaScriptEnabled = true
    mapView.settings.domStorageEnabled = true

    mapView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            mapView.markReady()
        }
    }

    mapView.loadDataWithBaseURL(
        "https://waterui.invalid/",
        mapHtml(struct.isInteractive, struct.showsScale, struct.showsUserLocation, struct.style),
        "text/html",
        "utf-8",
        null
    )

    if (!struct.isInteractive) {
        mapView.setOnTouchListener { _, event ->
            // Eat all gestures when non-interactive.
            true
        }
    }

    val region = WuiComputed.region(struct.regionPtr, env)
    region.observeWithAnimation { r, animation -> mapView.setRegion(r, animation) }
    mapView.disposeWith(region)

    val annotations = WuiComputed.annotations(struct.annotationsPtr, env)
    annotations.observe { a -> mapView.setAnnotations(a) }
    mapView.disposeWith(annotations)

    mapView
}

internal fun RegistryBuilder.registerWuiMap() {
    register({ mapTypeId }, mapRenderer)
}

private class WuiMapWebView(
    context: android.content.Context,
    private val defaultWidthDp: Float,
    private val defaultHeightDp: Float
) : WebView(context) {
    private data class RegionUpdate(
        val region: RegionStruct,
        val animation: WuiAnimation
    )

    private var ready = false
    private var pendingRegion: RegionUpdate? = null
    private var pendingAnnotations: List<AnnotationStruct>? = null

    fun markReady() {
        ready = true
        pendingRegion?.let { setRegion(it.region, it.animation) }
        pendingAnnotations?.let { setAnnotations(it) }
    }

    fun setRegion(region: RegionStruct, animation: WuiAnimation = WuiAnimation.None) {
        if (!ready) {
            pendingRegion = RegionUpdate(region, animation)
            return
        }
        val zoom = estimateZoom(region.latitudeDelta, region.longitudeDelta)
        val js = if (animation.shouldAnimate) {
            val durationSeconds = when (animation) {
                is WuiAnimation.Bezier -> animation.durationMs.coerceAtLeast(16L).toDouble() / 1000.0
                is WuiAnimation.Spring -> 0.35
                else -> 0.0
            }
            "window.wuiAnimateRegion(${region.centerLatitude},${region.centerLongitude},$zoom,$durationSeconds);"
        } else {
            "window.wuiSetRegion(${region.centerLatitude},${region.centerLongitude},$zoom);"
        }
        evaluateJavascript(js, null)
    }

    fun setAnnotations(annotations: List<AnnotationStruct>) {
        if (!ready) {
            pendingAnnotations = annotations
            return
        }
        val json = annotationsToJson(annotations)
        val js = "window.wuiSetAnnotations($json);"
        evaluateJavascript(js, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val defaultWidthPx = defaultWidthDp.dp(context).toInt()
        val defaultHeightPx = defaultHeightDp.dp(context).toInt()

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
            else -> defaultWidthPx
        }

        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> heightSize
            else -> defaultHeightPx
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }
}

private fun estimateZoom(latitudeDelta: Double, longitudeDelta: Double): Int {
    val delta = max(0.0001, min(latitudeDelta, longitudeDelta))
    // Very rough: zoom ≈ log2(360 / delta). Clamp to typical tile zoom levels.
    val zoom = (ln(360.0 / delta) / ln(2.0)).toInt()
    return zoom.coerceIn(1, 18)
}

private fun annotationsToJson(items: List<AnnotationStruct>): String {
    val sb = StringBuilder()
    sb.append('[')
    items.forEachIndexed { idx, a ->
        if (idx > 0) sb.append(',')
        sb.append('{')
        sb.append("\"lat\":").append(a.latitude).append(',')
        sb.append("\"lon\":").append(a.longitude).append(',')
        sb.append("\"title\":\"").append(jsonEscape(a.title)).append("\",")
        sb.append("\"subtitle\":\"").append(jsonEscape(a.subtitle)).append("\"")
        sb.append('}')
    }
    sb.append(']')
    return sb.toString()
}

private fun jsonEscape(s: String): String =
    s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

private fun mapHtml(
    interactive: Boolean,
    showsScale: Boolean,
    showsUserLocation: Boolean,
    style: Int
): String {
    // style: 0=Standard, 1=Satellite, 2=Hybrid
    val tileJs = when (style) {
        1 -> """
            L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
              maxZoom: 18
            }).addTo(map);
        """.trimIndent()
        2 -> """
            const imagery = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', { maxZoom: 18 });
            imagery.addTo(map);
            const roads = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, opacity: 0.55 });
            roads.addTo(map);
        """.trimIndent()
        else -> """
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
              maxZoom: 19
            }).addTo(map);
        """.trimIndent()
    }

    val interactionJs = if (interactive) {
        ""
    } else {
        """
          map.dragging.disable();
          map.touchZoom.disable();
          map.doubleClickZoom.disable();
          map.scrollWheelZoom.disable();
          map.boxZoom.disable();
          map.keyboard.disable();
          if (map.tap) map.tap.disable();
        """.trimIndent()
    }

    val scaleJs = if (showsScale) "L.control.scale().addTo(map);" else ""
    val locateJs = if (showsUserLocation) "map.locate({setView:false, watch:false});" else ""

    // Leaflet from CDN (keeps Android module dependency-free).
    return """
<!doctype html>
<html>
  <head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
    <style>
      html, body, #map { height: 100%; width: 100%; margin: 0; padding: 0; background: transparent; }
    </style>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  </head>
  <body>
    <div id="map"></div>
    <script>
      const map = L.map('map', { zoomControl: ${interactive} });
      $tileJs
      $interactionJs
      $scaleJs
      $locateJs
      let markers = [];
      window.wuiSetRegion = function(lat, lon, zoom) {
        map.setView([lat, lon], zoom, { animate: false });
      }
      window.wuiAnimateRegion = function(lat, lon, zoom, duration) {
        map.flyTo([lat, lon], zoom, { animate: true, duration: duration });
      }
      window.wuiSetAnnotations = function(items) {
        for (const m of markers) { map.removeLayer(m); }
        markers = [];
        for (const it of items) {
          const marker = L.marker([it.lat, it.lon]);
          const title = it.title || '';
          const subtitle = it.subtitle || '';
          const popup = subtitle ? (title + '<br/>' + subtitle) : title;
          if (popup) marker.bindPopup(popup);
          marker.addTo(map);
          markers.push(marker);
        }
      }
    </script>
  </body>
</html>
""".trimIndent()
}
