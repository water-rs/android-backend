package dev.waterui.android.components

import android.annotation.SuppressLint
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
import androidx.annotation.Keep
import androidx.core.view.isEmpty
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.BarStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NavigationStackStruct
import dev.waterui.android.runtime.NavigationViewStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.SplitNavigationContainerStruct
import dev.waterui.android.runtime.TabPosition
import dev.waterui.android.runtime.TabsStruct
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiStyledStr
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeAndRemoveView
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.disposeWuiTree
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import java.io.Closeable

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
    val searchPrompt: WuiComputed<WuiStyledStr>?,
    val colorSignal: WuiComputed<ResolvedColorStruct>,
    val hiddenComputed: WuiComputed<Boolean>?,
    val displayMode: Int
) {
    fun close() {
        listOfNotNull(leadingView, titleView, trailingView).forEach { view ->
            val wasAttached = view.parent != null
            detachFromParent(view)
            if (!wasAttached) {
                view.disposeWuiTree()
            }
        }
        searchBinding?.close()
        searchPrompt?.close()
        colorSignal.close()
        hiddenComputed?.close()
    }
}

private data class NavigationEntry(
    val contentView: View,
    val barSpec: NavigationBarSpec?
)

private fun detachFromParent(view: View?) {
    val parent = view?.parent
    if (parent is ViewGroup) {
        parent.removeView(view)
    }
}

private fun applyNavBarColor(target: View, color: ResolvedColorStruct) =
    target.setBackgroundColor(color.toColorInt())

private fun barMinHeight(context: Context, displayMode: Int): Int =
    when (displayMode) {
        DISPLAY_MODE_LARGE -> 88f.dp(context).toInt()
        DISPLAY_MODE_INLINE -> 56f.dp(context).toInt()
        DISPLAY_MODE_AUTOMATIC -> 64f.dp(context).toInt()
        else -> error("unknown navigation display mode: $displayMode")
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

    val searchBinding = bar.search?.textPtr?.takeIf { it != 0L }?.let { WuiBinding.str(it) }
    val searchPrompt = bar.search?.promptPtr?.takeIf { it != 0L }?.let { promptPtr ->
        WuiComputed.styledString(promptPtr)
    }

    val colorSignal = if (bar.colorPtr == 0L) {
        ThemeBridge.surface(env)
    } else {
        WuiComputed.colorFromComputed(bar.colorPtr)
    }

    val hiddenComputed = bar.hiddenPtr.takeIf { it != 0L }?.let {
        WuiComputed.bool(it)
    }

    return NavigationBarSpec(
        titleView = titleView,
        leadingView = leadingView,
        trailingView = trailingView,
        searchBinding = searchBinding,
        searchPrompt = searchPrompt,
        colorSignal = colorSignal,
        hiddenComputed = hiddenComputed,
        displayMode = bar.displayMode
    )
}

@SuppressLint("ViewConstructor")
private class NavigationBarView(
    context: Context,
    private val env: WuiEnvironment
) : LinearLayout(context) {
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

    private var activeColorSignal: WuiComputed<ResolvedColorStruct>? = null
    private var activeHiddenComputed: WuiComputed<Boolean>? = null
    private var activeSearchBinding: WuiBinding<String>? = null
    private var activeSearchPrompt: WuiComputed<WuiStyledStr>? = null
    private var activePromptBinding: Closeable? = null
    private var generatedBackView: View? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(headerRow)
        addView(searchSlot)
        headerRow.addView(leadingSlot)
        headerRow.addView(titleSlot)
        headerRow.addView(trailingSlot)
        setPadding(
            16f.dp(context).toInt(),
            8f.dp(context).toInt(),
            16f.dp(context).toInt(),
            8f.dp(context).toInt()
        )
        disposeWith {
            clearActiveBindings()
            generatedBackView = null
        }
    }

    fun bind(spec: NavigationBarSpec?, showBack: Boolean, onBack: (() -> Unit)?) {
        clearActiveBindings()
        clearSlots()

        activeColorSignal = spec?.colorSignal
        activeHiddenComputed = spec?.hiddenComputed
        activeSearchBinding = spec?.searchBinding
        activeSearchPrompt = spec?.searchPrompt

        if (spec == null) {
            visibility = GONE
            return
        }

        visibility = VISIBLE
        minimumHeight = barMinHeight(context, spec.displayMode)

        if (showBack && onBack != null) {
            val back = TextView(context).apply {
                text = "←"
                contentDescription = context.getString(R.string.wui_navigation_back)
                textSize = 20f
                setPadding(
                    4f.dp(context).toInt(),
                    4f.dp(context).toInt(),
                    12f.dp(context).toInt(),
                    4f.dp(context).toInt()
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onBack() }
            }
            ThemeBridge.foreground(env).also { foreground ->
                foreground.observe { back.setTextColor(it.toColorInt()) }
                foreground.attachTo(back)
            }
            generatedBackView = back
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
                setSingleLine()
            }
            searchField.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    spec.searchBinding.set(s?.toString().orEmpty())
                }
            })
            spec.searchBinding.observe { value ->
                if (searchField.text.toString() == value) {
                    return@observe
                }
                searchField.setText(value)
                searchField.setSelection(value.length)
            }
            spec.searchPrompt?.observe { styled ->
                activePromptBinding?.close()
                activePromptBinding = styled.bind(env) { resolved -> searchField.hint = resolved }
            }
            searchSlot.addView(searchField)
        }

        spec.colorSignal.observe { color ->
            applyNavBarColor(this, color)
        }
        spec.hiddenComputed?.observe { hidden ->
            visibility = if (hidden) GONE else VISIBLE
        }
    }

    fun close() {
        clearActiveBindings()
        clearSlots()
    }

    private fun clearSlots() {
        generatedBackView?.let { back ->
            detachFromParent(back)
            back.disposeWuiTree()
        }
        generatedBackView = null
        leadingSlot.removeAllViews()
        titleSlot.removeAllViews()
        trailingSlot.removeAllViews()
        searchSlot.removeAllViews()
    }

    private fun clearActiveBindings() {
        activeColorSignal?.clearObserver()
        activeHiddenComputed?.clearObserver()
        activeSearchBinding?.clearObserver()
        activeSearchPrompt?.clearObserver()
        activePromptBinding?.close()
        activePromptBinding = null
        activeColorSignal = null
        activeHiddenComputed = null
        activeSearchBinding = null
        activeSearchPrompt = null
    }
}

@android.annotation.SuppressLint("ViewConstructor")
private class AndroidNavigationStackView(
    context: Context,
    private val transition: Int,
    private val childEnv: WuiEnvironment,
    private val registry: RenderRegistry
) : LinearLayout(context) {
    private val barView = NavigationBarView(context, childEnv)
    private val contentContainer = FrameLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
    }
    private val entries = mutableListOf<NavigationEntry>()
    private val exitingEntries = mutableListOf<NavigationEntry>()

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
        check(entries.isEmpty() && contentContainer.isEmpty()) {
            "navigation root was already installed"
        }
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

        entry.contentView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        contentContainer.addView(entry.contentView)
        entries += entry
        updateChrome()
        animateTransition(previous, entry.contentView, isPush = true) {
            previous?.visibility = View.GONE
        }
    }

    fun pop() {
        if (entries.size <= 1) {
            return
        }

        val current = entries.removeAt(entries.lastIndex)
        exitingEntries += current
        val previous = entries.last()
        previous.contentView.visibility = View.VISIBLE
        updateChrome()
        animateTransition(previous.contentView, current.contentView, isPush = false) {
            contentContainer.disposeAndRemoveView(current.contentView)
            current.barSpec?.close()
            exitingEntries.remove(current)
        }
    }

    fun close() {
        (entries + exitingEntries).forEach { entry ->
            entry.contentView.animate().setListener(null)
            entry.contentView.animate().cancel()
        }
        (entries + exitingEntries).forEach { it.barSpec?.close() }
        entries.clear()
        exitingEntries.clear()
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
        if (transition == 0 && !contentContainer.isLaidOut) {
            onEnd?.invoke()
            return
        }
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
            0 -> {
                val width = contentContainer.width.toFloat()
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
            else -> error("unknown navigation transition: $transition")
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
    val barView = NavigationBarView(context, env)
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
        @Keep
        fun onPush(navView: NavigationViewStruct) {
            stackView.push(navView)
        }

        @Keep
        fun onPop() {
            stackView.pop()
        }
    }

    NativeBindings.waterui_env_install_navigation_controller(childEnv.raw(), callback)

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

private fun updateTabSelection(
    tabButtons: List<LinearLayout>,
    selectedIndex: Int,
    selectedColor: Int
) {
    tabButtons.forEachIndexed { index, tab ->
        tab.alpha = if (index == selectedIndex) 1f else 0.65f
        tab.setBackgroundColor(if (index == selectedIndex) selectedColor else Color.TRANSPARENT)
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
        val padding = 8f.dp(context).toInt()
        setPadding(padding, padding, padding, padding)
    }

    val contentContainer = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
    }

    var currentIndex = -1
    var selectedTabColor = Color.TRANSPARENT
    val selectionBinding = struct.selectionPtr.takeIf { it != 0L }?.let { WuiBinding.id(it) }
    val tabButtons = mutableListOf<LinearLayout>()
    val tabScreens = struct.tabs.map { tab ->
        val nav = NativeBindings.waterui_tab_content(tab.contentPtr, env.raw())
        val (screen, barSpec) = buildNavigationScreen(context, nav, env, registry)
        screen.visibility = View.GONE
        screen.disposeWith { barSpec.close() }
        contentContainer.addView(
            screen,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        screen
    }
    ThemeBridge.surfaceVariant(env).also { computed ->
        computed.observe { color ->
            selectedTabColor = color.toColorInt()
            if (currentIndex >= 0) {
                updateTabSelection(tabButtons, currentIndex, selectedTabColor)
            }
        }
        computed.attachTo(tabBar)
    }

    fun showTab(index: Int) {
        require(index in struct.tabs.indices) { "tab index $index is outside ${struct.tabs.indices}" }
        if (index == currentIndex) {
            updateTabSelection(tabButtons, index, selectedTabColor)
            return
        }
        if (currentIndex >= 0) {
            tabScreens[currentIndex].visibility = View.GONE
        }
        currentIndex = index
        val tab = struct.tabs[index]
        tabScreens[index].visibility = View.VISIBLE
        updateTabSelection(tabButtons, index, selectedTabColor)
        val selectedId = tab.id.toInt()
        selectionBinding?.set(selectedId)
    }

    struct.tabs.forEachIndexed { index, tab ->
        val label = inflateAnyView(context, tab.labelPtr, env, registry)
        val tabButton = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(
                12f.dp(context).toInt(),
                8f.dp(context).toInt(),
                12f.dp(context).toInt(),
                8f.dp(context).toInt()
            )
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
        require(index >= 0) { "selected tab id $selectedId is not present" }
        if (index != currentIndex) {
            showTab(index)
        } else {
            updateTabSelection(tabButtons, index, selectedTabColor)
        }
    }

    if (position == TabPosition.TOP) {
        container.addView(tabBar)
        container.addView(contentContainer)
    } else {
        container.addView(contentContainer)
        container.addView(tabBar)
    }

    if (selectionBinding == null && struct.tabs.isNotEmpty()) {
        showTab(0)
    }

    container.disposeWith {
        selectionBinding?.close()
        struct.tabs.forEach { tab -> NativeBindings.waterui_drop_tab_content(tab.contentPtr) }
    }
    container
}

@android.annotation.SuppressLint("ViewConstructor")
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
    private val barView = NavigationBarView(context, env)
    private val divider = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1f.dp(context).toInt(), LayoutParams.MATCH_PARENT)
    }
    private val dividerColor = ThemeBridge.border(env)
    private var currentDetailBar: NavigationBarSpec? = null
    private var currentDetailView: View? = null
    private var currentSelectedId: Int = Int.MIN_VALUE
    private var compactLayout: Boolean? = null

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dividerColor.observe { divider.setBackgroundColor(it.toColorInt()) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rebuild(force = isEmpty())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    fun close() {
        currentDetailBar?.close()
        currentDetailBar = null
        listOfNotNull(sidebarView, placeholderView, currentDetailView).forEach { view ->
            val wasAttached = view.parent != null
            detachFromParent(view)
            if (!wasAttached) {
                view.disposeWuiTree()
            }
        }
        currentDetailView = null
        detachFromParent(barView)
        detachFromParent(divider)
        removeAllViews()
        dividerColor.close()
        selectionBinding.close()
    }

    fun refresh(selectedId: Int) {
        rebuild(selectedId)
    }

    private fun isCompact(widthPx: Int): Boolean {
        val widthDp = widthPx / resources.displayMetrics.density
        return widthDp < 600f
    }

    private fun rebuild(selectedId: Int = currentSelectedId, force: Boolean = false) {
        val selectionChanged = syncDetailState(selectedId)
        val compact = isCompact(width.coerceAtLeast(1))
        if (!force && !selectionChanged && compactLayout == compact) {
            return
        }
        compactLayout = compact
        clearLayout()
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
            layoutParams = LinearLayout.LayoutParams(
                sidebarWidth.dp(context).toInt(),
                LayoutParams.MATCH_PARENT
            )
        })
        detachFromParent(divider)
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

    private fun syncDetailState(selectedId: Int): Boolean {
        if (selectedId == currentSelectedId) {
            return false
        }
        currentSelectedId = selectedId
        barView.close()
        currentDetailBar?.close()
        currentDetailBar = null
        currentDetailView?.let { view ->
            detachFromParent(view)
            view.disposeWuiTree()
        }
        currentDetailView = null
        if (selectedId == 0) {
            return true
        }
        val nav = NativeBindings.waterui_split_navigation_detail_content(
            detailPtr,
            selectedId,
            env.raw()
        )
        val entry = buildNavigationEntry(context, nav, env, registry)
        currentDetailBar = entry.barSpec
        currentDetailView = entry.contentView
        return true
    }

    private fun clearLayout() {
        detachFromParent(sidebarView)
        detachFromParent(placeholderView)
        detachFromParent(barView)
        detachFromParent(divider)
        detachFromParent(currentDetailView)
        removeAllViews()
    }
}

private val splitNavigationContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct: SplitNavigationContainerStruct =
        NativeBindings.waterui_force_as_split_navigation_container(node.rawPtr)

    val sidebar = inflateAnyView(context, struct.sidebarPtr, env, registry)
    val placeholder = inflateAnyView(context, struct.placeholderPtr, env, registry)
    val selection = WuiBinding.id(struct.selectionPtr)

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

    selection.observe { selectedId ->
        container.refresh(selectedId)
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
