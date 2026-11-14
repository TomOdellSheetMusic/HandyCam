package com.example.handycam

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualLensTest {
    private val wide = VirtualLens.Sensor("1", 3.3f, 4.45056f, 3648)
    private val crop = VirtualLens.Sensor("3", 3.3f, 3.92352f, 3216)

    @Test fun frontCropUsesWiderCameraAndMatchingZoom() {
        val result = VirtualLens.resolve(crop, listOf(wide, crop), 4f)
        assertEquals("1", result.cameraId)
        assertEquals(3648f / 3216f, result.zoomRatio, 0.001f)
        assertEquals("1", VirtualLens.resolve(wide, listOf(wide, crop), 4f).cameraId)
    }

    @Test fun differentSensorOrInsufficientZoomKeepsOriginalCamera() {
        val other = wide.copy(focal = 3.8f)
        assertEquals("3", VirtualLens.resolve(crop, listOf(other, crop), 4f).cameraId)
        assertEquals("3", VirtualLens.resolve(crop, listOf(wide, crop), 1.05f).cameraId)
    }

    @Test fun normalizesRatioForBothCameraPipelines() {
        val ratio = 1.2f
        val avc = VirtualLens.normalizedZoom(ratio, 1f, 4f, true)
        val mjpeg = VirtualLens.normalizedZoom(ratio, 1f, 4f, false)
        assertEquals(ratio, 1f + avc * 3f, 0.0001f)
        assertEquals(ratio, 1f / ((1f - mjpeg) + mjpeg / 4f), 0.0001f)
    }
}
