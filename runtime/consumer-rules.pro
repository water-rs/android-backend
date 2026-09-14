# Rust constructs these JNI projection types by their stable class/member names.
-keep class dev.waterui.android.runtime.*Struct { *; }
-keep enum dev.waterui.android.runtime.StretchAxis { *; }
-keep enum dev.waterui.android.runtime.HorizontalAlignment { *; }
-keep enum dev.waterui.android.runtime.VerticalAlignment { *; }

# Rust constructs WuiWatcherMetadata by name and embeds it in the watcher
# callback's JNI signature.
-keep class dev.waterui.android.reactive.WuiWatcherMetadata { *; }

# Rust calls onChanged(Object, WuiWatcherMetadata) by name on every
# WatcherCallback implementation, including compiler-generated lambda classes.
-keep interface dev.waterui.android.reactive.WatcherCallback
-keepclassmembers class * implements dev.waterui.android.reactive.WatcherCallback {
    void onChanged(java.lang.Object, dev.waterui.android.reactive.WuiWatcherMetadata);
}

# The WebView bridge constructs NativeWebViewEventCallback and calls
# WebViewFactory.create plus WebViewWrapper methods by name. The
# WebViewEventCallback interface name is embedded in JNI method signatures.
-keep class dev.waterui.android.components.WebViewFactory { *; }
-keep class dev.waterui.android.components.WebViewWrapper { *; }
-keep class dev.waterui.android.components.NativeWebViewEventCallback { *; }
-keep interface dev.waterui.android.components.WebViewEventCallback
