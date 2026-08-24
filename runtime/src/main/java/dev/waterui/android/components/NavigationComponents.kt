package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.annotation.Keep
import androidx.appcompat.content.res.AppCompatResources
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import androidx.core.view.size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import androidx.transition.Transition
import androidx.transition.TransitionManager
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import androidx.window.layout.WindowMetricsCalculator
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.widget.Toolbar
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView as MaterialNavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.R as MaterialR
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.BarStruct
import dev.waterui.android.runtime.ColorSlot
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NavigationStackStruct
import dev.waterui.android.runtime.NavigationViewStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ReactiveColorSignal
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.TabStruct
import dev.waterui.android.runtime.SplitNavigationContainerStruct
import dev.waterui.android.runtime.TabsStruct
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.applyRemainingInsets
import dev.waterui.android.runtime.WuiSafeAreaManaging
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
import dev.waterui.android.runtime.requireActivity
import dev.waterui.android.runtime.requireOnBackPressedDispatcherOwner
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

/// The narrowest a detail pane is still worth showing beside a sidebar.
///
/// Material's list-detail guidance keeps a detail pane at least this wide, and
/// `SlidingPaneLayout` reads it as the threshold for laying the two panes out
/// side by side: with the example's 280dp sidebar the pair needs a window past
/// the expanded breakpoint, so a phone collapses to one pane and a tablet does
/// not.
private const val DETAIL_PANE_MIN_WIDTH_DP = 360f

private const val DISPLAY_MODE_AUTOMATIC = 0
private const val DISPLAY_MODE_INLINE = 1
private const val DISPLAY_MODE_MEDIUM = 2
private const val DISPLAY_MODE_LARGE = 3
private const val TOOLBAR_PRINCIPAL = 0
private const val TOOLBAR_PRIMARY_ACTION = 1
private const val TOOLBAR_SECONDARY_ACTION = 2
private const val TOOLBAR_CONFIRMATION = 3
private const val TOOLBAR_CANCELLATION = 4
private const val TOOLBAR_BOTTOM_BAR = 5
private const val TOOLBAR_STATUS = 6
private const val TOOLBAR_TOP_BAR_LEADING = 7
private const val TOOLBAR_TOP_BAR_TRAILING = 8
private const val NAVIGATION_RESTORATION_ID_KEY = "dev.waterui.navigation.destination.id"

private fun navigationRestorationId(depth: Int): String {
    require(depth >= 0) { "navigation restoration depth must be non-negative" }
    return "dev.waterui.navigation.destination.$depth"
}

private data class NavigationToolbarItemSpec(
    val placement: Int,
    val view: View
)

private data class NavigationBarSpec(
    val titleView: View?,
    val subtitleView: View?,
    val toolbarItems: List<NavigationToolbarItemSpec>,
    val searchBinding: WuiBinding<String>?,
    val searchPrompt: WuiComputed<WuiStyledStr>?,
    val colorSignal: WuiComputed<ResolvedColorStruct>,
    val hiddenComputed: WuiComputed<Boolean>?,
    val displayMode: Int
) {
    fun close() {
        (listOfNotNull(titleView, subtitleView) + toolbarItems.map { it.view }).forEach { view ->
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
    val barSpec: NavigationBarSpec?,
    val state: NavigationDestinationState?,
    val restorationId: String,
    val fragment: NavigationDestinationFragment,
    /// What this destination declared, mirroring `WuiNavigationTransitionKind`.
    /// `TRANSITION_INHERIT` means it declared nothing and the stack's stands.
    val transitionKind: Int = TRANSITION_INHERIT,
    /// The zoom source id this destination named, if it named one.
    val transitionSourceId: Int = 0,
    var isActive: Boolean = false
)

/// Mirrors `WuiNavigationTransitionKind`.
private const val TRANSITION_AUTOMATIC = 0
private const val TRANSITION_FADE = 1
private const val TRANSITION_ZOOM = 2
private const val TRANSITION_NONE = 3
private const val TRANSITION_CUSTOM = 4
private const val TRANSITION_INHERIT = 5

private data class PendingNavigationTransaction(
    val id: Long,
    val retainedPrefix: Int,
    val removed: Int,
    val inserted: Array<NavigationViewStruct>
)

internal class NavigationDestinationFragment : Fragment() {
    private var destinationView: View? = null
    private var host: FrameLayout? = null

    fun bind(view: View) {
        check(destinationView == null || destinationView === view) {
            "navigation fragment cannot be rebound to a different destination"
        }
        destinationView = view
        host?.let { attachDestination(it, view) }
    }

    fun isBound(): Boolean = destinationView != null

    fun restorationId(): String = requireNotNull(
        requireArguments().getString(NAVIGATION_RESTORATION_ID_KEY)
    ) { "navigation fragment is missing its restoration identity" }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        val host = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        this.host = host
        destinationView?.let { attachDestination(host, it) }
        return host
    }

    override fun onDestroyView() {
        host = null
        super.onDestroyView()
    }

    private fun attachDestination(host: FrameLayout, view: View) {
        detachFromParent(view)
        host.removeAllViews()
        host.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    companion object {
        fun create(restorationId: String, view: View) = NavigationDestinationFragment().apply {
            arguments = android.os.Bundle().apply {
                putString(NAVIGATION_RESTORATION_ID_KEY, restorationId)
            }
            bind(view)
        }
    }
}

private class NavigationDestinationState(
    popEnabledPtr: Long,
    private val popAttemptedPtr: Long,
    private val appearPtr: Long,
    private val disappearPtr: Long,
    private val popPtr: Long,
    private val env: WuiEnvironment
) : Closeable {
    private val popEnabled = WuiComputed.bool(popEnabledPtr)
    private var enabled = true

    init {
        popEnabled.observe { enabled = it }
    }

    fun attemptPop(): Boolean {
        if (popAttemptedPtr != 0L) {
            NativeBindings.waterui_call_action(popAttemptedPtr, env.raw())
        }
        return enabled
    }

    fun appeared() = call(appearPtr)

    fun disappeared() = call(disappearPtr)

    fun popped() = call(popPtr)

    override fun close() {
        popEnabled.close()
        listOf(popAttemptedPtr, appearPtr, disappearPtr, popPtr)
            .filter { it != 0L }
            .forEach(NativeBindings::waterui_drop_action)
    }

    private fun call(actionPtr: Long) {
        if (actionPtr != 0L) {
            NativeBindings.waterui_call_action(actionPtr, env.raw())
        }
    }
}

private fun detachFromParent(view: View?) {
    val parent = view?.parent
    if (parent is ViewGroup) {
        parent.removeView(view)
    }
}

private fun applyNavBarColor(target: View, color: ResolvedColorStruct) =
    target.setBackgroundColor(color.toColorInt())

private fun requiredThemeDimension(context: Context, attribute: Int, name: String): Int {
    val values = context.obtainStyledAttributes(intArrayOf(attribute))
    return try {
        values.getDimensionPixelSize(0, 0).also { value ->
            check(value > 0) { "Android theme must define $name" }
        }
    } finally {
        values.recycle()
    }
}

private fun navigationCoordinator(
    context: Context,
    barView: NavigationBarView,
    contentView: View
) = CoordinatorLayout(context).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f
    )
    addView(
        barView,
        CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    addView(
        contentView,
        CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            behavior = AppBarLayout.ScrollingViewBehavior()
        }
    )
}

private fun buildBarSpec(
    context: Context,
    bar: BarStruct,
    env: WuiEnvironment,
    registry: RenderRegistry
): NavigationBarSpec {
    val titleView = if (bar.titlePtr != 0L) inflateAnyView(context, bar.titlePtr, env, registry) else null
    val subtitleView =
        if (bar.subtitlePtr != 0L) inflateAnyView(context, bar.subtitlePtr, env, registry) else null
    val toolbarItems = bar.toolbar.map { item ->
        check(item.placement in TOOLBAR_PRINCIPAL..TOOLBAR_TOP_BAR_TRAILING) {
            "unknown navigation toolbar placement: ${item.placement}"
        }
        NavigationToolbarItemSpec(
            placement = item.placement,
            view = inflateAnyView(context, item.contentPtr, env, registry)
        )
    }

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
        subtitleView = subtitleView,
        toolbarItems = toolbarItems,
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
) : AppBarLayout(context) {
    private val compactToolbarHeight = requiredThemeDimension(
        context,
        AppCompatR.attr.actionBarSize,
        "actionBarSize"
    )
    private val mediumToolbarHeight = requiredThemeDimension(
        context,
        MaterialR.attr.collapsingToolbarLayoutMediumSize,
        "collapsingToolbarLayoutMediumSize"
    )
    private val largeToolbarHeight = requiredThemeDimension(
        context,
        MaterialR.attr.collapsingToolbarLayoutLargeSize,
        "collapsingToolbarLayoutLargeSize"
    )
    private val toolbar = MaterialToolbar(context).apply {
        layoutParams = CollapsingToolbarLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            compactToolbarHeight
        ).apply {
            collapseMode = CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN
        }
    }
    private val collapsingToolbar = CollapsingToolbarLayout(
        context,
        null,
        MaterialR.attr.collapsingToolbarLayoutLargeStyle
    ).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, compactToolbarHeight)
        addView(toolbar)
    }
    val bottomToolbar = MaterialToolbar(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        visibility = GONE
    }
    private val searchBar = SearchBar(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        visibility = GONE
    }
    private val searchView = SearchView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        visibility = GONE
    }

    private var activeColorSignal: WuiComputed<ResolvedColorStruct>? = null
    private var activeHiddenComputed: WuiComputed<Boolean>? = null
    private var activeSearchBinding: WuiBinding<String>? = null
    private var activeSearchPrompt: WuiComputed<WuiStyledStr>? = null
    private var activeForegroundSignal: WuiComputed<ResolvedColorStruct>? = null
    private var activePromptBinding: Closeable? = null
    private var titleView: View? = null
    private var subtitleView: View? = null
    private val toolbarViews = mutableListOf<View>()
    private val semanticTextWatchers = mutableListOf<Pair<TextView, TextWatcher>>()
    private var syncingSearch = false
    private val searchWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (!syncingSearch) {
                activeSearchBinding?.set(s?.toString().orEmpty())
            }
        }
    }

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(collapsingToolbar)
        addView(searchBar)
        addView(searchView)
        searchView.setupWithSearchBar(searchBar)
        searchView.editText.addTextChangedListener(searchWatcher)
        disposeWith {
            clearActiveBindings()
            clearSlots()
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
        val titleHeight = when (spec.displayMode) {
            DISPLAY_MODE_AUTOMATIC -> if (showBack) compactToolbarHeight else largeToolbarHeight
            DISPLAY_MODE_INLINE -> compactToolbarHeight
            DISPLAY_MODE_MEDIUM -> mediumToolbarHeight
            DISPLAY_MODE_LARGE -> largeToolbarHeight
            else -> error("unknown navigation display mode: ${spec.displayMode}")
        }
        configureTitleMode(titleHeight)

        if (showBack && onBack != null) {
            toolbar.navigationIcon = AppCompatResources.getDrawable(
                context,
                AppCompatR.drawable.abc_ic_ab_back_material
            )
            toolbar.navigationContentDescription = context.getString(R.string.wui_navigation_back)
            toolbar.setNavigationOnClickListener { onBack() }
        } else {
            toolbar.navigationIcon = null
            toolbar.navigationContentDescription = null
            toolbar.setNavigationOnClickListener(null)
        }

        detachFromParent(spec.titleView)
        detachFromParent(spec.subtitleView)
        spec.toolbarItems.forEach { detachFromParent(it.view) }

        titleView = spec.titleView
        subtitleView = spec.subtitleView
        val principalItem = spec.toolbarItems.firstOrNull { it.placement == TOOLBAR_PRINCIPAL }
        if (principalItem == null) {
            collapsingToolbar.isTitleEnabled = true
            bindSemanticText(spec.titleView, collapsingToolbar::setTitle)
            bindSemanticText(spec.subtitleView, collapsingToolbar::setSubtitle)
        } else {
            collapsingToolbar.isTitleEnabled = false
            collapsingToolbar.title = null
            collapsingToolbar.subtitle = null
        }
        principalItem?.view?.let { view ->
            toolbar.addView(view, Toolbar.LayoutParams(
                Toolbar.LayoutParams.WRAP_CONTENT,
                Toolbar.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
            toolbarViews += view
        }

        spec.toolbarItems.filter { item ->
            item.placement == TOOLBAR_CANCELLATION || item.placement == TOOLBAR_TOP_BAR_LEADING
        }.forEach { addToolbarView(toolbar, it.view, Gravity.START) }
        spec.toolbarItems.filter { item ->
            item.placement == TOOLBAR_PRIMARY_ACTION ||
                item.placement == TOOLBAR_SECONDARY_ACTION ||
                item.placement == TOOLBAR_CONFIRMATION ||
                item.placement == TOOLBAR_TOP_BAR_TRAILING
        }.forEach { addToolbarView(toolbar, it.view, Gravity.END) }
        spec.toolbarItems.filter { item ->
            item.placement == TOOLBAR_BOTTOM_BAR || item.placement == TOOLBAR_STATUS
        }.forEach { addToolbarView(bottomToolbar, it.view, Gravity.CENTER) }
        bottomToolbar.visibility = if (bottomToolbar.isEmpty()) GONE else VISIBLE

        if (spec.searchBinding != null) {
            searchBar.visibility = VISIBLE
            spec.searchBinding.observe { value ->
                if (searchBar.text.toString() == value) {
                    return@observe
                }
                syncingSearch = true
                searchBar.setText(value)
                searchView.setText(value)
                syncingSearch = false
            }
            spec.searchPrompt?.observe { styled ->
                activePromptBinding?.close()
                activePromptBinding = styled.bind(env) { resolved ->
                    searchBar.hint = resolved
                    searchView.hint = resolved
                }
            }
        } else {
            searchBar.visibility = GONE
            searchBar.setText("")
            searchBar.hint = null
            searchView.setText("")
            searchView.hint = null
        }

        spec.colorSignal.observe { color ->
            applyNavBarColor(this, color)
            applyNavBarColor(collapsingToolbar, color)
            applyNavBarColor(toolbar, color)
            applyNavBarColor(bottomToolbar, color)
        }
        activeForegroundSignal = ThemeBridge.foreground(env).also { foreground ->
            foreground.observe { color ->
                val colorInt = color.toColorInt()
                toolbar.setNavigationIconTint(colorInt)
                collapsingToolbar.setCollapsedTitleTextColor(colorInt)
                collapsingToolbar.setExpandedTitleColor(colorInt)
                collapsingToolbar.setCollapsedSubtitleTextColor(colorInt)
                collapsingToolbar.setExpandedSubtitleColor(colorInt)
            }
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
        semanticTextWatchers.forEach { (view, watcher) ->
            view.removeTextChangedListener(watcher)
        }
        semanticTextWatchers.clear()
        toolbarViews.forEach(::detachFromParent)
        toolbarViews.clear()
        listOfNotNull(titleView, subtitleView).forEach(::detachFromParent)
        titleView = null
        subtitleView = null
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
        collapsingToolbar.title = null
        collapsingToolbar.subtitle = null
        collapsingToolbar.isTitleEnabled = false
        searchBar.visibility = GONE
        bottomToolbar.visibility = GONE
    }

    // A bar taller than the action bar collapses on scroll; one that is
    // exactly the action bar's height has nothing to collapse into.
    private fun configureTitleMode(height: Int) {
        val collapses = height > compactToolbarHeight
        val params = collapsingToolbar.layoutParams as LayoutParams
        params.height = height
        params.scrollFlags = if (collapses) {
            LayoutParams.SCROLL_FLAG_SCROLL or
                LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED or
                LayoutParams.SCROLL_FLAG_SNAP
        } else {
            0
        }
        collapsingToolbar.layoutParams = params
    }

    private fun bindSemanticText(view: View?, update: (CharSequence?) -> Unit) {
        if (view == null) {
            update(null)
            return
        }
        val textView = view.semanticTextView()
        if (textView == null) {
            check(view.rendersNothing()) {
                "semantic navigation title and subtitle must render a native TextView, " +
                    "but ${view.javaClass.name} contains none"
            }
            update(null)
            return
        }
        update(textView.text.toPlainBarText())
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = update(text.toPlainBarText())

            override fun afterTextChanged(text: Editable?) = Unit
        }
        textView.addTextChangedListener(watcher)
        semanticTextWatchers += textView to watcher
    }

    /// The characters of a semantic text, without its view-level styling.
    ///
    /// The mirrored `TextView` styles itself for the page body: its text is a
    /// `Spanned` carrying absolute size and line-height spans for the body
    /// font. `StaticLayout` honors those spans over its paint, so mirroring
    /// them into the collapsing toolbar silently pins the large expanded
    /// title to the body size — every expanded-title size API then appears
    /// dead. The bar owns title typography; only the characters cross over.
    private fun CharSequence?.toPlainBarText(): String? = this?.toString()

    /// The `TextView` at or below this view.
    ///
    /// A semantic title reaches the bar wrapped in whatever the view tree put
    /// around it: an environment scope and each metadata modifier add a group
    /// of their own, so the text is a descendant rather than the root the
    /// renderer handed back. The bar mirrors that view's text into the
    /// collapsing toolbar, so it needs the `TextView` itself.
    private fun View.semanticTextView(): TextView? {
        if (this is TextView) {
            return this
        }
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            group.getChildAt(index).semanticTextView()?.let { return it }
        }
        return null
    }

    /// Whether this view tree carries nothing the bar could show.
    ///
    /// A bar's title and subtitle are always views, so a page that declares no
    /// subtitle still sends one: WaterUI's empty view, inside whatever scopes
    /// the tree wrapped around it. That is "no subtitle", not a subtitle the
    /// bar failed to read, and the two have to be told apart because only the
    /// second one is a bug.
    private fun View.rendersNothing(): Boolean {
        if (this is WuiEmptyView) {
            return true
        }
        val group = this as? ViewGroup ?: return false
        if (group.childCount == 0) {
            return false
        }
        for (index in 0 until group.childCount) {
            if (!group.getChildAt(index).rendersNothing()) {
                return false
            }
        }
        return true
    }

    private fun addToolbarView(target: Toolbar, view: View, gravity: Int) {
        target.addView(view, Toolbar.LayoutParams(
            Toolbar.LayoutParams.WRAP_CONTENT,
            Toolbar.LayoutParams.WRAP_CONTENT,
            gravity or Gravity.CENTER_VERTICAL
        ))
        toolbarViews += view
    }

    private fun clearActiveBindings() {
        activeColorSignal?.clearObserver()
        activeHiddenComputed?.clearObserver()
        activeSearchBinding?.clearObserver()
        activeSearchPrompt?.clearObserver()
        activeForegroundSignal?.close()
        activePromptBinding?.close()
        activePromptBinding = null
        activeColorSignal = null
        activeHiddenComputed = null
        activeSearchBinding = null
        activeSearchPrompt = null
        activeForegroundSignal = null
    }
}

@android.annotation.SuppressLint("ViewConstructor")
private class AndroidNavigationStackView(
    context: Context,
    private val stackTransition: Int,
    private val stackTransitionSourceId: Int,
    private val childEnv: WuiEnvironment,
    private val registry: RenderRegistry
) : LinearLayout(context), WuiSafeAreaManaging {
    private val barView = NavigationBarView(context, childEnv)
    private val contentContainer = FragmentContainerView(context).apply {
        id = View.generateViewId()
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
    }
    private val entries = mutableListOf<NavigationEntry>()
    private val pendingRootTransactions = mutableListOf<PendingNavigationTransaction>()
    private var rootInstalled = false
    private var fragmentManager: FragmentManager? = null
    private val pendingRemovedEntries = mutableListOf<NavigationEntry>()
    private var predictiveCurrent: View? = null
    private var predictivePrevious: View? = null
    private var predictiveAllowed = false
    private var backCallbackInstalled = false
    private var activeTransactionId: Long? = null
    private var activeTransition: Transition? = null
    private var safeArea = Insets.NONE
    private var appliedBarVisible: Boolean? = null
    /// Set while a fragment sync is waiting for the host to start again, and
    /// true when the sync that was deferred was the initial one.
    private var pendingSyncIsInitial: Boolean? = null
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (entries.size <= 1) {
                return
            }
            predictiveAllowed = entries.last().state?.attemptPop() ?: true
            if (!predictiveAllowed) {
                return
            }
            val manager = checkNotNull(fragmentManager) {
                "predictive navigation started before FragmentManager installation"
            }
            check(!manager.isStateSaved) {
                "predictive navigation started after FragmentManager saved its state"
            }
            val current = entries.last().fragment
            val previous = entries[entries.lastIndex - 1].fragment
            manager.beginTransaction()
                .setReorderingAllowed(true)
                .show(previous)
                .commitNow()
            predictiveCurrent = current.requireView()
            predictivePrevious = previous.requireView()
            applyPredictiveProgress(backEvent.progress)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (!predictiveAllowed) {
                return
            }
            applyPredictiveProgress(backEvent.progress)
        }

        override fun handleOnBackCancelled() {
            cancelPredictivePop()
        }

        override fun handleOnBackPressed() {
            if (entries.size <= 1) {
                return
            }
            if (predictiveCurrent == null && !(entries.last().state?.attemptPop() ?: true)) {
                return
            }
            if (!predictiveAllowed && predictiveCurrent != null) {
                return
            }
            commitNativePop()
        }
    }

    init {
        orientation = VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(navigationCoordinator(context, barView, contentContainer))
        addView(barView.bottomToolbar)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (backCallbackInstalled) {
            return
        }
        val dispatcherOwner = context.requireOnBackPressedDispatcherOwner()
        val lifecycleOwner = checkNotNull(findViewTreeLifecycleOwner()) {
            "WaterUI navigation requires a ViewTreeLifecycleOwner"
        }
        val activity = context.requireActivity() as? FragmentActivity
            ?: error("WaterUI navigation requires a FragmentActivity host")
        fragmentManager = activity.supportFragmentManager
        syncFragments(initial = true)
        activeTransactionId?.let { id ->
            pendingRemovedEntries.forEach { it.state?.popped() }
            disposeEntries(pendingRemovedEntries.toList())
            pendingRemovedEntries.clear()
            markAppeared(entries.last())
            completeTransaction(id)
        }
        dispatcherOwner.onBackPressedDispatcher.addCallback(lifecycleOwner, backCallback)
        backCallbackInstalled = true
        updateBackEnabled()
    }

    /// The app bar takes the top edge, so its background reaches under the
    /// status bar; the destination content gets the other three. With no bar on
    /// screen there is nothing to reach up there, and the content takes all four.
    override fun applySafeArea(insets: Insets) {
        safeArea = insets
        distributeSafeArea()
    }

    private fun distributeSafeArea() {
        val insets = safeArea
        val barVisible = barView.visibility == VISIBLE
        val remaining = if (barVisible) {
            barView.setPadding(insets.left, insets.top, insets.right, 0)
            Insets.of(insets.left, 0, insets.right, insets.bottom)
        } else {
            barView.setPadding(0, 0, 0, 0)
            insets
        }
        entries.forEach { entry -> applyRemainingInsets(entry.contentView, remaining) }
        appliedBarVisible = barVisible
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The bar hides and reappears reactively, which moves the top inset
        // between it and the content.
        if (appliedBarVisible != (barView.visibility == VISIBLE)) {
            distributeSafeArea()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun installRoot(
        rootView: View,
        rootBar: NavigationBarSpec?,
        rootState: NavigationDestinationState?
    ) {
        check(entries.isEmpty() && contentContainer.isEmpty()) {
            "navigation root was already installed"
        }
        entries += NavigationEntry(
            rootView,
            rootBar,
            rootState,
            navigationRestorationId(0),
            NavigationDestinationFragment.create(navigationRestorationId(0), rootView)
        )
        rootInstalled = true
        distributeSafeArea()
        rootView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        if (isAttachedToWindow) {
            syncFragments(initial = true)
        }
        if (pendingRootTransactions.isEmpty()) {
            updateChrome()
            markAppeared(entries.last())
        } else {
            val pending = pendingRootTransactions.toList()
            pendingRootTransactions.clear()
            pending.forEach { transaction ->
                applyInstalled(transaction)
            }
        }
    }

    fun apply(
        id: Long,
        retainedPrefix: Int,
        removed: Int,
        inserted: Array<NavigationViewStruct>
    ) {
        val transaction = PendingNavigationTransaction(id, retainedPrefix, removed, inserted)
        if (!rootInstalled) {
            pendingRootTransactions += transaction
            return
        }
        applyInstalled(transaction)
    }

    private fun applyInstalled(transaction: PendingNavigationTransaction) {
        val id = transaction.id
        val retainedPrefix = transaction.retainedPrefix
        val removed = transaction.removed
        val inserted = transaction.inserted
        require(id > 0L) { "navigation transaction id must be positive" }
        require(retainedPrefix >= 0 && removed >= 0) {
            "navigation transaction prefix and removal count must be non-negative"
        }
        check(retainedPrefix + removed == entries.size - 1) {
            "navigation transaction must replace the current suffix"
        }
        settleActiveTransactionBeforeReplacement()
        entries.forEach { entry ->
            entry.contentView.animate().setListener(null)
            entry.contentView.animate().cancel()
            entry.contentView.alpha = 1f
            entry.contentView.translationX = 0f
        }
        activeTransactionId = id

        val oldTop = entries.last().contentView
        markDisappeared(entries.last())
        val removedEntries = entries.subList(retainedPrefix + 1, entries.size).toList()
        entries.subList(retainedPrefix + 1, entries.size).clear()
        val insertedEntries = inserted.mapIndexed { index, navView ->
            val restorationId = navigationRestorationId(retainedPrefix + index + 1)
            buildNavigationEntry(context, navView, restorationId, childEnv, registry).also { entry ->
                entry.contentView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                entries += entry
            }
        }

        distributeSafeArea()
        val newTop = entries.last().contentView
        updateChrome()
        applyFragmentTransaction(
            id = id,
            removedEntries = removedEntries,
            insertedEntries = insertedEntries,
            oldTop = oldTop,
            newTop = newTop
        )
    }

    fun close() {
        entries.lastOrNull()?.let(::markDisappeared)
        activeTransactionId?.let(::cancelTransaction)
        TransitionManager.endTransitions(contentContainer)
        activeTransition = null
        entries.forEach { entry ->
            entry.contentView.animate().setListener(null)
            entry.contentView.animate().cancel()
        }
        fragmentManager?.let { manager ->
            // Destroy always follows `onSaveInstanceState`, so arriving here
            // with a saved state is the ordinary case — a rotation, or the
            // system reclaiming the activity — and not a broken one. The
            // manager goes away with the activity or is restored into the next,
            // and a transaction against a saved state is neither possible nor
            // needed. Asserting otherwise crashed the app on every relaunch.
            if (!manager.isStateSaved) {
                manager.beginTransaction().apply {
                    entries.forEach { entry ->
                        if (entry.fragment.isAdded) {
                            remove(entry.fragment)
                        }
                    }
                }.commitNow()
            }
        }
        entries.forEach {
            it.barSpec?.close()
            it.state?.close()
            it.contentView.disposeWuiTree()
        }
        entries.clear()
        fragmentManager = null
        pendingRemovedEntries.forEach { entry ->
            entry.barSpec?.close()
            entry.state?.close()
            entry.contentView.disposeWuiTree()
        }
        pendingRemovedEntries.clear()
        check(pendingRootTransactions.isEmpty()) {
            "navigation stack was disposed before its semantic root was installed"
        }
        backCallback.remove()
    }

    private fun updateChrome() {
        val active = entries.lastOrNull()
        barView.bind(
            spec = active?.barSpec,
            showBack = entries.size > 1,
            onBack = {
                if (entries.last().state?.attemptPop() != false) {
                    NativeBindings.waterui_navigation_request_pop(childEnv.raw(), 1)
                }
            }
        )
        updateBackEnabled()
    }

    private fun updateBackEnabled() {
        backCallback.isEnabled = entries.size > 1 && activeTransactionId == null
    }

    private fun completeTransaction(id: Long) {
        if (activeTransactionId != id) {
            return
        }
        NativeBindings.waterui_navigation_transition_completed(childEnv.raw(), id)
        activeTransactionId = null
        updateBackEnabled()
    }

    private fun cancelTransaction(id: Long) {
        if (activeTransactionId != id) {
            return
        }
        NativeBindings.waterui_navigation_transition_cancelled(childEnv.raw(), id)
        activeTransactionId = null
        activeTransition = null
        updateBackEnabled()
    }

    private fun settleActiveTransactionBeforeReplacement() {
        fragmentManager?.executePendingTransactions()
        activeTransactionId?.let(::cancelTransaction)
        TransitionManager.endTransitions(contentContainer)
        activeTransition = null
        pendingRemovedEntries.forEach { it.state?.popped() }
        disposeEntries(pendingRemovedEntries.toList())
        pendingRemovedEntries.clear()
    }

    private fun applyPredictiveProgress(progress: Float) {
        val current = predictiveCurrent ?: return
        val previous = predictivePrevious ?: return
        val distance = contentContainer.width.toFloat()
        current.translationX = distance * progress
        previous.translationX = -distance * 0.1f * (1f - progress)
        previous.alpha = 0.9f + 0.1f * progress
    }

    private fun cancelPredictivePop() {
        val current = predictiveCurrent
        val previous = predictivePrevious
        val previousFragment = entries.getOrNull(entries.lastIndex - 1)?.fragment
        predictiveCurrent = null
        predictivePrevious = null
        predictiveAllowed = false
        val duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
        current?.animate()?.translationX(0f)?.setDuration(duration)?.start()
        previous?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(duration)
            ?.withEndAction {
                val fragment = previousFragment ?: return@withEndAction
                if (entries.lastOrNull()?.fragment === fragment || !fragment.isAdded) {
                    return@withEndAction
                }
                val manager = checkNotNull(fragmentManager) {
                    "predictive navigation cancellation completed without FragmentManager"
                }
                check(!manager.isStateSaved) {
                    "predictive navigation cancellation completed after FragmentManager saved its state"
                }
                manager.beginTransaction()
                    .setReorderingAllowed(true)
                    .hide(fragment)
                    .commitNow()
            }
            ?.start()
    }

    private fun commitNativePop() {
        val current = entries.removeAt(entries.lastIndex)
        val previous = entries.last()
        markDisappeared(current)
        predictiveCurrent = null
        predictivePrevious = null
        predictiveAllowed = false
        updateChrome()
        current.fragment.requireView().animate().translationX(contentContainer.width.toFloat())
            .setDuration(resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
            .withEndAction {
                val manager = checkNotNull(fragmentManager) {
                    "predictive navigation pop completed before FragmentManager installation"
                }
                check(!manager.isStateSaved) {
                    "predictive navigation pop completed after FragmentManager saved its state"
                }
                manager.beginTransaction()
                    .setReorderingAllowed(true)
                    .remove(current.fragment)
                    .show(previous.fragment)
                    .commitNow()
                current.state?.popped()
                disposeEntries(listOf(current))
                markAppeared(previous)
                NativeBindings.waterui_navigation_complete_native_pop(childEnv.raw(), 1)
            }
            .start()
    }

    /// Re-runs [syncFragments] the next time the host starts.
    ///
    /// A saved state is a pause, not a failure: what the stack should show is
    /// held in `entries` either way, and the transaction that shows it can wait
    /// for a manager that will accept it.
    private fun deferSyncUntilStarted(initial: Boolean) {
        if (pendingSyncIsInitial != null) {
            pendingSyncIsInitial = pendingSyncIsInitial == true || initial
            return
        }
        pendingSyncIsInitial = initial
        val lifecycle = checkNotNull(findViewTreeLifecycleOwner()) {
            "WaterUI navigation requires a ViewTreeLifecycleOwner"
        }.lifecycle
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event != Lifecycle.Event.ON_START && event != Lifecycle.Event.ON_DESTROY) {
                    return
                }
                lifecycle.removeObserver(this)
                val deferred = pendingSyncIsInitial ?: return
                pendingSyncIsInitial = null
                if (event == Lifecycle.Event.ON_START && fragmentManager != null) {
                    syncFragments(initial = deferred)
                }
            }
        })
    }

    private fun disposeEntries(disposed: List<NavigationEntry>) {
        disposed.forEach { entry ->
            detachFromParent(entry.contentView)
            entry.contentView.disposeWuiTree()
            entry.barSpec?.close()
            entry.state?.close()
        }
    }

    private fun markAppeared(entry: NavigationEntry) {
        if (!entry.isActive) {
            entry.state?.appeared()
            entry.isActive = true
        }
    }

    private fun markDisappeared(entry: NavigationEntry) {
        if (entry.isActive) {
            entry.state?.disappeared()
            entry.isActive = false
        }
    }

    private fun syncFragments(initial: Boolean) {
        val manager = fragmentManager ?: return
        if (manager.isStateSaved) {
            // The tree is attached from a posted callback — the GPU runtime is
            // created before the first view is inflated — so it can land after
            // the activity has saved its state, on a device slow enough to make
            // that a whole frame's difference. The manager refuses transactions
            // until it starts again; the stack is described entirely by
            // `entries`, so replaying it then loses nothing.
            deferSyncUntilStarted(initial)
            return
        }
        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        manager.fragments.filterIsInstance<NavigationDestinationFragment>()
            .filterNot(NavigationDestinationFragment::isBound)
            .forEach(transaction::remove)
        entries.forEachIndexed { index, entry ->
            check(entry.fragment.restorationId() == entry.restorationId) {
                "navigation fragment restoration identity does not match its stack entry"
            }
            if (!entry.fragment.isAdded) {
                transaction.add(contentContainer.id, entry.fragment, entry.restorationId)
            }
            if (index == entries.lastIndex) {
                transaction.show(entry.fragment)
            } else {
                transaction.hide(entry.fragment)
            }
        }
        if (initial) {
            transaction.commitNow()
        } else {
            transaction.commit()
        }
    }

    private fun applyFragmentTransaction(
        id: Long,
        removedEntries: List<NavigationEntry>,
        insertedEntries: List<NavigationEntry>,
        oldTop: View,
        newTop: View
    ) {
        val manager = fragmentManager
        if (manager == null) {
            pendingRemovedEntries += removedEntries
            return
        }
        check(!manager.isStateSaved) {
            "WaterUI navigation transaction arrived after FragmentManager saved its state"
        }

        val isPush = insertedEntries.isNotEmpty()
        // A push is animated by the destination arriving, a pop by the one
        // leaving; either way the moving destination's own declaration wins
        // over the stack's default.
        val moving = if (isPush) insertedEntries.lastOrNull() else removedEntries.lastOrNull()
        var kind = stackTransition
        var sourceId = stackTransitionSourceId
        if (moving != null && moving.transitionKind != TRANSITION_INHERIT) {
            kind = moving.transitionKind
            sourceId = moving.transitionSourceId
        }
        val matchedSource = if (kind == TRANSITION_ZOOM) {
            require(sourceId != 0) {
                "zoom navigation transition requires a non-zero source id"
            }
            val name = navigationTransitionName(sourceId)
            val source = checkNotNull(oldTop.findViewWithTag<View>(sourceId)) {
                "zoom navigation source $sourceId is missing from the outgoing destination"
            }
            val destination = checkNotNull(newTop.findViewWithTag<View>(sourceId)) {
                "zoom navigation source $sourceId is missing from the incoming destination"
            }
            check(destination.transitionName == name) {
                "zoom navigation destination $sourceId has an invalid transition name"
            }
            source.transitionName = name
            source
        } else {
            null
        }
        val enterEffect = if (matchedSource == null) {
            navigationTransition(isPush, kind)
        } else {
            MaterialContainerTransform().apply {
                drawingViewId = contentContainer.id
                transitionDirection = if (isPush) {
                    MaterialContainerTransform.TRANSITION_DIRECTION_ENTER
                } else {
                    MaterialContainerTransform.TRANSITION_DIRECTION_RETURN
                }
            }
        }
        val exitEffect = if (matchedSource == null) navigationTransition(isPush, kind) else null
        entries.last().fragment.enterTransition = enterEffect
        removedEntries.lastOrNull()?.fragment?.exitTransition = exitEffect

        var finished = false
        val settle = settle@{ completed: Boolean ->
            if (finished) {
                return@settle
            }
            finished = true
            activeTransition = null
            removedEntries.forEach { it.state?.popped() }
            disposeEntries(removedEntries)
            markAppeared(entries.last())
            if (completed) {
                completeTransaction(id)
            } else {
                cancelTransaction(id)
            }
        }
        activeTransition = enterEffect
        enterEffect?.addListener(object : Transition.TransitionListener {
            override fun onTransitionStart(transition: Transition) = Unit
            override fun onTransitionCancel(transition: Transition) = settle(false)
            override fun onTransitionPause(transition: Transition) = Unit
            override fun onTransitionResume(transition: Transition) = Unit
            override fun onTransitionEnd(transition: Transition) = settle(true)
        })

        manager.beginTransaction().setReorderingAllowed(true).apply {
            matchedSource?.let { source ->
                addSharedElement(source, navigationTransitionName(sourceId))
            }
            removedEntries.forEach { entry ->
                if (entry.fragment.isAdded) {
                    remove(entry.fragment)
                }
            }
            entries.forEachIndexed { index, entry ->
                if (!entry.fragment.isAdded) {
                    add(contentContainer.id, entry.fragment, entry.restorationId)
                }
                if (index == entries.lastIndex) {
                    show(entry.fragment)
                } else {
                    hide(entry.fragment)
                }
            }
            if (enterEffect == null) {
                runOnCommit { settle(true) }
            }
        }.commit()

        oldTop.alpha = 1f
        newTop.alpha = 1f
    }

    private fun navigationTransition(isPush: Boolean, kind: Int): Transition? = when (kind) {
        TRANSITION_AUTOMATIC -> MaterialSharedAxis(MaterialSharedAxis.X, isPush)
        TRANSITION_FADE -> MaterialFadeThrough()
        TRANSITION_ZOOM -> error("zoom navigation transitions must be configured as matched transitions")
        TRANSITION_NONE -> null
        TRANSITION_CUSTOM -> {
            Log.w("WaterUI", "Custom navigation transition is unavailable on Android; applying without animation")
            null
        }
        else -> error("unknown navigation transition: $kind")
    }
}

private fun buildNavigationEntry(
    context: Context,
    navView: NavigationViewStruct,
    restorationId: String,
    env: WuiEnvironment,
    registry: RenderRegistry
): NavigationEntry {
    val contentView = inflateAnyView(context, navView.contentPtr, env, registry).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    return NavigationEntry(
        contentView = contentView,
        barSpec = buildBarSpec(context, navView.bar, env, registry),
        state = buildNavigationState(navView, env),
        restorationId = restorationId,
        fragment = NavigationDestinationFragment.create(restorationId, contentView),
        transitionKind = navView.transitionKind,
        transitionSourceId = navView.transitionSourceId
    )
}

private fun buildNavigationState(
    navView: NavigationViewStruct,
    env: WuiEnvironment
) = NavigationDestinationState(
    popEnabledPtr = navView.popEnabledPtr,
    popAttemptedPtr = navView.popAttemptedPtr,
    appearPtr = navView.appearPtr,
    disappearPtr = navView.disappearPtr,
    popPtr = navView.popPtr,
    env = env
)

private data class NavigationScreen(
    val view: View,
    val barView: NavigationBarView,
    val barSpec: NavigationBarSpec,
    val state: NavigationDestinationState
)

private fun buildNavigationScreen(
    context: Context,
    navView: NavigationViewStruct,
    env: WuiEnvironment,
    registry: RenderRegistry
): NavigationScreen {
    val barSpec = buildBarSpec(context, navView.bar, env, registry)
    val state = buildNavigationState(navView, env)
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
    container.addView(navigationCoordinator(context, barView, contentView))
    container.addView(barView.bottomToolbar)
    return NavigationScreen(container, barView, barSpec, state)
}

private val navigationStackRenderer = WuiRenderer { context, node, env, registry ->
    val struct: NavigationStackStruct = NativeBindings.waterui_force_as_navigation_stack(node.rawPtr)
    val childEnv = env.clone()

    val stackView = AndroidNavigationStackView(
        context = context,
        stackTransition = struct.transition,
        stackTransitionSourceId = struct.transitionSourceId,
        childEnv = childEnv,
        registry = registry
    )

    val callback = object {
        @Keep
        fun onApply(
            id: Long,
            retainedPrefix: Int,
            removed: Int,
            inserted: Array<NavigationViewStruct>
        ) {
            stackView.apply(id, retainedPrefix, removed, inserted)
        }
    }

    NativeBindings.waterui_env_install_navigation_controller(childEnv.raw(), callback)

    val rootPtr = NativeBindings.waterui_navigation_stack_root(struct.rootPtr, childEnv.raw())

    val rootBar: NavigationBarSpec?
    val rootState: NavigationDestinationState?
    val rootView: View
    if (NativeBindings.waterui_view_id(rootPtr).toTypeId() == navigationViewTypeId) {
        val rootNav = NativeBindings.waterui_force_as_navigation_view(rootPtr)
        rootBar = buildBarSpec(context, rootNav.bar, childEnv, registry)
        rootState = buildNavigationState(rootNav, childEnv)
        rootView = inflateAnyView(context, rootNav.contentPtr, childEnv, registry)
    } else {
        error("resolved navigation stack root is not a NavigationView")
    }

    stackView.installRoot(rootView, rootBar, rootState)
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
        val screen = buildNavigationScreen(context, struct, env, registry)
        screen.state.appeared()
        screen.view.disposeWith {
            screen.state.disappeared()
            screen.state.close()
            screen.barSpec.close()
        }
        screen.view
    }
}

private fun findTabLabelText(view: View): String? {
    if (view is TextView && view.text.isNotBlank()) {
        return view.text.toString()
    }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            findTabLabelText(view.getChildAt(index))?.let { return it }
        }
    }
    return null
}

private fun tabLabelText(view: View): String = findTabLabelText(view)
    ?: error("native Android tabs require a semantic text label")

/// Material's navigation bar draws a 24dp icon; anything else is scaled by the
/// bar and looks it.
private const val TAB_ICON_DP = 24f

private data class AndroidTabIcon(
    val view: View,
    val foreground: ReactiveColorSignal
)

/// Inflates one tab icon as a live WaterUI view.
///
/// A `TabIcon::System` names a symbol from the platform's own catalog. Android
/// has no such catalog (principle 7: an asymmetric primitive is documented, not
/// faked), so naming one here is a mistake in the app rather than something to
/// approximate with a bundled font — it fails loudly and says what to use
/// instead. A portable icon stays attached to the Material item as a real view,
/// so a `GpuSurface` remains GPU-resident instead of being synchronously read
/// back through JNI into a bitmap.
private fun tabIconView(
    context: Context,
    tab: TabStruct,
    env: WuiEnvironment,
    registry: RenderRegistry,
    initialForeground: Int
): AndroidTabIcon? {
    check(tab.systemIconPtr == 0L) {
        "a system icon names a symbol from the platform's own catalog, and Android has none: " +
            "use an icon pack (waterui-icons-lucide, waterui-icons-material-icon, " +
            "waterui-icons-fontawesome7), which draws the same icon on every platform"
    }
    if (tab.iconPtr == 0L) {
        return null
    }
    val iconEnv = env.clone()
    val foreground = ReactiveColorSignal(initialForeground)
    ThemeBridge.installColor(iconEnv, ColorSlot.Foreground, foreground.takeComputed())
    val view = inflateAnyView(context, tab.iconPtr, iconEnv, registry)
    view.makeTabIconDecorative()
    view.disposeWith {
        foreground.close()
        iconEnv.close()
    }
    return AndroidTabIcon(view, foreground)
}

/// A tab icon is visual chrome; the Material item owns input and accessibility.
private fun View.makeTabIconDecorative() {
    isClickable = false
    isLongClickable = false
    isFocusable = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).makeTabIconDecorative()
        }
    }
}

/// Finds Material's single image slot without depending on a private resource id.
private fun View.requireTabIconAnchor(): ImageView {
    var anchor: ImageView? = null
    fun visit(view: View) {
        if (view is ImageView) {
            check(anchor == null) { "Material navigation item has more than one image slot" }
            anchor = view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                visit(view.getChildAt(index))
            }
        }
    }
    visit(this)
    return checkNotNull(anchor) { "Material navigation item has no image slot" }
}

private class AndroidTabEntry(
    val id: Int,
    val title: String,
    val tab: TabStruct,
    val screen: NavigationScreen,
    val badge: WuiComputed<Int>?,
    val enabled: WuiComputed<Boolean>
) {
    var isEnabled = true
    var icon: AndroidTabIcon? = null
}

@SuppressLint("ViewConstructor")
private class AdaptiveTabsView(
    context: Context,
    private val entries: List<AndroidTabEntry>,
    private val selection: WuiBinding<Int>,
    private val style: Int,
    env: WuiEnvironment,
    registry: RenderRegistry
) : ViewGroup(context), WuiSafeAreaManaging {
    private val content = FrameLayout(context)
    // Material 3's navigation bar labels every destination; the auto mode this
    // defaults to is the Material 2 behaviour of dropping every label but the
    // selected one once a bar has four of them.
    private val bottomBar = BottomNavigationView(context).apply {
        labelVisibilityMode = MaterialNavigationBarView.LABEL_VISIBILITY_LABELED
    }
    private val rail = NavigationRailView(context).apply {
        labelVisibilityMode = MaterialNavigationBarView.LABEL_VISIBILITY_LABELED
    }
    private var selectedIndex = -1
    private var usesRail = false
    private var synchronizing = false
    private var safeArea = Insets.NONE
    private var appliedRail: Boolean? = null

    init {
        require(entries.isNotEmpty()) { "native Android tabs require at least one tab" }
        addView(content)
        addView(bottomBar)
        addView(rail)
        require(style in 0..2) { "unknown native tab style: $style" }

        entries.forEachIndexed { index, entry ->
            content.addView(
                entry.screen.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            entry.screen.view.visibility = GONE
            val hasIcon = entry.tab.iconPtr != 0L || entry.tab.systemIconPtr != 0L
            bottomBar.menu.add(0, entry.id, bottomBar.menu.size, entry.title).apply {
                if (hasIcon) icon = ColorDrawable(Color.TRANSPARENT)
            }
            rail.menu.add(0, entry.id, rail.menu.size, entry.title).apply {
                if (hasIcon) icon = ColorDrawable(Color.TRANSPARENT)
            }
            entry.icon = tabIconView(
                context,
                entry.tab,
                env,
                registry,
                iconTint(bottomBar, selected = false, enabled = true)
            )
            entry.enabled.observe { enabled ->
                entry.isEnabled = enabled
                bottomBar.menu.findItem(entry.id).isEnabled = enabled
                rail.menu.findItem(entry.id).isEnabled = enabled
                updateIconColor(entry)
            }
            entry.badge?.observe { count ->
                bottomBar.getOrCreateBadge(entry.id).apply {
                    number = count
                    isVisible = count > 0
                }
                rail.getOrCreateBadge(entry.id).apply {
                    number = count
                    isVisible = count > 0
                }
            }
        }
        attachIcons(bottomBar)

        bottomBar.setOnItemSelectedListener { item -> onItemSelected(item.itemId) }
        rail.setOnItemSelectedListener { item -> onItemSelected(item.itemId) }
        selection.observe { selectedId -> selectId(selectedId, user = false) }
        disposeWith {
            selection.close()
            entries.forEach { entry ->
                entry.badge?.close()
                entry.enabled.close()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        updatePresentation(width, height)

        if (usesRail) {
            rail.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            content.measure(
                MeasureSpec.makeMeasureSpec((width - rail.measuredWidth).coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        } else {
            bottomBar.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
            )
            content.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((height - bottomBar.measuredHeight).coerceAtLeast(0), MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        when {
            usesRail -> {
                rail.layout(0, 0, rail.measuredWidth, height)
                content.layout(rail.measuredWidth, 0, width, height)
            }
            else -> {
                content.layout(0, 0, width, height - bottomBar.measuredHeight)
                bottomBar.layout(0, height - bottomBar.measuredHeight, width, height)
            }
        }
    }

    private fun updatePresentation(width: Int, height: Int) {
        val density = resources.displayMetrics.density
        val windowBounds = WindowMetricsCalculator.getOrCreate()
            .computeCurrentWindowMetrics(context.requireActivity()).bounds
        val sizeClass = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(
            windowBounds.width() / density,
            windowBounds.height() / density
        )
        usesRail = when (style) {
            0 -> sizeClass.isWidthAtLeastBreakpoint(
                WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
            )
            1 -> false
            2 -> true
            else -> error("unknown native tab style: $style")
        }
        rail.visibility = if (usesRail) VISIBLE else GONE
        bottomBar.visibility = if (usesRail) GONE else VISIBLE
        if (appliedRail != usesRail) {
            attachIcons(if (usesRail) rail else bottomBar)
            updateIconColors()
            distributeSafeArea()
        }
    }

    /// Reparents each live icon into the active Material icon container.
    ///
    /// The transparent menu drawable reserves Material's canonical icon slot;
    /// the WaterUI view occupies that slot without ever becoming CPU pixels.
    private fun attachIcons(bar: MaterialNavigationBarView) {
        val iconSize = TAB_ICON_DP.dp(context).toInt()
        entries.forEach { entry ->
            val icon = entry.icon ?: return@forEach
            val item = checkNotNull(bar.findViewById<View>(entry.id)) {
                "Material navigation item ${entry.id} was not created"
            }
            val anchor = item.requireTabIconAnchor()
            val innerContainer = checkNotNull(anchor.parent as? ViewGroup) {
                "Material navigation item ${entry.id} image slot has no container"
            }
            val container = checkNotNull(innerContainer.parent as? FrameLayout) {
                "Material navigation item ${entry.id} icon container is not a FrameLayout"
            }
            if (icon.view.parent !== container) {
                (icon.view.parent as? ViewGroup)?.removeView(icon.view)
                container.addView(
                    icon.view,
                    FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                )
            }
        }
    }

    private fun iconTint(
        bar: MaterialNavigationBarView,
        selected: Boolean,
        enabled: Boolean
    ): Int {
        val tint = checkNotNull(bar.itemIconTintList) {
            "Material navigation bar has no item icon tint"
        }
        val state = intArrayOf(
            if (enabled) android.R.attr.state_enabled else -android.R.attr.state_enabled,
            if (selected) android.R.attr.state_checked else -android.R.attr.state_checked
        )
        return tint.getColorForState(state, tint.defaultColor)
    }

    private fun updateIconColor(entry: AndroidTabEntry) {
        val bar = if (usesRail) rail else bottomBar
        entry.icon?.foreground?.setValue(
            iconTint(
                bar,
                selected = entries.indexOf(entry) == selectedIndex,
                enabled = entry.isEnabled
            )
        )
    }

    private fun updateIconColors() {
        entries.forEach(::updateIconColor)
    }

    /// The bar takes the edge it sits against, so its background reaches under
    /// the system bar there; the tab's own content gets the other three.
    override fun applySafeArea(insets: Insets) {
        safeArea = insets
        distributeSafeArea()
    }

    private fun distributeSafeArea() {
        val insets = safeArea
        val remaining = if (usesRail) {
            rail.setPadding(insets.left, insets.top, 0, insets.bottom)
            Insets.of(0, insets.top, insets.right, insets.bottom)
        } else {
            bottomBar.setPadding(insets.left, 0, insets.right, insets.bottom)
            Insets.of(insets.left, insets.top, insets.right, 0)
        }
        entries.forEach { entry -> applyRemainingInsets(entry.screen.view, remaining) }
        appliedRail = usesRail
    }

    /// Handles a tap on one of the two tab surfaces.
    ///
    /// `selectId` mirrors the new selection onto both surfaces, and assigning
    /// `selectedItemId` runs this listener again — so without the guard a tap
    /// recurses until the stack runs out. While it is set, the incoming
    /// selection is the one this view is already applying rather than a fresh
    /// choice by the user, and accepting it is all that is left to do.
    private fun onItemSelected(id: Int): Boolean {
        if (synchronizing) {
            return true
        }
        return selectId(id, user = true)
    }

    private fun selectId(id: Int, user: Boolean): Boolean {
        val index = entries.indexOfFirst { it.id == id }
        require(index >= 0) { "selected tab id $id is not present" }
        if (user && !entries[index].isEnabled) {
            return false
        }
        if (selectedIndex != index) {
            if (selectedIndex >= 0) {
                entries[selectedIndex].screen.state.disappeared()
                entries[selectedIndex].screen.view.visibility = GONE
            }
            selectedIndex = index
            entries[index].screen.view.visibility = VISIBLE
            entries[index].screen.state.appeared()
        }
        synchronizing = true
        bottomBar.selectedItemId = id
        rail.selectedItemId = id
        synchronizing = false
        updateIconColors()
        if (user) {
            selection.set(id)
        }
        return true
    }

}

/// How many split destinations keep their rendered view tree.
///
/// Eight covers the back-and-forth people actually do; beyond that the least
/// recently shown destination is torn down and rebuilt if it is wanted again,
/// losing only that page's transient state. The GTK backend uses the same
/// number for the same reason.
private const val SPLIT_DESTINATION_CACHE_CAPACITY = 8

private val tabsRenderer = WuiRenderer { context, node, env, registry ->
    val struct: TabsStruct = NativeBindings.waterui_force_as_tabs(node.rawPtr)
    val selection = WuiBinding.id(struct.selectionPtr)
    val entries = struct.tabs.map { tab ->
        val label = inflateAnyView(context, tab.labelPtr, env, registry)
        val title = tabLabelText(label)
        label.disposeWuiTree()
        val nav = NativeBindings.waterui_tab_content(tab.contentPtr, env.raw())
        val screen = buildNavigationScreen(context, nav, env, registry)
        screen.view.disposeWith {
            screen.state.close()
            screen.barSpec.close()
        }
        AndroidTabEntry(
            id = tab.id.toInt(),
            title = title,
            tab = tab,
            screen = screen,
            badge = tab.badgePtr.takeIf { it != 0L }?.let { badgePtr ->
                WuiComputed.int(badgePtr)
            },
            enabled = WuiComputed.bool(tab.enabledPtr)
        )
    }
    AdaptiveTabsView(context, entries, selection, struct.style, env, registry).apply {
        disposeWith {
            struct.tabs.forEach { tab ->
                NativeBindings.waterui_drop_tab_content(tab.contentPtr)
            }
        }
    }
}

private data class SplitNavigationSpec(
    val sidebarView: View,
    val placeholderView: View,
    val primarySelection: WuiBinding<Int>,
    val contentPtr: Long,
    val secondarySelection: WuiBinding<Int>?,
    val detailPtr: Long,
    val columnVisibility: WuiComputed<Int>,
    val sidebarMinWidth: Float,
    val sidebarIdealWidth: Float,
    val sidebarMaxWidth: Float,
    val style: Int,
    val env: WuiEnvironment,
    val registry: RenderRegistry
)

@android.annotation.SuppressLint("ViewConstructor")
private class SplitNavigationLayoutView(
    context: Context,
    spec: SplitNavigationSpec
) : FrameLayout(context), WuiSafeAreaManaging {
    private val sidebarView = spec.sidebarView
    private val placeholderView = spec.placeholderView
    private val primarySelection = spec.primarySelection
    private val contentPtr = spec.contentPtr
    private val secondarySelection = spec.secondarySelection
    private val detailPtr = spec.detailPtr
    private val columnVisibility = spec.columnVisibility
    private val style = spec.style
    private val env = spec.env
    private val registry = spec.registry
    private val outerPane = SlidingPaneLayout(context)
    private val innerPane = contentPtr.takeIf { it != 0L }?.let { SlidingPaneLayout(context) }
    private val contentPane = FrameLayout(context)
    private val detailPane = FrameLayout(context)
    // `LinkedHashMap` in access order: the eldest entry is the least recently
    // shown, which is the one `removeEldestEntry` drops.
    private val contents = destinationCache()
    private val details = destinationCache()
    private var activeContent: NavigationScreen? = null
    private var activeDetail: NavigationScreen? = null
    private var primarySelectedId = Int.MIN_VALUE
    private var secondarySelectedId = Int.MIN_VALUE
    private var visibility = 0
    private var predictiveAllowed = false
    private var predictiveTarget: View? = null
    private var backCallbackInstalled = false
    private var lastOuterSlideable: Boolean? = null
    /// Which pane this view last asked the layout to show, so that asking again
    /// for the same one cannot schedule layouts forever.
    private var appliedPaneShowsDetail: Boolean? = null
    private var lastInnerSlideable: Boolean? = null
    private var safeArea = Insets.NONE
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            val target = activePopTarget()
            predictiveTarget = target?.first
            predictiveAllowed = target?.second?.state?.attemptPop() ?: false
            if (predictiveAllowed) {
                applyPredictiveProgress(backEvent.progress)
            }
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (predictiveAllowed) {
                applyPredictiveProgress(backEvent.progress)
            }
        }

        override fun handleOnBackCancelled() {
            predictiveAllowed = false
            predictiveTarget?.animate()?.translationX(0f)?.alpha(1f)?.start()
            predictiveTarget = null
        }

        override fun handleOnBackPressed() {
            val target = activePopTarget()
            if (!predictiveAllowed && target?.second?.state?.attemptPop() != true) {
                return
            }
            predictiveAllowed = false
            predictiveTarget?.translationX = 0f
            predictiveTarget?.alpha = 1f
            predictiveTarget = null
            commitCompactPop()
        }
    }

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        require(contentPtr == 0L == (secondarySelection == null)) {
            "three-column split content and secondary selection must either both be present or absent"
        }
        require(style in 0..2) { "unknown native split style: $style" }
        require(
            spec.sidebarMinWidth > 0f && spec.sidebarMinWidth <= spec.sidebarIdealWidth &&
                spec.sidebarIdealWidth <= spec.sidebarMaxWidth
        ) { "invalid native split sidebar width constraints" }
        sidebarView.minimumWidth = spec.sidebarMinWidth.dp(context).toInt()
        outerPane.addView(
            sidebarView,
            SlidingPaneLayout.LayoutParams(
                spec.sidebarIdealWidth.dp(context).toInt(),
                SlidingPaneLayout.LayoutParams.MATCH_PARENT
            )
        )
        val trailing = innerPane ?: detailPane
        if (innerPane != null) {
            innerPane.addView(
                contentPane,
                SlidingPaneLayout.LayoutParams(
                    spec.sidebarIdealWidth.dp(context).toInt(),
                    SlidingPaneLayout.LayoutParams.MATCH_PARENT
                )
            )
            innerPane.addView(
                detailPane,
                SlidingPaneLayout.LayoutParams(
                    DETAIL_PANE_MIN_WIDTH_DP.dp(context).toInt(),
                    SlidingPaneLayout.LayoutParams.MATCH_PARENT
                ).apply { weight = if (style == 2) 2f else 1f }
            )
        }
        // `SlidingPaneLayout` lays its panes side by side while they both fit and
        // overlaps them when they do not, and it decides that by measuring the
        // widths the panes ask for — a pane at width 0 with a weight asks for
        // nothing and is handed whatever is left. With the detail pane asking for
        // nothing the sidebar alone always fits, so a phone gets both columns
        // crammed side by side instead of the pushed page it should show. The
        // pane asks for the narrowest width it is usable at and keeps its weight,
        // so it still takes the extra room on a window wide enough for both.
        outerPane.addView(
            trailing,
            SlidingPaneLayout.LayoutParams(
                DETAIL_PANE_MIN_WIDTH_DP.dp(context).toInt(),
                SlidingPaneLayout.LayoutParams.MATCH_PARENT
            ).apply { weight = if (style == 1) 1f else 2f }
        )
        addView(
            outerPane,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Swiping the detail away is a deselection, and the pane reports that as
        // *closed* — the sliding child is the detail, so closed means the list
        // is back on screen (the same reversal `showOuterPane` spells out).
        // Hanging this on `onPanelOpened` cleared the selection the instant a
        // tap brought the detail up, which put the list straight back.
        outerPane.addPanelSlideListener(object : SlidingPaneLayout.PanelSlideListener {
            override fun onPanelSlide(panel: View, slideOffset: Float) = Unit

            override fun onPanelOpened(panel: View) = Unit

            override fun onPanelClosed(panel: View) {
                if (outerPane.isSlideable && primarySelectedId != 0 && visibility == 0) {
                    primarySelection.set(0)
                }
            }
        })
        innerPane?.addPanelSlideListener(object : SlidingPaneLayout.PanelSlideListener {
            override fun onPanelSlide(panel: View, slideOffset: Float) = Unit

            override fun onPanelOpened(panel: View) = Unit

            override fun onPanelClosed(panel: View) {
                if (innerPane.isSlideable && secondarySelectedId != 0 && visibility == 0) {
                    checkNotNull(secondarySelection).set(0)
                }
            }
        })
        primarySelection.observe(::refreshPrimary)
        secondarySelection?.observe(::refreshSecondary)
        columnVisibility.observe { next ->
            require(next in 0..3) { "unknown navigation split column visibility: $next" }
            visibility = next
            applyVisibility()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val lifecycleOwner = checkNotNull(findViewTreeLifecycleOwner()) {
            "WaterUI split navigation requires a ViewTreeLifecycleOwner"
        }
        if (!backCallbackInstalled) {
            val dispatcherOwner = context.requireOnBackPressedDispatcherOwner()
            dispatcherOwner.onBackPressedDispatcher.addCallback(lifecycleOwner, backCallback)
            backCallbackInstalled = true
        }
        check(primarySelectedId != Int.MIN_VALUE) {
            "WaterUI split navigation primary selection was not observed before attachment"
        }
        refreshPrimary(primarySelectedId)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (lastOuterSlideable != outerPane.isSlideable ||
            lastInnerSlideable != innerPane?.isSlideable
        ) {
            lastOuterSlideable = outerPane.isSlideable
            lastInnerSlideable = innerPane?.isSlideable
            updateBackState()
        }
    }

    /// Shows whichever pane the current selection means.
    ///
    /// KNOWN GAP: this is applied where the selection changes, which is right
    /// while the layout stays put, but a `SlidingPaneLayout` only learns whether
    /// it can slide once it has been measured and comes up showing the list. A
    /// selection made before that first measure — every phone-width launch, and
    /// every rotation back from a two-pane window — therefore leaves the list on
    /// screen with its own detail hidden behind it, and the user has to pick the
    /// row again. Re-applying this from `onLayout` when slideability changes is
    /// the obvious repair and does fix the visible behaviour, but it puts the
    /// window into an input-dispatch stall — "Waited 5025ms for
    /// FocusEvent(hasFocus=true)" with an idle main thread — so the transition
    /// needs to be driven from wherever the window's focus is settled, not from
    /// layout.
    private fun applySelectedPaneState() {
        if (visibility != 0) {
            return
        }
        // Both calls ask for a layout, and this runs off the back of one, so
        // asking for the pane that is already showing would schedule layouts
        // forever.
        showOuterPane(showsDetail = primarySelectedId != 0)
    }

    /// Puts one of the two outer panes on screen, once.
    ///
    /// Every route into the outer pane goes through here so the record of which
    /// pane was asked for stays true; a caller that opened or closed the pane
    /// behind this record would leave it stale, and the next re-apply would skip
    /// the change it was supposed to make.
    ///
    /// `openPane` slides the sliding view — the second child, the detail — to
    /// offset zero, where it covers the list; `closePane` slides it away and
    /// reveals the list. The names read backwards for a list/detail split, which
    /// is why they are only spelled out here.
    private fun showOuterPane(showsDetail: Boolean) {
        if (appliedPaneShowsDetail == showsDetail) {
            return
        }
        appliedPaneShowsDetail = showsDetail
        if (showsDetail) {
            outerPane.openPane()
        } else {
            outerPane.closePane()
        }
    }

    fun disposeNavigation() {
        activeDetail?.state?.disappeared()
        activeContent?.state?.disappeared()
        details.values.forEach { detail ->
            detachFromParent(detail.view)
            detail.view.disposeWuiTree()
            detail.barSpec.close()
            detail.state.close()
        }
        contents.values.forEach { content ->
            detachFromParent(content.view)
            content.view.disposeWuiTree()
            content.barSpec.close()
            content.state.close()
        }
        details.clear()
        contents.clear()
        detachFromParent(sidebarView)
        detachFromParent(placeholderView)
        sidebarView.disposeWuiTree()
        placeholderView.disposeWuiTree()
        removeAllViews()
        backCallback.remove()
        primarySelection.close()
        secondarySelection?.close()
        columnVisibility.close()
    }

    /// Builds a bounded, access-ordered cache of rendered destinations.
    ///
    /// Keeping a destination is what preserves its scroll position and
    /// half-typed input when the user comes back to it, so the cache must hold
    /// more than the current one. It must not hold *every* destination ever
    /// visited: each is a rendered view tree, its bar spec and its navigation
    /// state, retained for the life of the split. An eldest entry that is
    /// evicted is torn down exactly the way `disposeNavigation` tears one down.
    private fun destinationCache(): MutableMap<Int, NavigationScreen> =
        object : LinkedHashMap<Int, NavigationScreen>(
            SPLIT_DESTINATION_CACHE_CAPACITY,
            0.75f,
            true
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, NavigationScreen>): Boolean {
                if (size <= SPLIT_DESTINATION_CACHE_CAPACITY) {
                    return false
                }
                // Never evict what is on screen. Access order puts the current
                // screen at the young end, so this only guards the degenerate
                // case of a capacity so small the current one is also eldest.
                val screen = eldest.value
                if (screen === activeContent || screen === activeDetail) {
                    return false
                }
                detachFromParent(screen.view)
                screen.view.disposeWuiTree()
                screen.barSpec.close()
                screen.state.close()
                return true
            }
        }

    private fun refreshPrimary(nextSelectedId: Int) {
        if (contentPtr == 0L) {
            refreshTwoColumn(nextSelectedId)
            return
        }
        if (nextSelectedId == primarySelectedId && contentPane.isNotEmpty()) {
            updateBackState()
            return
        }
        activeContent?.state?.disappeared()
        activeDetail?.state?.disappeared()
        primarySelectedId = nextSelectedId
        contentPane.removeAllViews()
        detailPane.removeAllViews()
        activeContent = null
        activeDetail = null

        if (nextSelectedId == 0) {
            showPlaceholder()
            applySelectedPaneState()
            updateBackState()
            return
        }

        val screen = contents.getOrPut(nextSelectedId) { buildScreen(contentPtr, nextSelectedId) }
        activeContent = screen
        attachScreen(contentPane, screen)
        screen.state.appeared()
        applySelectedPaneState()
        refreshSecondary(secondarySelectedId.coerceAtLeast(0))
        updateBackState()
    }

    private fun refreshTwoColumn(nextSelectedId: Int) {
        if (nextSelectedId == primarySelectedId && detailPane.isNotEmpty()) {
            updateBackState()
            return
        }
        activeDetail?.state?.disappeared()
        primarySelectedId = nextSelectedId
        detailPane.removeAllViews()
        activeDetail = null

        if (nextSelectedId == 0) {
            showPlaceholder()
            applySelectedPaneState()
            updateBackState()
            return
        }

        val screen = details.getOrPut(nextSelectedId) { buildScreen(detailPtr, nextSelectedId) }
        activeDetail = screen
        attachScreen(detailPane, screen)
        applySelectedPaneState()
        screen.state.appeared()
        updateBackState()
    }

    private fun refreshSecondary(nextSelectedId: Int) {
        if (innerPane == null || primarySelectedId <= 0) {
            return
        }
        if (nextSelectedId == secondarySelectedId && detailPane.isNotEmpty()) {
            updateBackState()
            return
        }
        activeDetail?.state?.disappeared()
        secondarySelectedId = nextSelectedId
        detailPane.removeAllViews()
        activeDetail = null

        if (nextSelectedId == 0) {
            showPlaceholder()
            // Nothing is selected, so the list is what should be on screen:
            // slide the detail away (see showOuterPane for why the names read
            // backwards here).
            innerPane.closePane()
            updateBackState()
            return
        }

        val screen = details.getOrPut(nextSelectedId) { buildScreen(detailPtr, nextSelectedId) }
        activeDetail = screen
        attachScreen(detailPane, screen)
        if (visibility == 0) {
            innerPane.openPane()
        }
        screen.state.appeared()
        updateBackState()
    }

    private fun buildScreen(builderPtr: Long, id: Int): NavigationScreen {
        val navigationView = NativeBindings.waterui_split_navigation_detail_content(
            builderPtr,
            id,
            env.raw()
        )
        return buildNavigationScreen(context, navigationView, env, registry)
    }

    private fun updateBackState() {
        val activeTarget = activePopTarget()
        backCallback.isEnabled = activeTarget != null
        contents.values.forEach { screen ->
            val showsBack = screen === activeContent && outerPane.isSlideable
            screen.barView.bind(
                screen.barSpec,
                showBack = showsBack,
                onBack = if (showsBack) {{
                    if (screen.state.attemptPop()) primarySelection.set(0)
                }} else null
            )
        }
        details.values.forEach { screen ->
            val usesSecondary = innerPane?.isSlideable == true && secondarySelectedId != 0
            val showsBack = screen === activeDetail &&
                (usesSecondary || (innerPane == null && outerPane.isSlideable))
            screen.barView.bind(
                screen.barSpec,
                showBack = showsBack,
                onBack = if (showsBack) {{
                    if (screen.state.attemptPop()) commitCompactPop()
                }} else null
            )
        }
    }

    private fun applyPredictiveProgress(progress: Float) {
        predictiveTarget?.translationX = (predictiveTarget?.width ?: 0) * progress
        predictiveTarget?.alpha = 1f - progress * 0.1f
    }

    private fun activePopTarget(): Pair<View, NavigationScreen>? {
        if (innerPane?.isSlideable == true && secondarySelectedId > 0) {
            return activeDetail?.let { detailPane to it }
        }
        if (outerPane.isSlideable && primarySelectedId > 0) {
            return (activeContent ?: activeDetail)?.let { (innerPane ?: detailPane) to it }
        }
        return null
    }

    private fun commitCompactPop() {
        if (innerPane?.isSlideable == true && secondarySelectedId > 0) {
            checkNotNull(secondarySelection).set(0)
        } else if (outerPane.isSlideable && primarySelectedId > 0) {
            primarySelection.set(0)
        }
    }

    /// Every column reaches the window's edges on its own.
    ///
    /// The panes sit side by side in a wide window and slide over one another
    /// in a narrow one, so each of them is against the top and the bottom and
    /// each can be against a side. Handing all four to each column lets a column
    /// that owns chrome — usually a navigation stack — place it against the
    /// hardware, and pads the ones that do not.
    override fun applySafeArea(insets: Insets) {
        safeArea = insets
        distributeSafeArea()
    }

    private fun distributeSafeArea() {
        applyRemainingInsets(sidebarView, safeArea)
        listOf(contentPane, detailPane).forEach { pane ->
            pane.getChildAt(0)?.let { applyRemainingInsets(it, safeArea) }
        }
    }

    private fun attachScreen(parent: FrameLayout, screen: NavigationScreen) {
        detachFromParent(screen.view)
        parent.addView(
            screen.view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        distributeSafeArea()
    }

    private fun showPlaceholder() {
        detachFromParent(placeholderView)
        detailPane.removeAllViews()
        detailPane.addView(
            placeholderView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        distributeSafeArea()
    }

    private fun applyVisibility() {
        when (visibility) {
            0 -> {
                showOuterPane(showsDetail = primarySelectedId > 0)
                innerPane?.let { pane ->
                    if (secondarySelectedId > 0) pane.closePane() else pane.openPane()
                }
            }
            1 -> {
                showOuterPane(showsDetail = false)
                innerPane?.openPane()
            }
            2 -> {
                showOuterPane(showsDetail = true)
                innerPane?.openPane()
            }
            3 -> {
                showOuterPane(showsDetail = true)
                innerPane?.closePane()
            }
            else -> error("unknown navigation split column visibility: $visibility")
        }
    }
}

private val splitNavigationContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct: SplitNavigationContainerStruct =
        NativeBindings.waterui_force_as_split_navigation_container(node.rawPtr)

    val sidebar = inflateAnyView(context, struct.sidebarPtr, env, registry)
    val placeholder = inflateAnyView(context, struct.placeholderPtr, env, registry)
    // `Int`, not `id`: the split's selection crosses as `WuiBinding<i32>` with 0
    // standing for "nothing selected" (`optional_id_binding` in the FFI maps it
    // to `Option<Id>`). Reading it through the `Id` accessors punned the binding
    // to `WuiBinding<Id>`, and clearing the selection then tried to build an
    // `Id` out of 0 — which is a `NonZeroI32` — and killed the process.
    val primarySelection = WuiBinding.int(struct.primarySelectionPtr)
    val secondarySelection = struct.secondarySelectionPtr.takeIf { it != 0L }?.let { ptr ->
        WuiBinding.int(ptr)
    }
    val columnVisibility = WuiComputed.int(struct.columnVisibilityPtr)

    val splitSpec = SplitNavigationSpec(
        sidebarView = sidebar,
        placeholderView = placeholder,
        primarySelection = primarySelection,
        contentPtr = struct.contentPtr,
        secondarySelection = secondarySelection,
        detailPtr = struct.detailPtr,
        columnVisibility = columnVisibility,
        sidebarMinWidth = struct.sidebarMinWidth,
        sidebarIdealWidth = struct.sidebarIdealWidth,
        sidebarMaxWidth = struct.sidebarMaxWidth,
        style = struct.style,
        env = env,
        registry = registry
    )
    val container = SplitNavigationLayoutView(context, splitSpec)

    container.disposeWith {
        container.disposeNavigation()
        if (struct.contentPtr != 0L) {
            NativeBindings.waterui_drop_split_navigation_detail(struct.contentPtr)
        }
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
