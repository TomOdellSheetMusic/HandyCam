package com.example.handycam

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.util.Range
import android.util.Size
import kotlin.math.roundToInt

/** Rates supported at the selected resolution by regular or AVC high-speed sessions. */
object FrameRateCapabilities {
    data class Limits(val regular: Int, val highSpeed: Int) {
        val maximum: Int get() = maxOf(regular, highSpeed)
        fun needsHighSpeed(fps: Int): Boolean = fps > regular && fps <= highSpeed
    }
    fun clampTarget(fps: Int, maximum: Int): Int = fps.coerceIn(1, maximum.coerceAtLeast(1))
    fun selectRange(ranges: List<Range<Int>>, fps: Int): Range<Int>? =
        ranges.filter { it.upper >= fps }
            .minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })

    /** Match the resolution used by the Camera2 encoder path. */
    fun selectOutputSize(sizes: List<Size>, width: Int, height: Int): Size? {
        if (sizes.isEmpty() || width <= 0 || height <= 0) return null
        val w = maxOf(width, height)
        val h = minOf(width, height)
        val targetAspect = w.toFloat() / h
        fun aspectDifference(size: Size) = kotlin.math.abs(size.width.toFloat() / size.height - targetAspect)
        fun areaDifference(size: Size) = kotlin.math.abs(size.width.toLong() * size.height - w.toLong() * h)
        val similar = sizes.filter { aspectDifference(it) <= 0.05f }
        return if (similar.isNotEmpty()) similar.minWithOrNull(compareBy<Size> { areaDifference(it) }.thenBy { aspectDifference(it) })
        else sizes.minWithOrNull(compareBy<Size> { aspectDifference(it) }.thenBy { areaDifference(it) })
    }

    fun limits(manager: CameraManager, selection: String, width: Int, height: Int, avc: Boolean): Limits {
        // A conservative target when Android does not report usable stream metadata.
        val fallback = Limits(30, 0)
        return try {
            if (width <= 0 || height <= 0) return fallback
            val physical = PhysicalLens.parse(selection)
            val requested = physical?.first ?: manager.cameraIdList.firstOrNull { id ->
                id == selection || (selection == "front" && manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) ||
                    (selection == "back" && manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK)
            } ?: return fallback
            val logicalId = if (physical == null) VirtualLens.resolve(manager, requested).cameraId else requested
            val logical = manager.getCameraCharacteristics(logicalId)
            val sensor = manager.getCameraCharacteristics(physical?.second ?: logicalId)
            val map = sensor.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fallback
            val requestedSize = Size(maxOf(width, height), minOf(width, height))
            val size = if (avc) selectOutputSize(map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList(), width, height)
                ?: requestedSize else requestedSize
            val encoders = if (avc) MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
            } else emptyList()
            fun encoderSupports(fps: Int): Boolean = !avc || encoders.any { codec ->
                try {
                    val capabilities = codec.getCapabilitiesForType("video/avc")
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in capabilities.colorFormats &&
                        capabilities.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
                } catch (_: Exception) { false }
            }
            val regular = try {
                val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
                val surfaceSizes = if (avc) map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray() else emptyArray()
                if (size !in yuvSizes || (avc && size !in surfaceSizes)) 30 else {
                    val durations = mutableListOf(map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size))
                    if (avc) durations.add(map.getOutputMinFrameDuration(SurfaceTexture::class.java, size))
                    val limit = if (durations.any { it <= 0 }) 30 else (1_000_000_000.0 / durations.max()).roundToInt()
                    logical.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.upper }
                        ?.map { minOf(it, limit) }?.filter { it > 0 && encoderSupports(it) }?.maxOrNull() ?: 30
                }
            } catch (_: Exception) { 30 }
            val highSpeed = if (avc && physical == null) try {
                if (size in map.highSpeedVideoSizes) {
                    map.getHighSpeedVideoFpsRangesFor(size).map { it.upper }
                        .filter { it > 60 && encoderSupports(it) }.maxOrNull() ?: 0
                } else 0
            } catch (_: Exception) { 0 } else 0
            Limits(regular, highSpeed)
        } catch (_: Exception) { fallback }
    }

    fun maxStreamingFps(manager: CameraManager, selection: String, width: Int, height: Int, avc: Boolean): Int =
        limits(manager, selection, width, height, avc).maximum

    fun highSpeedRange(manager: CameraManager, cameraId: String, width: Int, height: Int, fps: Int): Range<Int>? {
        return try {
            val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val size = Size(maxOf(width, height), minOf(width, height))
            if (size !in map.highSpeedVideoSizes) return null
            map.getHighSpeedVideoFpsRangesFor(size).filter { it.upper >= fps }
                .minWithOrNull(compareBy<Range<Int>> { it.upper }.thenByDescending { it.lower })
        } catch (_: Exception) { null }
    }
}
