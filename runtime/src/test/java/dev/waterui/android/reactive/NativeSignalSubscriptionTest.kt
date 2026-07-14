package dev.waterui.android.reactive

import dev.waterui.android.runtime.WuiAnimation
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSignalSubscriptionTest {
    @Test
    fun watcherIsInstalledBeforeInitialRead() {
        val order = mutableListOf<String>()
        val subscription = NativeSignalSubscription(
            read = {
                order += "read"
                7
            },
            subscribe = {
                order += "watch"
                Closeable {}
            },
            isOwnerReleased = { false },
            releaseValue = {},
            valuesEqual = Int::equals
        )

        subscription.observe { _, _ -> }

        assertEquals(listOf("watch", "read"), order)
        subscription.close()
    }

    @Test
    fun changeDuringWatcherInstallationCannotBeMissed() {
        var source = 1
        val observed = mutableListOf<Int>()
        val subscription = NativeSignalSubscription(
            read = { source },
            subscribe = { observer ->
                source = 2
                observer(source, WuiAnimation.None)
                Closeable {}
            },
            isOwnerReleased = { false },
            releaseValue = {},
            valuesEqual = Int::equals
        )

        subscription.observe { value, _ -> observed += value }

        assertEquals(listOf(2), observed)
        subscription.close()
    }

    @Test
    fun synchronousOwnedValueSkipsInitialReadAndReleasesExactlyOnce() {
        data class OwnedValue(val id: Int)

        var reads = 0
        val observed = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val subscription = NativeSignalSubscription(
            read = {
                reads += 1
                OwnedValue(2)
            },
            subscribe = { observer ->
                observer(OwnedValue(1), WuiAnimation.None)
                Closeable {}
            },
            isOwnerReleased = { false },
            releaseValue = { released += it.id },
            valuesEqual = { _, _ -> false }
        )

        subscription.observe { value, _ -> observed += value.id }
        subscription.close()

        assertEquals(0, reads)
        assertEquals(listOf(1), observed)
        assertEquals(listOf(1), released)
    }

    @Test
    fun replacedAndFinalValuesAreReleasedExactlyOnce() {
        lateinit var emit: (Int, WuiAnimation) -> Unit
        val released = mutableListOf<Int>()
        var watcherClosed = false
        val subscription = NativeSignalSubscription(
            read = { 1 },
            subscribe = { observer ->
                emit = observer
                Closeable { watcherClosed = true }
            },
            isOwnerReleased = { false },
            releaseValue = released::add,
            valuesEqual = Int::equals
        )

        subscription.observe { _, _ -> }
        emit(2, WuiAnimation.None)
        subscription.close()

        assertEquals(listOf(1, 2), released)
        assertTrue(watcherClosed)
    }
}
