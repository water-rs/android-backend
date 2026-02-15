package dev.waterui.android.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.reactive.attachTo
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.NavigationControllerCallback
import dev.waterui.android.runtime.NavigationViewStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.TabPosition
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyRustAnimation
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.findActivity
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.withRustAnimator

// ========== Type IDs ==========

private val navigationStackTypeId: WuiTypeId by lazy {
    WatcherJni.navigationStackId().toTypeId()
}

private val navigationViewTypeId: WuiTypeId by lazy {
    WatcherJni.navigationViewId().toTypeId()
}

private val tabsTypeId: WuiTypeId by lazy {
    WatcherJni.tabsId().toTypeId()
}

private val plainTypeId: WuiTypeId by lazy {
    WatcherJni.plainId().toTypeId()
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
    val struct = WatcherJni.forceAsNavigationStack(node.rawPtr)
    val transition = struct.transition

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val toolbar = createTopAppBar(context)

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
    val childEnvPtr = WatcherJni.cloneEnv(env.raw())
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

    val activity = context.findActivity() as? ComponentActivity
    val systemBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewStack.size > 1) {
                WatcherJni.navigationPop(childEnv.raw())
            } else {
                // Allow default system back when we're at root.
                isEnabled = false
                activity?.onBackPressedDispatcher?.onBackPressed()
                isEnabled = true
            }
        }
    }
    activity?.onBackPressedDispatcher?.addCallback(systemBackCallback)

    fun setToolbarTitleView(titleView: View?) {
        currentTitleView?.let { toolbar.removeView(it) }
        currentTitleView = titleView
        if (titleView != null) {
            applyTitleStyleForTopAppBar(titleView)
            toolbar.title = ""
            val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
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
            systemBackCallback.isEnabled = false
            return
        }

        val hiddenValue = top.hidden?.current() ?: false
        val topColor = top.color?.current()

        activeEntryId = top.id

        systemBackCallback.isEnabled = viewStack.size > 1

        // Back behavior: delegate to Rust-driven pop to keep a single code path.
        if (viewStack.size > 1) {
            toolbar.navigationIcon =
                AppCompatResources.getDrawable(
                    context,
                    androidx.appcompat.R.drawable.abc_ic_ab_back_material
                )
            toolbar.setNavigationOnClickListener {
                WatcherJni.navigationPop(childEnv.raw())
            }
        } else {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
        }

        setToolbarTitleView(top.titleView)

        // Hidden
        toolbar.visibility = if (hiddenValue) View.GONE else View.VISIBLE

        // Color (reactive to theme changes via resolved color + bar.color changes)
        applyTopAppBarColors(toolbar, topColor, currentTitleView)
    }

    fun inflateTitleView(titlePtr: Long): View? {
        if (titlePtr == 0L) return null
        var ptr = titlePtr
        // Unwrap through `body()` until we either reach a registered native type
        // (or a title-friendly primitive we can render with Material3 styling).
        repeat(12) {
            val typeId = WatcherJni.viewId(ptr).toTypeId()

            // Prefer rendering plain labels ourselves so we can apply Material3 title
            // styling without fighting the generic label renderer's body font bindings.
            if (typeId == plainTypeId) {
                val plain = WatcherJni.forceAsPlain(ptr)
                val text = plain.textBytes.decodeToString()
                return TextView(context).apply {
                    this.text = text
                    includeFontPadding = false
                    setLineSpacing(0f, 1f)
                    applyTitleStyleForTopAppBar(this)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            }

            // If the runtime knows how to render this view, let the normal inflater
            // handle ownership and lifecycle.
            if (registry.resolve(typeId) != null) {
                val view = inflateAnyView(context, ptr, childEnv, registry)
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                return view
            }

            // Otherwise unwrap one level.
            ptr = WatcherJni.viewBody(ptr, childEnv.raw())
            if (ptr == 0L) return null
        }

        // Give up after a few steps to avoid infinite loops on malformed views.
        return null
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
            val colorPtr = WatcherJni.readComputedColor(navView.bar.colorPtr)
            if (colorPtr != 0L) {
                colorComputed = WuiComputed.resolvedColor(colorPtr, childEnv)
                WatcherJni.dropColor(colorPtr)
            }
            WatcherJni.dropComputedColor(navView.bar.colorPtr)
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

                // Prepare initial state for transition.
                when (transition) {
                    NAV_TRANSITION_PUSH_POP -> {
                        entryView.translationX = contentHost.width.toFloat()
                        entryView.alpha = 1f
                    }
                    NAV_TRANSITION_FADE -> {
                        entryView.translationX = 0f
                        entryView.alpha = 0f
                    }
                    else -> {
                        entryView.translationX = 0f
                        entryView.alpha = 1f
                    }
                }
                contentHost.addView(entryView)
                viewStack.add(entry)

                // Only the top entry should drive toolbar updates.
                entry.hidden?.observeWithAnimation { hidden, animation ->
                    if (entry.id == activeEntryId) {
                        applyTopAppBarVisibility(toolbar, hidden, animation)
                    }
                }
                entry.color?.observeWithAnimation { color, animation ->
                    if (entry.id == activeEntryId) {
                        toolbar.applyRustAnimation(animation) {
                            applyTopAppBarColors(toolbar, color, currentTitleView)
                        }
                    }
                }

                applyToolbarForTop()

                when (transition) {
                    NAV_TRANSITION_PUSH_POP -> {
                        entryView.animate()
                            .translationX(0f)
                            .setDuration(250)
                            .start()
                    }
                    NAV_TRANSITION_FADE -> {
                        entryView.animate()
                            .alpha(1f)
                            .setDuration(250)
                            .start()
                    }
                    else -> {
                        entryView.translationX = 0f
                        entryView.alpha = 1f
                    }
                }
            }
        }

        override fun onPop() {
            contentHost.post {
                if (viewStack.size <= 1) return@post

                val current = viewStack.removeLastOrNull() ?: return@post
                val currentView = current.view

                val completePop = {
                    contentHost.removeView(currentView)
                    current.close()
                }
                when (transition) {
                    NAV_TRANSITION_PUSH_POP -> {
                        currentView.animate()
                            .translationX(contentHost.width.toFloat())
                            .setDuration(250)
                            .withEndAction { completePop() }
                            .start()
                    }
                    NAV_TRANSITION_FADE -> {
                        currentView.animate()
                            .alpha(0f)
                            .setDuration(250)
                            .withEndAction { completePop() }
                            .start()
                    }
                    else -> completePop()
                }

                // Show previous view
                viewStack.lastOrNull()?.view?.visibility = View.VISIBLE
                applyToolbarForTop()
            }
        }
    }

    // Create and install navigation controller
    val controllerPtr = WatcherJni.navigationControllerNew(callback)
    WatcherJni.envInstallNavigationController(childEnvPtr, controllerPtr)

    // Render root view with child environment
    if (struct.rootPtr != 0L) {
        // The NavigationStack root is an AnyView. Many views (including raw_view! types)
        // only become renderable native views after evaluating `body()` once.
        // Pushed NavigationViews come from Rust already as WuiNavigationView structs,
        // but the root is still an AnyView and must be unwrapped to Native<NavigationView>
        // to access its bar/title.
        var rootPtr = struct.rootPtr

        fun addRootAsNavView(navView: NavigationViewStruct) {
            val entry = inflateStackEntry(navView)
            contentHost.addView(entry.view)
            viewStack.add(entry)

            // Initial toolbar wiring
            entry.hidden?.observeWithAnimation { hidden, animation ->
                if (entry.id == activeEntryId) {
                    applyTopAppBarVisibility(toolbar, hidden, animation)
                }
            }
            entry.color?.observeWithAnimation { color, animation ->
                if (entry.id == activeEntryId) {
                    toolbar.applyRustAnimation(animation) {
                        applyTopAppBarColors(toolbar, color, currentTitleView)
                    }
                }
            }
            applyToolbarForTop()
        }

        val directTypeId = WatcherJni.viewId(rootPtr).toTypeId()
        if (directTypeId == navigationViewTypeId) {
            addRootAsNavView(WatcherJni.forceAsNavigationView(rootPtr))
        } else {
            rootPtr = WatcherJni.viewBody(rootPtr, childEnv.raw())
            val bodyTypeId = if (rootPtr != 0L) WatcherJni.viewId(rootPtr).toTypeId() else null
            if (bodyTypeId != null && bodyTypeId == navigationViewTypeId) {
                addRootAsNavView(WatcherJni.forceAsNavigationView(rootPtr))
            } else if (rootPtr != 0L) {
                val rootView = inflateAnyView(context, rootPtr, childEnv, registry)
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
    }

    // Cleanup
    container.disposeWith {
        viewStack.forEach { it.close() }
        systemBackCallback.remove()
        WatcherJni.dropEnv(childEnvPtr)
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
    val struct = WatcherJni.forceAsNavigationView(node.rawPtr)

    // If a navigation controller is installed in the environment, the surrounding
    // NavigationStack owns the chrome; render content only.
    if (WatcherJni.envHasNavigationController(env.raw())) {
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

    var titleViewRef: View? = null
    val navBar = createTopAppBar(context).apply {
        // Title view (AnyView)
        if (struct.bar.titlePtr != 0L) {
            val titleView = inflateAnyView(context, struct.bar.titlePtr, env, registry)
            titleViewRef = titleView
            applyTitleStyleForTopAppBar(titleView)
            val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
            title = ""
            addView(titleView, lp)
            applyTopAppBarColors(this, null, titleView)
        }
    }
    container.addView(navBar)

    // Reactive watchers
    var colorComputed: WuiComputed<ResolvedColorStruct>? = null
    var hiddenComputed: WuiComputed<Boolean>? = null

    // Setup bar color watcher
    if (struct.bar.colorPtr != 0L) {
        val colorPtr = WatcherJni.readComputedColor(struct.bar.colorPtr)
        if (colorPtr != 0L) {
            colorComputed = WuiComputed.resolvedColor(colorPtr, env)
            colorComputed?.observeWithAnimation { newColor, animation ->
                navBar.applyRustAnimation(animation) {
                    applyTopAppBarColors(navBar, newColor, titleViewRef)
                }
            }
            WatcherJni.dropColor(colorPtr)
        }
        WatcherJni.dropComputedColor(struct.bar.colorPtr)
    }

    // Setup bar hidden watcher
    if (struct.bar.hiddenPtr != 0L) {
        hiddenComputed = WuiComputed.bool(struct.bar.hiddenPtr, env)
        hiddenComputed?.observeWithAnimation { hidden, animation ->
            applyTopAppBarVisibility(navBar, hidden, animation)
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
    // Legacy helper retained for older call sites; prefer applyTopAppBarColors.
    navBar.setBackgroundColor(color.toColorInt())
}

private fun createTopAppBar(context: Context): MaterialToolbar {
    val toolbar = MaterialToolbar(
        context,
        null,
        com.google.android.material.R.attr.toolbarStyle
    ).apply {
        val desiredHeight = topAppBarHeightPx(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            desiredHeight
        )
        minimumHeight = desiredHeight
        elevation = 0f
        setTitleMargin(0, 0, 0, 0)
        setTitleTextAppearance(context, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        isTitleCentered = false

        val inset = (16f * context.resources.displayMetrics.density).toInt()
        setContentInsetsRelative(inset, inset)
        contentInsetStartWithNavigation = inset
        setContentInsetEndWithActions(inset)
    }
    applyTopAppBarColors(toolbar, null, null)
    return toolbar
}

private fun topAppBarHeightPx(context: Context): Int {
    val resources = context.resources
    val resId = resources.getIdentifier(
        "m3_appbar_size_small",
        "dimen",
        "com.google.android.material"
    )
    if (resId != 0) {
        try {
            return resources.getDimensionPixelSize(resId)
        } catch (_: android.content.res.Resources.NotFoundException) {
            // fall through to dp fallback
        }
    }
    return (64f * resources.displayMetrics.density).toInt()
}

private fun defaultTopAppBarContainerColor(context: Context): Int {
    val fallbackSurface = MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorSurface,
        Color.WHITE
    )
    // Material3 token (only present for M3 themes).
    return MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorSurfaceContainer,
        fallbackSurface
    )
}

private fun defaultTopAppBarContentColor(context: Context): Int {
    return MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorOnSurface,
        Color.BLACK
    )
}

private fun bestContrastingContentColor(background: Int): Int {
    val luminance = ColorUtils.calculateLuminance(background)
    return if (luminance > 0.5) Color.BLACK else Color.WHITE
}

private fun applyTitleStyleForTopAppBar(titleView: View) {
    if (titleView is TextView) {
        TextViewCompat.setTextAppearance(
            titleView,
            com.google.android.material.R.style.TextAppearance_Material3_TitleLarge
        )
        titleView.isSingleLine = true
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
    }
}

private fun applyTopAppBarColors(toolbar: MaterialToolbar, color: ResolvedColorStruct?, titleView: View?) {
    val bg = if (color == null || color.opacity <= 0.001f) {
        defaultTopAppBarContainerColor(toolbar.context)
    } else {
        color.toColorInt()
    }
    toolbar.setBackgroundColor(bg)
    toolbar.backgroundTintList = ColorStateList.valueOf(bg)

    val contentColor = if (color == null || color.opacity <= 0.001f) {
        defaultTopAppBarContentColor(toolbar.context)
    } else {
        bestContrastingContentColor(bg)
    }
    toolbar.setTitleTextColor(contentColor)
    toolbar.setNavigationIconTint(contentColor)

    if (titleView is TextView) {
        titleView.setTextColor(contentColor)
    }

    syncSystemBarsWithTopAppBar(toolbar, bg)
}

private fun applyTopAppBarVisibility(toolbar: View, hidden: Boolean, animation: WuiAnimation) {
    toolbar.animate().cancel()

    if (!toolbar.isAttachedToWindow || !animation.shouldAnimate) {
        toolbar.alpha = 1f
        toolbar.visibility = if (hidden) View.GONE else View.VISIBLE
        return
    }

    if (hidden) {
        toolbar.visibility = View.VISIBLE
        toolbar.withRustAnimator(animation) {
            alpha(0f)
            withEndAction {
                toolbar.visibility = View.GONE
                toolbar.alpha = 1f
            }
        }
        return
    }

    toolbar.alpha = 0f
    toolbar.visibility = View.VISIBLE
    toolbar.withRustAnimator(animation) {
        alpha(1f)
    }
}

private fun syncSystemBarsWithTopAppBar(toolbar: MaterialToolbar, appBarColor: Int) {
    val activity = toolbar.context.findActivity() ?: return
    val window = activity.window ?: return

    window.statusBarColor = appBarColor

    val navBarColor = MaterialColors.getColor(
        toolbar,
        com.google.android.material.R.attr.colorSurface,
        appBarColor
    )
    window.navigationBarColor = navBarColor

    val insetsController = WindowCompat.getInsetsController(window, toolbar)
    insetsController.isAppearanceLightStatusBars = ColorUtils.calculateLuminance(appBarColor) > 0.5
    insetsController.isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(navBarColor) > 0.5
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
    val struct = WatcherJni.forceAsTabs(node.rawPtr)
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
            val navViewStruct = WatcherJni.tabContent(tab.contentPtr)
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

    val tabLayout = TabLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tabMode = TabLayout.MODE_FIXED
        tabGravity = TabLayout.GRAVITY_FILL
    }

    struct.tabs.forEach { tab ->
        val tabItem = tabLayout.newTab().apply {
            tag = tab.id
            if (tab.labelPtr == 0L) {
                error("Tabs: tab labelPtr is null (id=${tab.id})")
            }
            customView = inflateAnyView(context, tab.labelPtr, env, registry)
        }
        tabLayout.addTab(tabItem)
    }

    tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val index = tab?.position ?: return
            showTab(index)
            val tabId = struct.tabs.getOrNull(index)?.id?.toInt() ?: return
            if (selectionBinding?.current() != tabId) {
                selectionBinding?.set(tabId)
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

    when (position) {
        TabPosition.TOP -> {
            container.addView(tabLayout)
            container.addView(contentContainer)
        }
        TabPosition.BOTTOM -> {
            container.addView(contentContainer)
            container.addView(tabLayout)
        }
    }

    val initialIndex = struct.tabs.indexOfFirst {
        it.id.toInt() == (selectionBinding?.current() ?: 0)
    }.takeIf { it >= 0 } ?: 0
    showTab(initialIndex)
    tabLayout.getTabAt(initialIndex)?.select()

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
        val navBar = createTopAppBar(context)
        val titleView = inflateAnyView(context, navViewStruct.bar.titlePtr, env, registry)
        applyTitleStyleForTopAppBar(titleView)
        val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL
        )
        navBar.title = ""
        navBar.addView(titleView, lp)
        applyTopAppBarColors(navBar, null, titleView)
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

private const val NAV_TRANSITION_PUSH_POP = 0
private const val NAV_TRANSITION_FADE = 1

internal fun RegistryBuilder.registerWuiTabs() {
    register({ tabsTypeId }, tabsRenderer)
}
