package dev.waterui.android.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Convenience composable that mirrors the Swift `App` entry point.
 *
 * Consumers should provide their own lifecycle-aware environment management; this helper is
 * mainly intended for quick integration tests and the sample application.
 */
@Composable
fun WaterUiRoot(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val environment = remember { WuiEnvironment.create() }
    val rootView = remember(environment) { WuiAnyView.fromRaw(NativeBindings.waterui_main()) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            rootView.close()
            environment.close()
        }
    }

    WaterUiView(env = environment, root = rootView, modifier = modifier)
}
