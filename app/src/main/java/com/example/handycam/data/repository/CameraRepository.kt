package com.example.handycam.data.repository

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.example.handycam.FrameRateCapabilities
import com.example.handycam.data.model.CameraInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for camera-related operations.
 * Abstracts camera hardware access and provides clean API.
 */
@Singleton
class CameraRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    fun maxStreamingFps(camera: String, width: Int, height: Int, avc: Boolean): Int =
        FrameRateCapabilities.maxStreamingFps(cameraManager, camera, width, height, avc)

    /**
     * Get list of all available cameras with their metadata.
     */
    fun getAvailableCameras(): List<CameraInfo> {
        return try {
            cameraManager.cameraIdList.map { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focalLength = focalLengths?.firstOrNull()
                val focalLengthStr = focalLength?.let { "%.1fmm".format(it) }
                val megapixels = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { size ->
                    val mp = (size.width * size.height).toDouble() / 1_000_000.0
                    "%.1fMP".format(mp)
                }
                val cameraLabel = listOfNotNull(focalLengthStr, megapixels).joinToString(" • ")
                
                val name = when {
                    facing == "front" -> "Front"
                    facing == "external" -> "External"
                    focalLength != null && focalLength < 3.0f -> "Ultrawide"
                    focalLength != null && focalLength > 10.0f -> "Telephoto"
                    else -> "Main Wide"
                }
                
                CameraInfo(
                    id = cameraId,
                    displayName = "$name ${cameraLabel}".trim(),
                    facing = facing,
                    focalLength = cameraLabel.ifEmpty { null }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

}
