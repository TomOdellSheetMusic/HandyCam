package com.example.handycam

import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class YuvImageConversionTest {
    @Test
    fun convertsContiguousLumaAndPlanarChroma() {
        val y = plane(byteArrayOf(1, 2, 3, 4), rowStride = 2, pixelStride = 1)
        val u = plane(byteArrayOf(5), rowStride = 1, pixelStride = 1)
        val v = plane(byteArrayOf(6), rowStride = 1, pixelStride = 1)
        val image = proxy<ImageProxy> { method ->
            when (method) {
                "getWidth", "getHeight" -> 2
                "getPlanes" -> arrayOf(y, u, v)
                else -> error("Unexpected ImageProxy call: $method")
            }
        }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 6, 5), image.toNv21())
    }

    @Test
    fun convertsPaddedPlanesWithInterleavedChroma() {
        val y = plane(byteArrayOf(1, 2, 99, 3, 4, 99), rowStride = 3, pixelStride = 1)
        val u = plane(byteArrayOf(5, 99), rowStride = 2, pixelStride = 2)
        val v = plane(byteArrayOf(6, 99), rowStride = 2, pixelStride = 2)
        val image = proxy<ImageProxy> { method ->
            when (method) {
                "getWidth", "getHeight" -> 2
                "getPlanes" -> arrayOf(y, u, v)
                else -> error("Unexpected ImageProxy call: $method")
            }
        }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 6, 5), image.toNv21())
    }

    private fun plane(bytes: ByteArray, rowStride: Int, pixelStride: Int): ImageProxy.PlaneProxy {
        val buffer = ByteBuffer.wrap(bytes)
        return proxy { method ->
            when (method) {
                "getBuffer" -> buffer
                "getRowStride" -> rowStride
                "getPixelStride" -> pixelStride
                else -> error("Unexpected PlaneProxy call: $method")
            }
        }
    }

    private inline fun <reified T> proxy(crossinline value: (String) -> Any): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            value(method.name)
        } as T
}
