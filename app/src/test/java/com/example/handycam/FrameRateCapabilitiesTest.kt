package com.example.handycam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateCapabilitiesTest {
    @Test fun highSpeedSessionIsOnlyUsedBeyondRegularCaptureRate() {
        val limits = FrameRateCapabilities.Limits(60, 120)
        assertEquals(120, limits.maximum)
        assertFalse(limits.needsHighSpeed(60))
        assertTrue(limits.needsHighSpeed(120))
        assertFalse(limits.needsHighSpeed(121))
    }

    @Test fun regularHighRatesDoNotRequireConstrainedSession() {
        val limits = FrameRateCapabilities.Limits(90, 120)
        assertFalse(limits.needsHighSpeed(90))
        assertTrue(limits.needsHighSpeed(91))
    }
}
