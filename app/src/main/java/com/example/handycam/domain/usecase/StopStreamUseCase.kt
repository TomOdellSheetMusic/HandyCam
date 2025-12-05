package com.example.handycam.domain.usecase

import com.example.handycam.data.repository.SettingsRepository
import com.example.handycam.data.repository.StreamRepository
import javax.inject.Inject

/**
 * Use case for stopping the video streaming service.
 */
class StopStreamUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val streamRepository: StreamRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        return try {
            settingsRepository.updateStreamingState(false)
            streamRepository.updateStreamState(isActive = false)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
