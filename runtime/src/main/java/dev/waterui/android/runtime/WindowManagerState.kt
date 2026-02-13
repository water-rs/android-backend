package dev.waterui.android.runtime

import java.util.concurrent.atomic.AtomicLong

internal enum class WindowDispatchDecision {
    ACCEPT,
    DROP_STALE_GENERATION,
    DROP_MISSING_HOST,
    DROP_DETACHED_HOST,
    DROP_MISSING_ACTIVITY,
    DROP_MISSING_ENV
}

internal data class WindowManagerStateSnapshot(
    val generation: Long,
    val envPtr: Long,
    val hostToken: Int?,
    val activeSessionCount: Int
)

internal data class WindowDetachResult(
    val detached: Boolean,
    val sessionsToClose: Set<Long>
)

internal data class WindowAttachResult(
    val generation: Long,
    val replacedSessions: Set<Long>
)

/**
 * Thread-safe host/session state used by WindowManager.
 *
 * This isolates lifecycle decisions from Android UI types so we can unit test
 * multi-window behavior (stale request rejection, detach cleanup, etc.).
 */
internal class WindowManagerState {
    private val generation = AtomicLong(1L)

    @Volatile
    private var hostToken: Int? = null

    @Volatile
    private var envPtr: Long = 0L

    private val activeSessionIds = linkedSetOf<Long>()
    private val lock = Any()

    fun captureGeneration(): Long = generation.get()

    fun attach(hostToken: Int, envPtr: Long): WindowAttachResult {
        synchronized(lock) {
            val changed = this.hostToken != hostToken || this.envPtr != envPtr
            val replacedSessions = if (changed) {
                val stale = activeSessionIds.toSet()
                activeSessionIds.clear()
                stale
            } else {
                emptySet()
            }
            this.hostToken = hostToken
            this.envPtr = envPtr
            if (changed) {
                generation.incrementAndGet()
            }
            return WindowAttachResult(
                generation = generation.get(),
                replacedSessions = replacedSessions
            )
        }
    }

    fun detach(maybeHostToken: Int?, maybeEnvPtr: Long): WindowDetachResult {
        synchronized(lock) {
            val currentHostToken = hostToken
            if (currentHostToken == null) {
                return WindowDetachResult(detached = false, sessionsToClose = emptySet())
            }
            val shouldDetach = maybeHostToken == null ||
                maybeHostToken == currentHostToken ||
                (maybeEnvPtr != 0L && maybeEnvPtr == envPtr)
            if (!shouldDetach) {
                return WindowDetachResult(detached = false, sessionsToClose = emptySet())
            }

            hostToken = null
            envPtr = 0L
            generation.incrementAndGet()

            val toClose = activeSessionIds.toSet()
            activeSessionIds.clear()
            return WindowDetachResult(detached = true, sessionsToClose = toClose)
        }
    }

    fun currentEnvPtr(): Long = envPtr

    fun evaluateDispatch(
        expectedGeneration: Long,
        hasHost: Boolean,
        hostAttached: Boolean,
        hasActivity: Boolean
    ): WindowDispatchDecision {
        val currentGeneration = generation.get()
        if (expectedGeneration != currentGeneration) {
            return WindowDispatchDecision.DROP_STALE_GENERATION
        }
        if (!hasHost) {
            return WindowDispatchDecision.DROP_MISSING_HOST
        }
        if (!hostAttached) {
            return WindowDispatchDecision.DROP_DETACHED_HOST
        }
        if (!hasActivity) {
            return WindowDispatchDecision.DROP_MISSING_ACTIVITY
        }
        if (envPtr == 0L) {
            return WindowDispatchDecision.DROP_MISSING_ENV
        }
        return WindowDispatchDecision.ACCEPT
    }

    fun registerSession(sessionId: Long, expectedGeneration: Long): Boolean {
        synchronized(lock) {
            if (expectedGeneration != generation.get() || hostToken == null || envPtr == 0L) {
                return false
            }
            activeSessionIds.add(sessionId)
            return true
        }
    }

    fun unregisterSession(sessionId: Long) {
        synchronized(lock) {
            activeSessionIds.remove(sessionId)
        }
    }

    fun snapshotForTests(): WindowManagerStateSnapshot {
        synchronized(lock) {
            return WindowManagerStateSnapshot(
                generation = generation.get(),
                envPtr = envPtr,
                hostToken = hostToken,
                activeSessionCount = activeSessionIds.size
            )
        }
    }
}
