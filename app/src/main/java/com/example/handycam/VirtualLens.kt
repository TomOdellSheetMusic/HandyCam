package com.example.handycam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlin.math.abs

/** Detect front-camera crop variants using matching focal length and sensor pixel pitch. */
object VirtualLens {
    data class Sensor(val id: String, val focal: Float, val widthMm: Float, val widthPixels: Int)
    data class Selection(val cameraId: String, val zoomRatio: Float)

    fun resolve(target: Sensor, candidates: List<Sensor>, maxZoom: Float): Selection {
        val pitch = target.widthMm / target.widthPixels
        val widest = candidates.filter {
            it.widthPixels > 0 && it.widthMm > 0f &&
                abs(it.focal - target.focal) < 0.001f &&
                abs(it.widthMm / it.widthPixels / pitch - 1f) < 0.005f
        }.maxByOrNull { it.widthMm } ?: return Selection(target.id, 1f)
        val ratio = widest.widthMm / target.widthMm
        return if (ratio > 1.01f && ratio <= maxZoom) Selection(widest.id, ratio)
        else Selection(target.id, 1f)
    }

    fun resolve(manager: CameraManager, id: String): Selection {
        val fallback = Selection(id, 1f)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || PhysicalLens.parse(id) != null) return fallback
        return try {
            fun sensor(cameraId: String): Sensor? {
                val chars = manager.getCameraCharacteristics(cameraId)
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT ||
                    chars.physicalCameraIds.isNotEmpty()) return null
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.singleOrNull() ?: return null
                val width = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: return null
                val pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width ?: return null
                if (focal <= 0f || width <= 0f || pixels <= 0) return null
                return Sensor(cameraId, focal, width, pixels)
            }
            val target = sensor(id) ?: return fallback
            val candidates = manager.cameraIdList.mapNotNull { sensor(it) }
            val proposed = resolve(target, candidates, Float.MAX_VALUE)
            val maxZoom = manager.getCameraCharacteristics(proposed.cameraId)
                .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
            resolve(target, candidates, maxZoom)
        } catch (_: Exception) { fallback }
    }

    fun normalizedZoom(ratio: Float, min: Float, max: Float, avc: Boolean): Float {
        if (max <= min) return 0f
        val clamped = ratio.coerceIn(min, max)
        return if (avc) (clamped - min) / (max - min)
        else ((1f / min - 1f / clamped) / (1f / min - 1f / max)).coerceIn(0f, 1f)
    }
}
