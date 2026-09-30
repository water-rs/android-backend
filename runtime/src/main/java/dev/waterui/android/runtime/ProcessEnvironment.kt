package dev.waterui.android.runtime

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

private const val TAG = "WaterUI.ProcessEnvironment"
private const val BUNDLED_ASSETS_DIR = "waterui_assets"
private const val SYNC_STAMP_FILE = "waterui-sync-stamp"

/**
 * Syncs the bundled `waterui_assets/` tree into app-private storage and
 * exports the paths the native runtime reads: `WATERUI_ASSETS_ROOT` for
 * declared assets and `WATER_CACHE_DIR` for Rust code wanting a cache
 * directory (the platform `dirs` crate has no usable location inside an app
 * sandbox). A failed sync or setenv throws — the runtime never starts on a
 * half-configured environment.
 *
 * Environment overrides the caller layers on later (CLI intent extras,
 * system properties) win by overwriting these defaults, so callers run this
 * before their own overrides.
 */
fun installWaterUiProcessEnvironment(context: Context) {
    val assetsRoot = syncBundledAssets(context)
    Os.setenv("WATERUI_ASSETS_ROOT", assetsRoot.absolutePath, true)
    Os.setenv("WATER_CACHE_DIR", context.cacheDir.absolutePath, true)
}

/**
 * Copies `assets/waterui_assets` — the tree `water build` ships inside the
 * APK or library AAR — into `filesDir/waterui_assets`, skipping the copy when
 * the bundled `waterui-sync-stamp` matches the stamp last extracted.
 *
 * The shipped contract: an absent `waterui_assets` directory means no assets
 * were declared; a present directory always carries the stamp — an asset
 * tree without one is a packaging defect and `assets.open` throwing on it is
 * the fail-fast.
 */
private fun syncBundledAssets(context: Context): File {
    val assetRoot = File(context.filesDir, BUNDLED_ASSETS_DIR)
    if (context.assets.list(BUNDLED_ASSETS_DIR).orEmpty().isEmpty()) {
        assetRoot.mkdirs()
        return assetRoot
    }

    val bundledStamp = context.assets
        .open("$BUNDLED_ASSETS_DIR/$SYNC_STAMP_FILE")
        .bufferedReader()
        .use { it.readText() }
    val localStamp = File(assetRoot, SYNC_STAMP_FILE)
        .takeIf { it.exists() }
        ?.readText()
    if (localStamp == bundledStamp) {
        Log.d(TAG, "waterui_assets up to date, skipping extraction")
        return assetRoot
    }

    assetRoot.deleteRecursively()
    assetRoot.mkdirs()
    copyAssetTree(context, BUNDLED_ASSETS_DIR, assetRoot)
    File(assetRoot, SYNC_STAMP_FILE).writeText(bundledStamp)
    Log.d(TAG, "waterui_assets extracted (stamp=$bundledStamp)")
    return assetRoot
}

private fun copyAssetTree(context: Context, assetPath: String, dest: File) {
    val children = context.assets.list(assetPath).orEmpty().filter { it.isNotEmpty() }
    if (children.isEmpty()) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return
    }

    dest.mkdirs()
    for (child in children) {
        copyAssetTree(context, "$assetPath/$child", File(dest, child))
    }
}
