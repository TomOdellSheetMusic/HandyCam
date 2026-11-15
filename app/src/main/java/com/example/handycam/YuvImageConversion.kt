package com.example.handycam

import androidx.camera.core.ImageProxy

internal fun ImageProxy.toNv21(): ByteArray {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val yBuffer = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
    val uBuffer = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
    val vBuffer = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }
    val nv21 = ByteArray(width * height * 3 / 2)
    var pos = 0

    if (yPlane.pixelStride == 1 && yPlane.rowStride == width) {
        System.arraycopy(yBuffer, 0, nv21, 0, width * height)
        pos = width * height
    } else {
        for (row in 0 until height) {
            for (col in 0 until width) {
                nv21[pos++] = yBuffer[row * yPlane.rowStride + col * yPlane.pixelStride]
            }
        }
    }

    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            nv21[pos++] = vBuffer[row * vPlane.rowStride + col * vPlane.pixelStride]
            nv21[pos++] = uBuffer[row * uPlane.rowStride + col * uPlane.pixelStride]
        }
    }
    return nv21
}
