package dev.waterui.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowManagerStateTest {
    @Test
    fun attachSameHostAndEnvDoesNotInvalidateSessions() {
        val state = WindowManagerState()
        val first = state.attach(hostToken = 11, envPtr = 101L)
        assertTrue(first.replacedSessions.isEmpty())

        assertTrue(state.registerSession(sessionId = 1L, expectedGeneration = first.generation))
        val second = state.attach(hostToken = 11, envPtr = 101L)

        assertEquals(first.generation, second.generation)
        assertTrue(second.replacedSessions.isEmpty())
        assertEquals(1, state.snapshotForTests().activeSessionCount)
    }

    @Test
    fun attachNewEnvReplacesExistingSessions() {
        val state = WindowManagerState()
        val initial = state.attach(hostToken = 11, envPtr = 101L)
        assertTrue(state.registerSession(sessionId = 1L, expectedGeneration = initial.generation))
        assertTrue(state.registerSession(sessionId = 2L, expectedGeneration = initial.generation))

        val next = state.attach(hostToken = 11, envPtr = 202L)
        assertEquals(setOf(1L, 2L), next.replacedSessions)
        assertTrue(next.generation > initial.generation)
        assertEquals(0, state.snapshotForTests().activeSessionCount)
    }

    @Test
    fun detachClearsEnvAndReturnsSessionsToClose() {
        val state = WindowManagerState()
        val attach = state.attach(hostToken = 42, envPtr = 900L)
        assertTrue(state.registerSession(sessionId = 7L, expectedGeneration = attach.generation))
        assertTrue(state.registerSession(sessionId = 8L, expectedGeneration = attach.generation))

        val result = state.detach(maybeHostToken = 42, maybeEnvPtr = 900L)
        assertTrue(result.detached)
        assertEquals(setOf(7L, 8L), result.sessionsToClose)
        assertEquals(0L, state.currentEnvPtr())
    }

    @Test
    fun detachMismatchedHostIsIgnored() {
        val state = WindowManagerState()
        val attach = state.attach(hostToken = 42, envPtr = 900L)
        assertTrue(state.registerSession(sessionId = 1L, expectedGeneration = attach.generation))

        val result = state.detach(maybeHostToken = 99, maybeEnvPtr = 0L)
        assertFalse(result.detached)
        assertTrue(result.sessionsToClose.isEmpty())
        assertEquals(900L, state.currentEnvPtr())
        assertEquals(1, state.snapshotForTests().activeSessionCount)
    }

    @Test
    fun dispatchRejectsStaleGenerationBeforeOtherChecks() {
        val state = WindowManagerState()
        val attach = state.attach(hostToken = 1, envPtr = 3L)
        val staleGeneration = attach.generation - 1

        val decision = state.evaluateDispatch(
            expectedGeneration = staleGeneration,
            hasHost = true,
            hostAttached = true,
            hasActivity = true
        )

        assertEquals(WindowDispatchDecision.DROP_STALE_GENERATION, decision)
    }

    @Test
    fun dispatchRequiresHostAttachmentActivityAndEnv() {
        val state = WindowManagerState()
        val attach = state.attach(hostToken = 9, envPtr = 5L)

        assertEquals(
            WindowDispatchDecision.DROP_MISSING_HOST,
            state.evaluateDispatch(attach.generation, hasHost = false, hostAttached = true, hasActivity = true)
        )
        assertEquals(
            WindowDispatchDecision.DROP_DETACHED_HOST,
            state.evaluateDispatch(attach.generation, hasHost = true, hostAttached = false, hasActivity = true)
        )
        assertEquals(
            WindowDispatchDecision.DROP_MISSING_ACTIVITY,
            state.evaluateDispatch(attach.generation, hasHost = true, hostAttached = true, hasActivity = false)
        )
    }

    @Test
    fun dispatchRejectsWhenEnvMissingAfterDetach() {
        val state = WindowManagerState()
        val attach = state.attach(hostToken = 5, envPtr = 77L)
        state.detach(maybeHostToken = 5, maybeEnvPtr = 77L)

        val decision = state.evaluateDispatch(
            expectedGeneration = attach.generation + 1,
            hasHost = true,
            hostAttached = true,
            hasActivity = true
        )

        assertEquals(WindowDispatchDecision.DROP_MISSING_ENV, decision)
    }

    @Test
    fun registerSessionFailsForStaleGeneration() {
        val state = WindowManagerState()
        val attach = state.attach(hostToken = 12, envPtr = 55L)
        val stale = attach.generation - 1

        assertFalse(state.registerSession(sessionId = 99L, expectedGeneration = stale))
        assertEquals(0, state.snapshotForTests().activeSessionCount)
    }

    @Test
    fun attachNewHostTokenReplacesExistingSessionsEvenWhenEnvUnchanged() {
        val state = WindowManagerState()
        val first = state.attach(hostToken = 1, envPtr = 500L)
        assertTrue(state.registerSession(sessionId = 10L, expectedGeneration = first.generation))

        val second = state.attach(hostToken = 2, envPtr = 500L)

        assertEquals(setOf(10L), second.replacedSessions)
        assertTrue(second.generation > first.generation)
        assertEquals(0, state.snapshotForTests().activeSessionCount)
    }

    @Test
    fun detachByEnvironmentPointerWorksWithoutHostToken() {
        val state = WindowManagerState()
        val attach = state.attach(hostToken = 7, envPtr = 77L)
        assertTrue(state.registerSession(sessionId = 1L, expectedGeneration = attach.generation))

        val detached = state.detach(maybeHostToken = null, maybeEnvPtr = 77L)

        assertTrue(detached.detached)
        assertEquals(setOf(1L), detached.sessionsToClose)
        assertEquals(0L, state.currentEnvPtr())
    }

    @Test
    fun unregisterSessionRemovesActiveSession() {
        val state = WindowManagerState()
        val attach = state.attach(hostToken = 3, envPtr = 900L)
        assertTrue(state.registerSession(sessionId = 123L, expectedGeneration = attach.generation))
        assertEquals(1, state.snapshotForTests().activeSessionCount)

        state.unregisterSession(123L)

        assertEquals(0, state.snapshotForTests().activeSessionCount)
    }
}
