package dev.waterui.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppStructTest {
    @Test
    fun contentAndEnvironmentOwnershipTransferIndependently() {
        val app = AppStruct(contentPtr = 41L, envPtr = 73L)

        assertEquals(73L, app.takeEnvironment())
        assertEquals(41L, app.takeContent())
    }

    @Test
    fun eachOwnedPointerCanOnlyBeTakenOnce() {
        val app = AppStruct(contentPtr = 41L, envPtr = 73L)
        app.takeContent()
        app.takeEnvironment()

        assertThrows(IllegalStateException::class.java) { app.takeContent() }
        assertThrows(IllegalStateException::class.java) { app.takeEnvironment() }
    }

    @Test
    fun nullOwnedPointersFailAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            AppStruct(contentPtr = 0L, envPtr = 73L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppStruct(contentPtr = 41L, envPtr = 0L)
        }
    }
}
