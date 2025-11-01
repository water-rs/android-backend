package dev.waterui.android.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp

typealias ComponentRenderer = @Composable (view: WuiAnyView, env: WuiEnvironment, modifier: Modifier) -> Unit

object RenderRegistry {
    private val renderers = linkedMapOf<String, ComponentRenderer>()

    fun register(id: String, renderer: ComponentRenderer) {
        renderers[id] = renderer
    }

    fun rendererFor(id: String): ComponentRenderer? = renderers[id]
}

/**
 * Placeholder renderer that simply walks the view tree and dumps identifiers.
 * This gives the Android backend a usable baseline while individual components
 * are still being implemented.
 */
@Composable
fun PlaceholderRenderer(view: WuiAnyView, env: WuiEnvironment, modifier: Modifier = Modifier) {
    val id = remember(view) { view.viewId() }
    Card(
        modifier = modifier.padding(4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = id, style = MaterialTheme.typography.bodyMedium)
            val body = remember(view, env) { view.body(env) }
            body?.let { child ->
                DisposableAnyView(anyView = child) {
                    PlaceholderRenderer(view = it, env = env)
                }
            }
        }
    }
}

@Composable
fun WaterUiView(
    env: WuiEnvironment,
    root: WuiAnyView,
    modifier: Modifier = Modifier
) {
    androidx.compose.runtime.DisposableEffect(root) {
        onDispose { root.close() }
    }

    val id = remember(root) { root.viewId() }
    val renderer = RenderRegistry.rendererFor(id)
    if (renderer != null) {
        renderer(root, env, modifier)
    } else {
        PlaceholderRenderer(root, env, modifier)
    }
}

@Composable
fun DisposableAnyView(
    anyView: WuiAnyView,
    content: @Composable (WuiAnyView) -> Unit
) {
    androidx.compose.runtime.DisposableEffect(anyView) {
        onDispose {
            anyView.close()
        }
    }
    content(anyView)
}
