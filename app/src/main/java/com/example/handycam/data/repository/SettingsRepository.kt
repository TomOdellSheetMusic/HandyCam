package com.example.handycam.data.repository

import com.example.handycam.data.model.AppSettings
import com.example.handycam.data.model.StreamConfig
import com.example.handycam.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for app settings.
 * Provides reactive access to user preferences via Flow.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val preferencesManager: PreferencesManager
) {
    
    val appSettings: Flow<AppSettings> = preferencesManager.appSettingsFlow
    val streamConfig: Flow<StreamConfig> = preferencesManager.streamConfigFlow

    suspend fun updateHost(host: String) = preferencesManager.updateHost(host)
    
    suspend fun updatePort(port: Int) = preferencesManager.updatePort(port)
    
    suspend fun updateCamera(camera: String) = preferencesManager.updateCamera(camera)
    
    suspend fun updateStreamingState(isStreaming: Boolean) = 
        preferencesManager.updateStreamingState(isStreaming)
    
    suspend fun updateStreamConfig(config: StreamConfig) = 
        preferencesManager.updateStreamConfig(config)
    
    suspend fun updateResolution(width: Int, height: Int) = 
        preferencesManager.updateResolution(width, height)
    
    suspend fun updateJpegQuality(quality: Int) = 
        preferencesManager.updateJpegQuality(quality)
    
    suspend fun updateFps(fps: Int) = preferencesManager.updateFps(fps)
    
    suspend fun updateUseAvc(useAvc: Boolean) = preferencesManager.updateUseAvc(useAvc)
    
    suspend fun updateAvcBitrate(bitrate: Int?) = preferencesManager.updateAvcBitrate(bitrate)
}
