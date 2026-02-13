package dev.waterui.android.runtime

import dev.waterui.android.ffi.WatcherJni

fun bootstrapWaterUiRuntime() {
    WatcherJni
}

fun configureHotReloadEndpoint(host: String, port: Int) {
    WatcherJni.configureHotReloadEndpoint(host, port)
}

fun configureHotReloadDirectory(path: String) {
    WatcherJni.configureHotReloadDirectory(path)
}
