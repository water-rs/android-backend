package dev.waterui.android.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.BarStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NavigationSearchStruct
import dev.waterui.android.runtime.NavigationStackStruct
import dev.waterui.android.runtime.NavigationViewStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.SplitNavigationContainerStruct
import dev.waterui.android.runtime.TabPosition
import dev.waterui.android.runtime.TabsStruct
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView

private val navigationStackTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_navigation_stack_id().toTypeId()
}

private val navigationViewTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_navigation_view_id().toTypeId()
}

private val tabsTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_tabs_id().toTypeId()
}

private val splitNavigationContainerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_split_navigation_container_id().toTypeId()
}

private const val DISPLAY_MODE_AUTOMATIC = 0
private const val DISPLAY_MODE_INLINE = 1
private const val DISPLAY_MODE_LARGE = 2

private data class NavigationBarSpec(
    val titleView: View?,
    val leadingView: View?,
    val trailingView: View?,
    val searchBinding: WuiBinding<String>?,
    val searchPrompt: String,
    val colorComputed: WuiComputed<ResolvedColorStruct>?,
    val hiddenComputed: WuiComputed<Boolean>?,
    val displayMode: Int
) {
    fun close() {
        searchBinding?.close()
        colorComputed?.close()
        hiddenComputed?.close()
    }
}

private data class NavigationEntry(
    val contentView: View,
    val barSpec: NavigationBarSpec?
)

private fun dp(context: Context, value: Float): Int =
    (value * context.resources.displayMetrics.density).toInt()

private fun detachFromParent(view: View?) {
    val parent = view?.parent
    if (parent is ViewGroup) {
        parent.removeView(view)
    }
}

private fun extractTextFromView(view: View): String? {
    if (view is TextView) {
        val text = view.text?.toString().orEmpty()
        if (text.isNotEmpty()) {
            return text
        }
    }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            val nested = extractTextFromView(view.getChildAt(index))
            if (!nested.isNullOrEmpty()) {
                return nested
            }
        }
    }
    return null
}

private fun applyNavBarColor(target: View, color: ResolvedColorStruct) {
    val argb = Color.argb(
        (color.opacity * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
    target.setBackgroundColor(argb)
}

private fun barMinHeight(context: Context, displayMode: Int): Int =
    when (displayMode) {
        DISPLAY_MODE_LARGE -> dp(context, 88f)
        DISPLAY_MODE_INLINE -> dp(context, 56f)
        else -> dp(context, 64f)
    }

private fun buildBarSpec(
    context: Context,
    bar: BarStruct,
    env: WuiEnvironment,
    registry: RenderRegistry
): NavigationBarSpec {
    val titleView = if (bar.titlePtr != 0L) inflateAnyView(context, bar.titlePtr, env, registry) else null
    val leadingView =
        if (bar.leadingPtr != 0L) inflateAnyView(context, bar.leadingPtr, env, registry) else null
    val trailingView =
        if (bar.trailingPtr != 0L) inflateAnyView(context, bar.trailingPtr, env, registry) else null

    val searchBinding = bar.search?.textPtr?.takeIf { it != 0L }?.let { WuiBinding.str(it, env) }
    val searchPrompt = bar.search?.promptPtr?.takeIf { it != 0L }?.let { promptPtr ->
        extractTextFromView(inflateAnyView(context, promptPtr, env, registry)).orEmpty()
    }.orEmpty()

    val colorComputed = bar.colorPtr.takeIf { it != 0L }?.let { colorPtr ->
        val rawColor = NativeBindings.waterui_read_computed_color(colorPtr)
        if (rawColor == 0L) {
            null
        } else {
            WuiComputed.resolvedColor(rawColor, env).also {
                NativeBindings.waterui_drop_color(rawColor)
            }
        }
    }

    val hiddenComputed = bar.hiddenPtr.takeIf { it != 0L }?.let { WuiComputed.bool(it, env) }

    return NavigationBarSpec(
        titleView = titleView,
        leadingView = leadingView,
        trailingView = trailingView,
        searchBinding = searchBinding,
        searchPrompt = searchPrompt,
        colorComputed = colorComputed,
        hiddenComputed = hiddenComputed,
        displayMode = bar.displayMode
    )
}

private class NavigationBarView(context: Context) : LinearLayout(context) {
    private val headerRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private val leadingSlot = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    private val titleSlot = FrameLayout(context).apply {
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }

    private val trailingSlot = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    private val searchSlot = FrameLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private var activeColorComputed: WuiComputed<ResolvedColorStruct>? = null
    private var activeHiddenComputed: WuiComputed<Boolean>? = null
    private var activeSearchBinding: WuiBinding<String>? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(headerRow)
        addView(searchSlot)
        headerRow.addView(leadingSlot)
        headerRow.addView(titleSlot)
        headerRow.addView(trailingSlot)
        setPadding(dp(context, 16f), dp(context, 8f), dp(context, 16f), dp(context, 8f))
    }

    fun bind(spec: NavigationBarSpec?, showBack: Boolean, onBack: (() -> Unit)?) {
        activeColorComputed?.observe { _ -> }
        activeHiddenComputed?.observe { _ -> }
        activeSearchBinding?.observe { _ -> }

        leadingSlot.removeAllViews()
        titleSlot.removeAllViews()
        trailingSlot.removeAllViews()
        searchSlot.removeAllViews()

        activeColorComputed = spec?.colorComputed
        activeHiddenComputed = spec?.hiddenComputed
        activeSearchBinding = spec?.searchBinding

        if (spec == null) {
            visibility = GONE
            return
        }

        visibility = VISIBLE
        minimumHeight = barMinHeight(context, spec.displayMode)

        if (showBack && onBack != null) {
            val back = TextView(context).apply {
                text = "←"
                textSize = 20f
                setTextColor(Color.BLACK)
                setPadding(dp(context, 4f), dp(context, 4f), dp(context, 12f), dp(context, 4f))
                isClickable = true
                isFocusable = true
                setOnClickListener { onBack() }
            }
            leadingSlot.addView(back)
        }

        detachFromParent(spec.leadingView)
        detachFromParent(spec.titleView)
        detachFromParent(spec.trailingView)

        spec.leadingView?.let { leadingSlot.addView(it) }
        spec.titleView?.let { titleSlot.addView(it) }
        spec.trailingView?.let { trailingSlot.addView(it) }

        if (spec.searchBinding != null) {
            val searchField = EditText(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                hint = spec.searchPrompt
                setSingleLine()
                setText(spec.searchBinding.current())
            }
            var syncing = false
            searchField.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (syncing) {
                        return
                    }
                    spec.searchBinding.set(s?.toString().orEmpty())
                }
            })
            spec.searchBinding.observe { value ->
                if (searchField.text.toString() == value) {
                    return@observe
                }
                syncing = true
                searchField.setText(value)
                searchField.setSelection(value.length)
                syncing = false
            }
            searchSlot.addView(searchField)
        }

        spec.colorComputed?.observe { color ->
            applyNavBarColor(this, color)
        }
        spec.hiddenComputed?.observe { hidden ->
            visibility = if (hidden) GONE else VISIBLE
        }
        spec.hiddenComputed?.current()?.let { hidden ->
            visibility = if (hidden) GONE else VISIBLE
        }
        spec.colorComputed?.current()?.let { color ->
            applyNavBarColor(this, color)
        }
    }
}

private class AndroidNavigationStackView(
    context: Context,
    private val transition: Int,
    private val childEnv: WuiEnvironment,
    private val registry: RenderRegistry
) : LinearLayout(context) {
    private val barView = NavigationBarView(context)
    private val contentContainer = FrameLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
    }
    private val entries = mutableListOf<NavigationEntry>()

    init {
        orientation = VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(barView)
        addView(contentContainer)
    }

    fun installRoot(rootView: View, rootBar: NavigationBarSpec?) {
        contentContainer.removeAllViews()
        entries.clear()
        entries += NavigationEntry(rootView, rootBar)
        rootView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        contentContainer.addView(rootView)
        updateChrome()
    }

    fun push(navView: NavigationViewStruct) {
        val entry = buildNavigationEntry(context, navView, childEnv, registry)
        val previous = entries.lastOrNull()?.contentView
        previous?.visibility = View.GONE

        entry.contentView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        contentContainer.addView(entry.contentView)
        entries += entry
        updateChrome()
        animateTransition(previous, entry.contentView, isPush = true)
    }

    fun pop() {
        if (entries.size <= 1) {
            return
        }

        val current = entries.removeLast()
        val previous = entries.last()
        previous.contentView.visibility = View.VISIBLE
        updateChrome()
        animateTransition(previous.contentView, current.contentView, isPush = false) {
            contentContainer.removeView(current.contentView)
            current.barSpec?.close()
        }
    }

    fun close() {
        entries.forEach { it.barSpec?.close() }
        entries.clear()
    }

    private fun updateChrome() {
        val active = entries.lastOrNull()
        barView.bind(
            spec = active?.barSpec,
            showBack = entries.size > 1,
            onBack = { pop() }
        )
    }

    private fun animateTransition(from: View?, to: View, isPush: Boolean, onEnd: (() -> Unit)? = null) {
        when (transition) {
            1 -> {
                to.alpha = 0f
                to.animate().alpha(1f).setDuration(220L).start()
                from?.animate()?.alpha(0f)?.setDuration(220L)?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        from.alpha = 1f
                        onEnd?.invoke()
                    }
                })?.start() ?: onEnd?.invoke()
            }
            2 -> onEnd?.invoke()
            else -> {
                val width = if (contentContainer.width > 0) contentContainer.width.toFloat() else dp(context, 240f).toFloat()
                to.translationX = if (isPush) width else -width * 0.1f
                to.alpha = 1f
                to.animate().translationX(0f).setDuration(240L).start()
                from?.animate()
                    ?.translationX(if (isPush) -width * 0.2f else width)
                    ?.alpha(0.9f)
                    ?.setDuration(240L)
                    ?.setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            from.translationX = 0f
                            from.alpha = 1f
                            onEnd?.invoke()
                        }
                    })
                    ?.start() ?: onEnd?.invoke()
            }
        }
    }
}

private fun buildNavigationEntry(
    context: Context,
    navView: NavigationViewStruct,
    env: WuiEnvironment,
    registry: RenderRegistry
): NavigationEntry {
    val contentView = inflateAnyView(context, navView.contentPtr, env, registry).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    return NavigationEntry(contentView = contentView, barSpec = buildBarSpec(context, navView.bar, env, registry))
}

private fun buildNavigationScreen(
    context: Context,
    navView: NavigationViewStruct,
    env: WuiEnvironment,
    registry: RenderRegistry
): Pair<View, NavigationBarSpec> {
    val barSpec = buildBarSpec(context, navView.bar, env, registry)
    val contentView = inflateAnyView(context, navView.contentPtr, env, registry)
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    val barView = NavigationBarView(context)
    barView.bind(barSpec, showBack = false, onBack = null)
    container.addView(barView)
    container.addView(contentView.apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
    })
    return container to barSpec
}

private val navigationStackRenderer = WuiRenderer { context, node, env, registry ->
    val struct: NavigationStackStruct = NativeBindings.waterui_force_as_navigation_stack(node.rawPtr)
    val childEnv = env.clone()

    val stackView = AndroidNavigationStackView(
        context = context,
        transition = struct.transition,
        childEnv = childEnv,
        registry = registry
    )

    val callback = object {
        @Suppress("unused")
        fun onPush(navView: NavigationViewStruct) {
            stackView.push(navView)
        }

        @Suppress("unused")
        fun onPop() {
            stackView.pop()
        }
    }

    val controllerPtr = NativeBindings.waterui_navigation_controller_new(callback)
    NativeBindings.waterui_env_install_navigation_controller(childEnv.raw(), controllerPtr)

    val rootBar: NavigationBarSpec?
    val rootView: View
    if (struct.rootPtr != 0L && NativeBindings.waterui_view_id(struct.rootPtr).toTypeId() == navigationViewTypeId) {
        val rootNav = NativeBindings.waterui_force_as_navigation_view(struct.rootPtr)
        rootBar = buildBarSpec(context, rootNav.bar, childEnv, registry)
        rootView = inflateAnyView(context, rootNav.contentPtr, childEnv, registry)
    } else {
        rootBar = null
        rootView = if (struct.rootPtr != 0L) {
            inflateAnyView(context, struct.rootPtr, childEnv, registry)
        } else {
            FrameLayout(context)
        }
    }

    stackView.installRoot(rootView, rootBar)
    stackView.disposeWith {
        stackView.close()
        NativeBindings.waterui_drop_navigation_controller(controllerPtr)
        childEnv.close()
    }
    stackView
}

private val navigationViewRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_navigation_view(node.rawPtr)

    if (NativeBindings.waterui_env_has_navigation_controller(env.raw())) {
        inflateAnyView(context, struct.contentPtr, env, registry)
    } else {
        val (view, barSpec) = buildNavigationScreen(context, struct, env, registry)
        view.disposeWith { barSpec.close() }
        view
    }
}

private fun updateTabSelection(tabButtons: List<LinearLayout>, selectedIndex: Int) {
    tabButtons.forEachIndexed { index, tab ->
        tab.alpha = if (index == selectedIndex) 1f else 0.65f
        tab.setBackgroundColor(if (index == selectedIndex) Color.argb(18, 0, 0, 0) else Color.TRANSPARENT)
    }
}

private val tabsRenderer = WuiRenderer { context, node, env, registry ->
    val struct: TabsStruct = NativeBindings.waterui_force_as_tabs(node.rawPtr)
    val position = TabPosition.fromInt(struct.position)

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val tabBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
    }

    val contentContainer = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
    }

    var currentContent: View? = null
    var currentIndex = -1
    val selectionBinding = struct.selectionPtr.takeIf { it != 0L }?.let { WuiBinding.int(it, env) }
    val tabButtons = mutableListOf<LinearLayout>()

    fun showTab(index: Int) {
        if (index !in struct.tabs.indices) {
            return
        }
        currentIndex = index
        currentContent?.let { contentContainer.removeView(it) }
        val tab = struct.tabs[index]
        val nav = NativeBindings.waterui_tab_content(tab.contentPtr)
        val (screen, barSpec) = buildNavigationScreen(context, nav, env, registry)
        screen.disposeWith { barSpec.close() }
        currentContent = screen
        contentContainer.addView(screen)
        updateTabSelection(tabButtons, index)
        val selectedId = tab.id.toInt()
        if (selectionBinding?.current() != selectedId) {
            selectionBinding?.set(selectedId)
        }
    }

    struct.tabs.forEachIndexed { index, tab ->
        val label = inflateAnyView(context, tab.labelPtr, env, registry)
        val tabButton = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 8f))
            isClickable = true
            isFocusable = true
            addView(label)
            setOnClickListener { showTab(index) }
        }
        tabButtons += tabButton
        tabBar.addView(tabButton)
    }

    selectionBinding?.observe { selectedId ->
        val index = struct.tabs.indexOfFirst { it.id.toInt() == selectedId }
        if (index >= 0 && index != currentIndex) {
            showTab(index)
        } else if (index >= 0) {
            updateTabSelection(tabButtons, index)
        }
    }

    val initialIndex = struct.tabs.indexOfFirst {
        it.id.toInt() == (selectionBinding?.current() ?: struct.tabs.firstOrNull()?.id?.toInt() ?: 0)
    }.takeIf { it >= 0 } ?: 0

    if (position == TabPosition.TOP) {
        container.addView(tabBar)
        container.addView(contentContainer)
    } else {
        container.addView(contentContainer)
        container.addView(tabBar)
    }

    showTab(initialIndex)

    container.disposeWith {
        selectionBinding?.close()
    }
    container
}

private class SplitNavigationLayoutView(
    context: Context,
    private val sidebarView: View,
    private val placeholderView: View,
    private val selectionBinding: WuiBinding<Int>,
    private val detailPtr: Long,
    private val sidebarWidth: Float,
    private val env: WuiEnvironment,
    private val registry: RenderRegistry
) : FrameLayout(context) {
    private val barView = NavigationBarView(context)
    private var currentDetailBar: NavigationBarSpec? = null
    private var currentDetailView: View? = null
    private var currentSelectedId: Int = Int.MIN_VALUE

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { rebuild() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    fun close() {
        currentDetailBar?.close()
        selectionBinding.close()
    }

    fun refresh() {
        rebuild()
    }

    private fun isCompact(widthPx: Int): Boolean {
        val widthDp = widthPx / resources.displayMetrics.density
        return widthDp < 600f
    }

    private fun rebuild() {
        syncDetailState()
        removeAllViews()
        val compact = isCompact(width.coerceAtLeast(1))
        if (!compact) {
            buildRegular()
        } else if (currentDetailBar != null && currentDetailView != null) {
            buildCompactDetail()
        } else {
            detachFromParent(sidebarView)
            addView(sidebarView.apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            })
        }
    }

    private fun buildRegular() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        detachFromParent(sidebarView)
        row.addView(sidebarView.apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, sidebarWidth), LayoutParams.MATCH_PARENT)
        })
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 1f), LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.argb(32, 0, 0, 0))
        }
        row.addView(divider)
        val detailHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        if (currentDetailBar != null && currentDetailView != null) {
            barView.bind(currentDetailBar, showBack = false, onBack = null)
            detailHost.addView(barView)
            val detailView = currentDetailView!!
            detachFromParent(detailView)
            detailHost.addView(detailView.apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            })
        } else {
            detachFromParent(placeholderView)
            detailHost.addView(placeholderView.apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            })
        }
        row.addView(detailHost)
        addView(row)
    }

    private fun buildCompactDetail() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        barView.bind(currentDetailBar, showBack = true, onBack = { selectionBinding.set(0) })
        column.addView(barView)
        val detailView = currentDetailView!!
        detachFromParent(detailView)
        column.addView(detailView.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        })
        addView(column)
    }

    private fun syncDetailState() {
        val selectedId = selectionBinding.current()
        if (selectedId == currentSelectedId) {
            return
        }
        currentSelectedId = selectedId
        currentDetailBar?.close()
        currentDetailBar = null
        currentDetailView = null
        if (selectedId == 0) {
            return
        }
        val nav = NativeBindings.waterui_split_navigation_detail_content(detailPtr, selectedId)
        val (screen, barSpec) = buildNavigationScreen(context, nav, env, registry)
        screen.disposeWith { barSpec.close() }
        currentDetailBar = barSpec
        currentDetailView = screen
    }
}

private val splitNavigationContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct: SplitNavigationContainerStruct =
        NativeBindings.waterui_force_as_split_navigation_container(node.rawPtr)

    val sidebar = inflateAnyView(context, struct.sidebarPtr, env, registry)
    val placeholder = inflateAnyView(context, struct.placeholderPtr, env, registry)
    val selection = WuiBinding.int(struct.selectionPtr, env)

    val container = SplitNavigationLayoutView(
        context = context,
        sidebarView = sidebar,
        placeholderView = placeholder,
        selectionBinding = selection,
        detailPtr = struct.detailPtr,
        sidebarWidth = struct.sidebarWidth,
        env = env,
        registry = registry
    )

    selection.observe {
        container.post { container.refresh() }
    }

    container.disposeWith {
        container.close()
        if (struct.detailPtr != 0L) {
            NativeBindings.waterui_drop_split_navigation_detail(struct.detailPtr)
        }
    }
    container
}

internal fun RegistryBuilder.registerWuiNavigationStack() {
    register({ navigationStackTypeId }, navigationStackRenderer)
}

internal fun RegistryBuilder.registerWuiNavigationView() {
    register({ navigationViewTypeId }, navigationViewRenderer)
}

internal fun RegistryBuilder.registerWuiTabs() {
    register({ tabsTypeId }, tabsRenderer)
}

internal fun RegistryBuilder.registerWuiSplitNavigationContainer() {
    register({ splitNavigationContainerTypeId }, splitNavigationContainerRenderer)
}
