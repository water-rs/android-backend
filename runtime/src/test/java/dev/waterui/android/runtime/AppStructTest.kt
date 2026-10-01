package dev.waterui.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppStructTest {
    private fun app(contentPtr: Long = 41L, envPtr: Long = 73L, backgroundPtr: Long = 97L) =
        AppStruct(
            contentPtr = contentPtr,
            envPtr = envPtr,
            backgroundPtr = backgroundPtr,
            colorSpaceRequest = 0,
            colorSpaceRange = 0
        )

    @Test
    fun ownedPointersTransferIndependently() {
        val app = app()

        assertEquals(73L, app.takeEnvironment())
        assertEquals(97L, app.takeBackground())
        assertEquals(41L, app.takeContent())
    }

    @Test
    fun eachOwnedPointerCanOnlyBeTakenOnce() {
        val app = app()
        app.takeContent()
        app.takeEnvironment()
        app.takeBackground()

        assertThrows(IllegalStateException::class.java) { app.takeContent() }
        assertThrows(IllegalStateException::class.java) { app.takeEnvironment() }
        assertThrows(IllegalStateException::class.java) { app.takeBackground() }
    }

    @Test
    fun nullOwnedPointersFailAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) { app(contentPtr = 0L) }
        assertThrows(IllegalArgumentException::class.java) { app(envPtr = 0L) }
        assertThrows(IllegalArgumentException::class.java) { app(backgroundPtr = 0L) }
    }
}
