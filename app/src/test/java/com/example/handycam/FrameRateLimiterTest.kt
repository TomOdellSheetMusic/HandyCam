package com.example.handycam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateLimiterTest {
    @Test fun limitsBurstsBySendTime() {
        val limiter = FrameRateLimiter(15)
        assertTrue(limiter.shouldSend(1_000_000_000L))
        assertFalse(limiter.shouldSend(1_001_000_000L))
        assertFalse(limiter.shouldSend(1_066_000_000L))
        assertTrue(limiter.shouldSend(1_067_000_000L))
    }

    @Test fun admitsFramesAtRequestedInterval() {
        val limiter = FrameRateLimiter(15)
        val accepted = (0..30).count { limiter.shouldSend(it * 66_666_667L) }
        assertEquals(31, accepted)
    }
}
