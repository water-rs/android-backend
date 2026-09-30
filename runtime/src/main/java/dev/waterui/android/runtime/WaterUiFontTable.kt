package dev.waterui.android.runtime

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "WaterUI.FontTable"
private const val FONT_ASSETS_DIR = "fonts"
private const val FONT_MANIFEST = "waterui-fonts.json"

/**
 * The declared-family → `Typeface` table the runtime owner builds once at
 * bootstrap and hands to the renderer explicitly.
 *
 * `water build` writes `assets/fonts/waterui-fonts.json` next to the font
 * files it stages; the manifest is what maps a declared family name to its
 * asset path. An absent `fonts/` directory means the app declared no fonts.
 * A populated directory without the manifest is a packaging defect and
 * `assets.open` throwing on it is the fail-fast.
 */
class WaterUiFontTable(context: Context) {
    private val typefaces: Map<String, Typeface>

    init {
        val bundled = context.assets.list(FONT_ASSETS_DIR).orEmpty().filter { it.isNotEmpty() }
        typefaces = if (bundled.isEmpty()) {
            emptyMap()
        } else {
            val manifest = context.assets
                .open("$FONT_ASSETS_DIR/$FONT_MANIFEST")
                .bufferedReader()
                .use { it.readText() }
            parseManifest(context, manifest)
        }
        if (typefaces.isNotEmpty()) {
            Log.d(TAG, "Loaded ${typefaces.size} declared font families")
        }
    }

    /**
     * The bundled `Typeface` for a declared family, or `null` when the family
     * is not declared — callers then fall back to the platform lookup.
     */
    fun typefaceFor(family: String): Typeface? = typefaces[family]

    private fun parseManifest(context: Context, manifest: String): Map<String, Typeface> {
        val fonts = try {
            JSONObject(manifest).getJSONArray("fonts")
        } catch (error: JSONException) {
            throw IllegalArgumentException(
                "invalid $FONT_ASSETS_DIR/$FONT_MANIFEST manifest",
                error
            )
        }
        return buildMap {
            for (i in 0 until fonts.length()) {
                val entry = fonts.getJSONObject(i)
                val family = entry.getString("name")
                val file = entry.getString("file_name")
                put(
                    family,
                    Typeface.createFromAsset(context.assets, "$FONT_ASSETS_DIR/$file")
                )
            }
        }
    }
}
