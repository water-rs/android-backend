package dev.waterui.android.components

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.TabPosition
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView

// ========== Type IDs ==========

private val navigationStackTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_navigation_stack_id().toTypeId()
}

private val navigationViewTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_navigation_view_id().toTypeId()
}

private val tabsTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_tabs_id().toTypeId()
}

// ========== NavigationStack Renderer ==========

/**
 * NavigationStack component renderer.
 *
 * Displays a navigation stack with the root view.
 * Expands to fill available space (StretchAxis.BOTH).
 */
private val navigationStackRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_navigation_stack(node.rawPtr)

    val container = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    if (struct.rootPtr != 0L) {
        val rootView = inflateAnyView(context, struct.rootPtr, env, registry)
        rootView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(rootView)
    }

    container
}

// ========== NavigationView Renderer ==========

/**
 * NavigationView component renderer.
 *
 * Displays a navigation bar with title and content area.
 * Supports reactive bar color and hidden state.
 * Expands to fill available space (StretchAxis.BOTH).
 */
private val navigationViewRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_navigation_view(node.rawPtr)

    // Create vertical layout: nav bar at top, content below
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // Create navigation bar
    val navBar = createNavBar(context, struct.bar.titleContentPtr, env)
    container.addView(navBar)

    // Reactive watchers
    var colorComputed: WuiComputed<ResolvedColorStruct>? = null

    // Setup bar color watcher
    if (struct.bar.colorPtr != 0L) {
        val colorPtr = NativeBindings.waterui_read_computed_color(struct.bar.colorPtr)
        if (colorPtr != 0L) {
            colorComputed = WuiComputed.resolvedColor(colorPtr, env)
            colorComputed?.observe { newColor ->
                applyNavBarColor(navBar, newColor)
            }
            NativeBindings.waterui_drop_color(colorPtr)
        }
    }

    // Note: bar.hidden is Computed<bool> but we don't have JNI support for computed bool yet
    // For now, skip hidden state reactivity - nav bar is always visible

    // Content area
    val contentContainer = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f  // Take remaining space
        )
    }

    if (struct.contentPtr != 0L) {
        val contentView = inflateAnyView(context, struct.contentPtr, env, registry)
        contentView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        contentContainer.addView(contentView)
    }

    container.addView(contentContainer)

    // Cleanup
    container.disposeWith {
        colorComputed?.close()
    }

    container
}

private fun createNavBar(context: android.content.Context, titleContentPtr: Long, env: WuiEnvironment): LinearLayout {
    val navBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (56 * context.resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setBackgroundColor(Color.parseColor("#F5F5F5"))
        setPadding(
            (16 * context.resources.displayMetrics.density).toInt(),
            (8 * context.resources.displayMetrics.density).toInt(),
            (16 * context.resources.displayMetrics.density).toInt(),
            (8 * context.resources.displayMetrics.density).toInt()
        )
    }

    // Title text
    val titleView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.BLACK)

        // Read title from Computed<StyledStr>
        if (titleContentPtr != 0L) {
            val styledStr = NativeBindings.waterui_read_computed_styled_str(titleContentPtr)
            text = styledStr.chunks.joinToString("") { it.text }
        }
    }

    navBar.addView(titleView)
    return navBar
}

private fun applyNavBarColor(navBar: View, color: ResolvedColorStruct) {
    val argb = Color.argb(
        (color.opacity * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
    navBar.setBackgroundColor(argb)
}

// ========== Tabs Renderer ==========

/**
 * Tabs component renderer.
 *
 * Displays a tab container with customizable tab bar position (top or bottom).
 * Uses TabLayout for top position, BottomNavigationView for bottom position.
 * Expands to fill available space (StretchAxis.BOTH).
 */
private val tabsRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_tabs(node.rawPtr)
    val position = TabPosition.fromInt(struct.position)

    // Create container
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // Content container
    val contentContainer = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f  // Take remaining space
        )
    }

    // Track current tab content
    var currentContentView: View? = null

    // Setup selection binding
    var selectionBinding: WuiBinding<Int>? = null
    if (struct.selectionPtr != 0L) {
        selectionBinding = WuiBinding.int(struct.selectionPtr, env)
    }

    // Tab switching logic
    fun showTab(index: Int) {
        if (index < 0 || index >= struct.tabs.size) return

        // Remove old content
        currentContentView?.let { contentContainer.removeView(it) }

        // Build tab content (calls waterui_tab_content to get NavigationView)
        val tab = struct.tabs[index]
        if (tab.contentPtr != 0L) {
            val navViewStruct = NativeBindings.waterui_tab_content(tab.contentPtr)
            // Create a NavigationView-like layout for the tab content
            val tabContent = inflateTabContent(context, navViewStruct, env, registry)
            tabContent.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            contentContainer.addView(tabContent)
            currentContentView = tabContent
        }
    }

    // Create tab bar based on position
    when (position) {
        TabPosition.TOP -> {
            // TabLayout at top
            val tabLayout = TabLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                tabMode = TabLayout.MODE_FIXED
                tabGravity = TabLayout.GRAVITY_FILL
            }

            // Add tabs
            struct.tabs.forEachIndexed { index, tab ->
                val tabItem = tabLayout.newTab().apply {
                    text = "Tab ${index + 1}"
                    tag = tab.id
                }
                tabLayout.addTab(tabItem)
            }

            // Handle tab selection
            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    tab?.let {
                        val index = it.position
                        showTab(index)
                        // Update binding
                        val tabId = struct.tabs.getOrNull(index)?.id?.toInt() ?: return
                        if (selectionBinding?.current() != tabId) {
                            selectionBinding?.set(tabId)
                        }
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })

            // Watch for selection changes from binding
            selectionBinding?.observe { selectedId ->
                val index = struct.tabs.indexOfFirst { it.id.toInt() == selectedId }
                if (index >= 0 && tabLayout.selectedTabPosition != index) {
                    tabLayout.getTabAt(index)?.select()
                }
            }

            // Layout: tab bar at top, content below
            container.addView(tabLayout)
            container.addView(contentContainer)

            // Show initial tab
            val initialIndex = struct.tabs.indexOfFirst {
                it.id.toInt() == (selectionBinding?.current() ?: 0)
            }.takeIf { it >= 0 } ?: 0
            showTab(initialIndex)
            tabLayout.getTabAt(initialIndex)?.select()
        }

        TabPosition.BOTTOM -> {
            // BottomNavigationView at bottom
            val bottomNav = BottomNavigationView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Add menu items programmatically
            struct.tabs.forEachIndexed { index, tab ->
                bottomNav.menu.add(0, tab.id.toInt(), index, "Tab ${index + 1}")
            }

            // Handle navigation selection
            bottomNav.setOnItemSelectedListener { menuItem ->
                val index = struct.tabs.indexOfFirst { it.id.toInt() == menuItem.itemId }
                if (index >= 0) {
                    showTab(index)
                    // Update binding
                    if (selectionBinding?.current() != menuItem.itemId) {
                        selectionBinding?.set(menuItem.itemId)
                    }
                }
                true
            }

            // Watch for selection changes from binding
            selectionBinding?.observe { selectedId ->
                if (bottomNav.selectedItemId != selectedId) {
                    bottomNav.selectedItemId = selectedId
                }
            }

            // Layout: content at top, tab bar at bottom
            container.addView(contentContainer)
            container.addView(bottomNav)

            // Show initial tab
            val initialId = selectionBinding?.current() ?: struct.tabs.firstOrNull()?.id?.toInt() ?: 0
            val initialIndex = struct.tabs.indexOfFirst { it.id.toInt() == initialId }.takeIf { it >= 0 } ?: 0
            showTab(initialIndex)
            bottomNav.selectedItemId = struct.tabs.getOrNull(initialIndex)?.id?.toInt() ?: 0
        }
    }

    // Cleanup
    container.disposeWith {
        selectionBinding?.close()
    }

    container
}

/**
 * Inflates the content of a tab from NavigationViewStruct.
 * Creates a simple container with the navigation view content.
 */
private fun inflateTabContent(
    context: android.content.Context,
    navViewStruct: dev.waterui.android.runtime.NavigationViewStruct,
    env: WuiEnvironment,
    registry: dev.waterui.android.runtime.RenderRegistry
): View {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // Create navigation bar if title is provided
    if (navViewStruct.bar.titleContentPtr != 0L) {
        val navBar = createNavBar(context, navViewStruct.bar.titleContentPtr, env)
        container.addView(navBar)
    }

    // Content area
    val contentContainer = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f  // Take remaining space
        )
    }

    if (navViewStruct.contentPtr != 0L) {
        val contentView = inflateAnyView(context, navViewStruct.contentPtr, env, registry)
        contentView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        contentContainer.addView(contentView)
    }

    container.addView(contentContainer)
    return container
}

// ========== Registration ==========

internal fun RegistryBuilder.registerWuiNavigationStack() {
    register({ navigationStackTypeId }, navigationStackRenderer)
}

internal fun RegistryBuilder.registerWuiNavigationView() {
    register({ navigationViewTypeId }, navigationViewRenderer)
}

internal fun RegistryBuilder.registerWuiTabs() {
    register({ tabsTypeId }, tabsRenderer)
}
