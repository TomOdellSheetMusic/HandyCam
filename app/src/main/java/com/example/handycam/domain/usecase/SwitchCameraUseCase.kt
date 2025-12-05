package com.example.handycam.domain.usecase

import com.example.handycam.data.repository.CameraRepository
import com.example.handycam.data.repository.SettingsRepository
import javax.inject.Inject

/**
 * Use case for switching the active camera during streaming.
 */
class SwitchCameraUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cameraRepository: CameraRepository
) {
    suspend operator fun invoke(cameraSelector: String): Result<String> {
        return try {
            // Validate camera exists
            val cameraId = cameraRepository.findCameraId(cameraSelector)
                ?: return Result.failure(IllegalArgumentException("Camera not found: $cameraSelector"))

            // Update settings
            settingsRepository.updateCamera(cameraSelector)

            Result.success(cameraId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
