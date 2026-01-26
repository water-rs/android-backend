package dev.waterui.android.components

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.reactive.attachTo
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NavigationControllerCallback
import dev.waterui.android.runtime.NavigationViewStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.TabPosition
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

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
 * Displays a navigation stack with full push/pop support.
 * Creates a NavigationController that receives push/pop callbacks from Rust.
 * Expands to fill available space (StretchAxis.BOTH).
 */
private val navigationStackRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_navigation_stack(node.rawPtr)

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val toolbar = MaterialToolbar(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Default Material 3 surface color
        val surfaceColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        setBackgroundColor(surfaceColor)
    }

    val contentHost = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
    }

    container.addView(toolbar)
    container.addView(contentHost)

    // Clone environment for child views
    val childEnvPtr = NativeBindings.waterui_clone_env(env.raw())
    val childEnv = WuiEnvironment(childEnvPtr)

    data class StackEntry(
        val id: Long,
        val view: View,
        val titleView: View?,
        val color: WuiComputed<ResolvedColorStruct>?,
        val hidden: WuiComputed<Boolean>?,
        val displayMode: Int
    ) {
        fun close() {
            color?.close()
            hidden?.close()
        }
    }

    var nextEntryId = 1L
    var activeEntryId = 0L
    var currentTitleView: View? = null
    val viewStack = mutableListOf<StackEntry>()

    fun setToolbarTitleView(titleView: View?) {
        currentTitleView?.let { toolbar.removeView(it) }
        currentTitleView = titleView
        if (titleView != null) {
            toolbar.title = ""
            val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            toolbar.addView(titleView, lp)
        }
    }

    fun applyToolbarForTop() {
        val top = viewStack.lastOrNull()
        if (top == null) {
            toolbar.visibility = View.GONE
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
            setToolbarTitleView(null)
            return
        }

        // If the top entry doesn't provide any navigation chrome, keep the toolbar hidden.
        if (viewStack.size == 1 && top.titleView == null && top.hidden == null && top.color == null) {
            toolbar.visibility = View.GONE
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
            setToolbarTitleView(null)
            return
        }

        activeEntryId = top.id

        // Back behavior: delegate to Rust-driven pop to keep a single code path.
        if (viewStack.size > 1) {
            toolbar.navigationIcon = AppCompatResources.getDrawable(
                context,
                androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )
            toolbar.setNavigationOnClickListener {
                NativeBindings.waterui_navigation_pop(childEnv.raw())
            }
        } else {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
        }

        setToolbarTitleView(top.titleView)

        // Hidden
        val hiddenValue = top.hidden?.current() ?: false
        toolbar.visibility = if (hiddenValue) View.GONE else View.VISIBLE

        // Color (reactive to theme changes via resolved color, not reactive to bar.color changes)
        top.color?.current()?.let { applyNavBarColor(toolbar, it) }
    }

    fun inflateTitleView(titlePtr: Long): View? {
        if (titlePtr == 0L) return null
        val view = inflateAnyView(context, titlePtr, childEnv, registry)
        // Let toolbar measure it naturally
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return view
    }

    fun inflateStackEntry(navView: NavigationViewStruct): StackEntry {
        val entryId = nextEntryId++

        val titleView = inflateTitleView(navView.bar.titlePtr)

        val viewContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        if (navView.contentPtr != 0L) {
            val contentView = inflateAnyView(context, navView.contentPtr, childEnv, registry)
            contentView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            viewContainer.addView(contentView)
        }

        var colorComputed: WuiComputed<ResolvedColorStruct>? = null
        if (navView.bar.colorPtr != 0L) {
            val colorPtr = NativeBindings.waterui_read_computed_color(navView.bar.colorPtr)
            if (colorPtr != 0L) {
                colorComputed = WuiComputed.resolvedColor(colorPtr, childEnv)
                NativeBindings.waterui_drop_color(colorPtr)
            }
            NativeBindings.waterui_drop_computed_color(navView.bar.colorPtr)
        }

        var hiddenComputed: WuiComputed<Boolean>? = null
        if (navView.bar.hiddenPtr != 0L) {
            hiddenComputed = WuiComputed.bool(navView.bar.hiddenPtr, childEnv)
        }

        return StackEntry(
            id = entryId,
            view = viewContainer,
            titleView = titleView,
            color = colorComputed,
            hidden = hiddenComputed,
            displayMode = navView.bar.displayMode
        )
    }

    // Create navigation controller callback
    val callback = object : NavigationControllerCallback {
        override fun onPush(navView: NavigationViewStruct) {
            contentHost.post {
                // Hide current view
                viewStack.lastOrNull()?.view?.visibility = View.GONE

                val entry = inflateStackEntry(navView)
                val entryView = entry.view

                // Keep offscreen for slide-in.
                entryView.translationX = contentHost.width.toFloat()
                contentHost.addView(entryView)
                viewStack.add(entry)

                // Only the top entry should drive toolbar updates.
                entry.hidden?.observe { hidden ->
                    if (entry.id == activeEntryId) {
                        toolbar.visibility = if (hidden) View.GONE else View.VISIBLE
                    }
                }
                entry.color?.observe { color ->
                    if (entry.id == activeEntryId) {
                        applyNavBarColor(toolbar, color)
                    }
                }

                applyToolbarForTop()

                entryView.animate()
                    .translationX(0f)
                    .setDuration(250)
                    .start()
            }
        }

        override fun onPop() {
            contentHost.post {
                if (viewStack.size <= 1) return@post

                val current = viewStack.removeLastOrNull() ?: return@post
                val currentView = current.view

                // Animate out
                currentView.animate()
                    .translationX(contentHost.width.toFloat())
                    .setDuration(250)
                    .withEndAction {
                        contentHost.removeView(currentView)
                        current.close()
                    }
                    .start()

                // Show previous view
                viewStack.lastOrNull()?.view?.visibility = View.VISIBLE
                applyToolbarForTop()
            }
        }
    }

    // Create and install navigation controller
    val controllerPtr = NativeBindings.waterui_navigation_controller_new(callback)
    NativeBindings.waterui_env_install_navigation_controller(childEnvPtr, controllerPtr)

    // Render root view with child environment
    if (struct.rootPtr != 0L) {
        val rootTypeId = NativeBindings.waterui_view_id(struct.rootPtr).toTypeId()
        if (rootTypeId == navigationViewTypeId) {
            val navView = NativeBindings.waterui_force_as_navigation_view(struct.rootPtr)
            val entry = inflateStackEntry(navView)
            contentHost.addView(entry.view)
            viewStack.add(entry)

            // Initial toolbar wiring
            entry.hidden?.observe { hidden ->
                if (entry.id == activeEntryId) {
                    toolbar.visibility = if (hidden) View.GONE else View.VISIBLE
                }
            }
            entry.color?.observe { color ->
                if (entry.id == activeEntryId) {
                    applyNavBarColor(toolbar, color)
                }
            }
            applyToolbarForTop()
        } else {
            val rootView = inflateAnyView(context, struct.rootPtr, childEnv, registry)
            rootView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            contentHost.addView(rootView)
            viewStack.add(
                StackEntry(
                    id = 0L,
                    view = rootView,
                    titleView = null,
                    color = null,
                    hidden = null,
                    displayMode = 0
                )
            )
            applyToolbarForTop()
        }
    }

    // Cleanup
    container.disposeWith {
        viewStack.forEach { it.close() }
        NativeBindings.waterui_env_drop(childEnvPtr)
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

    // If a navigation controller is installed in the environment, the surrounding
    // NavigationStack owns the chrome; render content only.
    if (NativeBindings.waterui_env_has_navigation_controller(env.raw())) {
        return@WuiRenderer if (struct.contentPtr != 0L) {
            inflateAnyView(context, struct.contentPtr, env, registry)
        } else {
            FrameLayout(context)
        }
    }

    // Create vertical layout: nav bar at top, content below
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val navBar = MaterialToolbar(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Default Material 3 surface color
        val surfaceColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        setBackgroundColor(surfaceColor)

        // Title view (AnyView)
        if (struct.bar.titlePtr != 0L) {
            val titleView = inflateAnyView(context, struct.bar.titlePtr, env, registry)
            val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            title = ""
            addView(titleView, lp)
        }
    }
    container.addView(navBar)

    // Reactive watchers
    var colorComputed: WuiComputed<ResolvedColorStruct>? = null
    var hiddenComputed: WuiComputed<Boolean>? = null

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
        NativeBindings.waterui_drop_computed_color(struct.bar.colorPtr)
    }

    // Setup bar hidden watcher
    if (struct.bar.hiddenPtr != 0L) {
        hiddenComputed = WuiComputed.bool(struct.bar.hiddenPtr, env)
        hiddenComputed?.observe { hidden ->
            navBar.visibility = if (hidden) View.GONE else View.VISIBLE
        }
    }

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
        hiddenComputed?.close()
    }

    container
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
                    tag = tab.id
                    if (tab.labelPtr == 0L) {
                        error("Tabs: tab labelPtr is null (id=${tab.id})")
                    }
                    val labelView = inflateAnyView(context, tab.labelPtr, env, registry)
                    customView = labelView
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
            // Use TabLayout at bottom as well to avoid forcing string-only labels.
            val tabLayout = TabLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                tabMode = TabLayout.MODE_FIXED
                tabGravity = TabLayout.GRAVITY_FILL
            }

            struct.tabs.forEachIndexed { index, tab ->
                val tabItem = tabLayout.newTab().apply {
                    tag = tab.id
                    if (tab.labelPtr == 0L) {
                        error("Tabs: tab labelPtr is null (id=${tab.id})")
                    }
                    val labelView = inflateAnyView(context, tab.labelPtr, env, registry)
                    customView = labelView
                }
                tabLayout.addTab(tabItem)
            }

            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    tab?.let {
                        val index = it.position
                        showTab(index)
                        val tabId = struct.tabs.getOrNull(index)?.id?.toInt() ?: return
                        if (selectionBinding?.current() != tabId) {
                            selectionBinding?.set(tabId)
                        }
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })

            selectionBinding?.observe { selectedId ->
                val index = struct.tabs.indexOfFirst { it.id.toInt() == selectedId }
                if (index >= 0 && tabLayout.selectedTabPosition != index) {
                    tabLayout.getTabAt(index)?.select()
                }
            }

            container.addView(contentContainer)
            container.addView(tabLayout)

            val initialIndex = struct.tabs.indexOfFirst {
                it.id.toInt() == (selectionBinding?.current() ?: 0)
            }.takeIf { it >= 0 } ?: 0
            showTab(initialIndex)
            tabLayout.getTabAt(initialIndex)?.select()
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
    if (navViewStruct.bar.titlePtr != 0L) {
        val navBar = MaterialToolbar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            val surfaceColor = MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorSurface,
                Color.WHITE
            )
            setBackgroundColor(surfaceColor)

            val titleView = inflateAnyView(context, navViewStruct.bar.titlePtr, env, registry)
            val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            title = ""
            addView(titleView, lp)
        }
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
