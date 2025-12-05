package com.example.handycam.domain.usecase

import com.example.handycam.data.model.StreamConfig
import com.example.handycam.data.repository.SettingsRepository
import com.example.handycam.data.repository.StreamRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case for starting the video streaming service.
 * Encapsulates the business logic for initializing and starting a stream.
 */
class StartStreamUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val streamRepository: StreamRepository
) {
    /**
     * Execute the use case to start streaming.
     * @return Result indicating success or failure
     */
    suspend operator fun invoke(
        host: String,
        port: Int,
        camera: String,
        streamConfig: StreamConfig
    ): Result<Unit> {
        return try {
            // Save settings
            settingsRepository.updateHost(host)
            settingsRepository.updatePort(port)
            settingsRepository.updateCamera(camera)
            settingsRepository.updateStreamConfig(streamConfig)
            settingsRepository.updateStreamingState(true)

            // Update stream state
            streamRepository.updateStreamState(
                isActive = true,
                host = host,
                port = port,
                camera = camera
            )

            Result.success(Unit)
        } catch (e: Exception) {
            streamRepository.updateStreamState(
                isActive = false,
                error = e.message ?: "Failed to start stream"
            )
            Result.failure(e)
        }
    }
}
