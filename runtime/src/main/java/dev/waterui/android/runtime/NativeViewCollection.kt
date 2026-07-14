package dev.waterui.android.runtime

import dev.waterui.android.reactive.WatcherGuard
import java.io.Closeable

internal data class NativeViewItem<T>(val id: Int, val value: T)

/** Reconciles an identity-aware Rust view collection without rebuilding stable items. */
internal class NativeViewCollection<T : Closeable>(
    handle: Long,
    private val expectedType: WuiTypeId,
    private val consume: (Long) -> T
) : Closeable {
    private val source = NativeAnyViews(handle)
    private val watcher: WatcherGuard
    private var valuesById = linkedMapOf<Int, T>()
    private var ordered = emptyList<NativeViewItem<T>>()
    private var observer: (List<NativeViewItem<T>>) -> Unit = {}

    init {
        watcher = source.watch(::reconcile)
        reconcile(source.ids())
    }

    fun observe(onChanged: (List<NativeViewItem<T>>) -> Unit) {
        observer = onChanged
        onChanged(ordered)
    }

    override fun close() {
        watcher.close()
        observer = {}
        valuesById.values.forEach(Closeable::close)
        valuesById.clear()
        ordered = emptyList()
        source.close()
    }

    private fun reconcile(ids: IntArray) {
        if (ids.size == ordered.size && ids.indices.all { index -> ids[index] == ordered[index].id }) {
            return
        }

        val seen = HashSet<Int>(ids.size)
        val previous = valuesById
        val next = LinkedHashMap<Int, T>(ids.size)
        val nextOrdered = ArrayList<NativeViewItem<T>>(ids.size)
        ids.forEachIndexed { index, id ->
            check(seen.add(id)) { "native view collection contains duplicate id $id" }
            val value = previous.remove(id) ?: consumeView(index)
            next[id] = value
            nextOrdered += NativeViewItem(id, value)
        }
        previous.values.forEach(Closeable::close)
        valuesById = next
        ordered = nextOrdered
        observer(ordered)
    }

    private fun consumeView(index: Int): T {
        val viewPtr = source.viewAt(index)
        check(viewPtr != 0L) { "native view collection returned a null view at index $index" }
        val actualType = NativeBindings.waterui_view_id(viewPtr).toTypeId()
        check(actualType == expectedType) {
            "native view collection expected $expectedType at index $index, got $actualType"
        }
        return consume(viewPtr)
    }
}
