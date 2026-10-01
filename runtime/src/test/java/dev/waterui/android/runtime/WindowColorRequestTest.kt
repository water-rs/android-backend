package dev.waterui.android.runtime

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WindowColorRequestTest {
    @Test
    fun negotiationTakesTheWidestStandardDynamicRangeMode() {
        val request = WindowColorRequest.NEGOTIATED
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, request.colorMode(false, false))
        assertEquals(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, request.colorMode(true, true))
    }

    @Test
    fun aPreferredRangeNarrowsToTheDisplay() {
        val preferHdr = WindowColorRequest(request = 1, range = 2)
        assertEquals(ActivityInfo.COLOR_MODE_HDR, preferHdr.colorMode(true, true))
        assertEquals(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, preferHdr.colorMode(true, false))
        assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, preferHdr.colorMode(false, false))
    }

    @Test
    fun aRequiredRangeTheDisplayCannotShowIsAnError() {
        val requireWide = WindowColorRequest(request = 2, range = 1)
        assertEquals(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, requireWide.colorMode(true, false))
        assertThrows(IllegalStateException::class.java) { requireWide.colorMode(false, false) }
    }
}
