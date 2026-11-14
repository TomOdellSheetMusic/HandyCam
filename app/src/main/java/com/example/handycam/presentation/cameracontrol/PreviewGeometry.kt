package com.example.handycam.presentation.cameracontrol

import kotlin.math.min

/** Dimensions for an upright Camera2 SurfaceView, fitted without cropping or stretching. */
internal object PreviewGeometry {
    fun fit(
        containerWidth: Float,
        containerHeight: Float,
        bufferWidth: Int,
        bufferHeight: Int,
        sensorOrientation: Int,
        displayRotation: Int,
        frontFacing: Boolean,
    ): Pair<Float, Float> {
        if (containerWidth <= 0f || containerHeight <= 0f || bufferWidth <= 0 || bufferHeight <= 0) {
            return containerWidth to containerHeight
        }
        val sign = if (frontFacing) 1 else -1
        val rotation = (sensorOrientation - displayRotation * sign + 360) % 360
        val swapped = rotation == 90 || rotation == 270
        val width = (if (swapped) bufferHeight else bufferWidth).toFloat()
        val height = (if (swapped) bufferWidth else bufferHeight).toFloat()
        val scale = min(containerWidth / width, containerHeight / height)
        return width * scale to height * scale
    }
}
