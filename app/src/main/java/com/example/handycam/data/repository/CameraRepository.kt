package com.example.handycam.data.repository

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
                val focalLengthStr = focalLengths?.firstOrNull()?.let { "%.1fmm".format(it) }
                
                CameraInfo(
                    id = cameraId,
                    displayName = "$facing ($cameraId)",
                    facing = facing,
                    focalLength = focalLengthStr
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Find camera ID by facing direction or logical name.
     * Supports "back", "front", or specific camera IDs.
     */
    fun findCameraId(cameraSelector: String): String? {
        return try {
            when (cameraSelector.lowercase()) {
                "back" -> {
                    cameraManager.cameraIdList.firstOrNull { id ->
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        characteristics.get(CameraCharacteristics.LENS_FACING) == 
                            CameraCharacteristics.LENS_FACING_BACK
                    }
                }
                "front" -> {
                    cameraManager.cameraIdList.firstOrNull { id ->
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        characteristics.get(CameraCharacteristics.LENS_FACING) == 
                            CameraCharacteristics.LENS_FACING_FRONT
                    }
                }
                else -> {
                    // Check if it's a valid camera ID
                    if (cameraManager.cameraIdList.contains(cameraSelector)) {
                        cameraSelector
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if camera supports torch/flash.
     */
    fun isTorchAvailable(cameraId: String): Boolean {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Enable or disable torch for a specific camera.
     */
    fun setTorchMode(cameraId: String, enabled: Boolean): Result<Unit> {
        return try {
            cameraManager.setTorchMode(cameraId, enabled)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
