package dev.waterui.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppStructTest {
    @Test
    fun ownedPointersTransferIndependently() {
        val app = AppStruct(contentPtr = 41L, envPtr = 73L, backgroundPtr = 97L)

        assertEquals(73L, app.takeEnvironment())
        assertEquals(97L, app.takeBackground())
        assertEquals(41L, app.takeContent())
    }

    @Test
    fun eachOwnedPointerCanOnlyBeTakenOnce() {
        val app = AppStruct(contentPtr = 41L, envPtr = 73L, backgroundPtr = 97L)
        app.takeContent()
        app.takeEnvironment()
        app.takeBackground()

        assertThrows(IllegalStateException::class.java) { app.takeContent() }
        assertThrows(IllegalStateException::class.java) { app.takeEnvironment() }
        assertThrows(IllegalStateException::class.java) { app.takeBackground() }
    }

    @Test
    fun nullOwnedPointersFailAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            AppStruct(contentPtr = 0L, envPtr = 73L, backgroundPtr = 97L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppStruct(contentPtr = 41L, envPtr = 0L, backgroundPtr = 97L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppStruct(contentPtr = 41L, envPtr = 73L, backgroundPtr = 0L)
        }
    }
}
