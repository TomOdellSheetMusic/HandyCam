package com.example.handycam.domain.usecase

import com.example.handycam.data.repository.CameraRepository
import javax.inject.Inject

/**
 * Use case for toggling camera torch/flash.
 */
class ToggleTorchUseCase @Inject constructor(
    private val cameraRepository: CameraRepository
) {
    operator fun invoke(cameraId: String, enabled: Boolean): Result<Unit> {
        return cameraRepository.setTorchMode(cameraId, enabled)
    }
}
