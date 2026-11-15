package com.example.handycam

/** Limits independent frames using the actual send clock, not queued frame timestamps. */
internal class FrameRateLimiter(fps: Int) {
    private val intervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
    private var lastSentNs: Long? = null

    fun shouldSend(nowNs: Long): Boolean {
        val last = lastSentNs
        if (last != null && nowNs - last < intervalNs) return false
        lastSentNs = nowNs
        return true
    }
}
