package dev.waterui.android.components

import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.WatcherStruct
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The overlay's `placed_edge` write-back: every placement reports the logical
 * edge the platform actually used (the physical edge the shared contract
 * resolved under the layout direction, converted back), and only writes when
 * the edge changed so a stable placement does not churn watchers.
 *
 * The binding is a `WuiBinding<Int>` whose JNI entry points are faked by
 * lambdas — `WuiAnchorEdge` crosses as its ordinal, so the Kotlin side is a
 * plain `Int` (`0 Top, 1 Bottom, 2 Leading, 3 Trailing`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnchoredOverlayPresentationTest {
    /** A `WuiBinding` backed by a Kotlin-side cell that records every write. */
    private class FakeEdgeBinding(initial: Int = 0) {
        var stored = initial
        val writes = mutableListOf<Int>()
        val binding: WuiBinding<Int> = WuiBinding(
            bindingPtr = 1L,
            reader = { stored },
            writer = { _, value -> stored = value; writes += value },
            watcherFactory = { WatcherStruct(0, 0, 0) },
            watcherRegistrar = { _, _ -> 1L },
            dropper = {}
        )
    }

    @Test
    fun placedEdgeRecordsTheResolvedLogicalEdgeAfterAFlip() {
        val fake = FakeEdgeBinding(initial = 0)
        // The platform asked for Top (0); the placement flipped to Bottom (1).
        reportPlacedEdge(fake.binding, 1)
        assertEquals(listOf(1), fake.writes)
        assertEquals(1, fake.stored)
    }

    @Test
    fun placedEdgeWritesOnlyWhenTheEdgeChanged() {
        val fake = FakeEdgeBinding(initial = 1)
        reportPlacedEdge(fake.binding, 1)
        reportPlacedEdge(fake.binding, 1)
        assertEquals(emptyList<Int>(), fake.writes)
        reportPlacedEdge(fake.binding, 3)
        assertEquals(listOf(3), fake.writes)
    }
}
