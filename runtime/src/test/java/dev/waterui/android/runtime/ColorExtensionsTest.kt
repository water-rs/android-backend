package dev.waterui.android.runtime

import org.junit.Test
import org.junit.Assert.*

class ColorExtensionsTest {
    @Test
    fun srgbToLinear_isAccurateAtKnownPoints() {
        assertEquals(0.0f, srgbToLinear(0.0f), 0.0f)
        assertEquals(1.0f, srgbToLinear(1.0f), 1e-6f)
        assertEquals(0.21404114f, srgbToLinear(0.5f), 1e-5f)
    }

    @Test
    fun srgbToLinear_usesLinearSegmentBelowThreshold() {
        val threshold = 0.04045f
        val justBelow = threshold - 0.00001f
        assertEquals(justBelow / 12.92f, srgbToLinear(justBelow), 1e-6f)
    }

    @Test
    fun srgbToLinear_isMonotonicInDisplayRange() {
        val samples = listOf(0.0f, 0.1f, 0.25f, 0.5f, 0.75f, 1.0f)
        val converted = samples.map(::srgbToLinear)
        for (i in 1 until converted.size) {
            assertTrue("sample $i should be >= previous", converted[i] >= converted[i - 1])
        }
    }
}
