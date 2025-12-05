package com.example.handycam.domain.usecase

import com.example.handycam.data.model.CameraInfo
import com.example.handycam.data.repository.CameraRepository
import javax.inject.Inject

/**
 * Use case for getting list of available cameras.
 */
class GetAvailableCamerasUseCase @Inject constructor(
    private val cameraRepository: CameraRepository
) {
    operator fun invoke(): List<CameraInfo> {
        return cameraRepository.getAvailableCameras()
    }
}
