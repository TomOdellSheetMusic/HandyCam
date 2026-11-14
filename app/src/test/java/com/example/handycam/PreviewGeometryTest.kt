package com.example.handycam

import com.example.handycam.presentation.cameracontrol.PreviewGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewGeometryTest {
    @Test fun portraitDisplayFitsRotatedLandscapeBuffer() {
        val (width, height) = PreviewGeometry.fit(1080f, 2400f, 1920, 1080, 90, 0, false)
        assertEquals(1080f, width, 0.01f)
        assertEquals(1920f, height, 0.01f)
    }

    @Test fun landscapeDisplayFitsBufferWithoutStretching() {
        val (width, height) = PreviewGeometry.fit(2400f, 1080f, 1920, 1080, 90, 90, false)
        assertEquals(1920f, width, 0.01f)
        assertEquals(1080f, height, 0.01f)
    }

    @Test fun frontFacingAndSquareBuffersAreHandled() {
        val front = PreviewGeometry.fit(1080f, 2400f, 1920, 1080, 270, 0, true)
        assertEquals(1080f / 1920f, front.first / front.second, 0.0001f)
        val square = PreviewGeometry.fit(2400f, 1080f, 1000, 1000, 270, 90, true)
        assertEquals(1080f, square.first, 0.01f)
        assertEquals(1080f, square.second, 0.01f)
    }

    @Test fun unknownBufferUsesAvailableBounds() {
        assertEquals(1080f to 2400f, PreviewGeometry.fit(1080f, 2400f, 0, 0, 90, 0, false))
    }
}
