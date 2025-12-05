package com.example.handycam.presentation.main

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.handycam.StreamService
import com.example.handycam.data.model.AppSettings
import com.example.handycam.data.model.CameraInfo
import com.example.handycam.data.model.StreamConfig
import com.example.handycam.data.repository.SettingsRepository
import com.example.handycam.data.repository.StreamRepository
import com.example.handycam.domain.usecase.GetAvailableCamerasUseCase
import com.example.handycam.domain.usecase.StartStreamUseCase
import com.example.handycam.domain.usecase.StopStreamUseCase
import com.example.handycam.domain.usecase.SwitchCameraUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for MainActivity.
 * Manages UI state and coordinates business logic.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val streamRepository: StreamRepository,
    private val getAvailableCamerasUseCase: GetAvailableCamerasUseCase,
    private val startStreamUseCase: StartStreamUseCase,
    private val stopStreamUseCase: StopStreamUseCase,
    private val switchCameraUseCase: SwitchCameraUseCase
) : ViewModel() {

    // Observe app settings
    val appSettings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // Observe stream config
    val streamConfig: StateFlow<StreamConfig> = settingsRepository.streamConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamConfig())

    // Observe stream state
    val streamState = streamRepository.streamState
        .stateIn(viewModelScope, SharingStarted.Eagerly, streamRepository.streamState.value)

    // Available cameras list
    private val _availableCameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val availableCameras: StateFlow<List<CameraInfo>> = _availableCameras.asStateFlow()

    // UI events
    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    init {
        loadAvailableCameras()
    }

    private fun loadAvailableCameras() {
        viewModelScope.launch {
            _availableCameras.value = getAvailableCamerasUseCase()
        }
    }

    fun startStreaming(
        host: String,
        port: Int,
        width: Int,
        height: Int,
        camera: String,
        jpegQuality: Int,
        fps: Int,
        useAvc: Boolean,
        avcBitrate: Int? = null
    ) {
        viewModelScope.launch {
            val config = StreamConfig(
                width = width,
                height = height,
                jpegQuality = jpegQuality,
                fps = fps,
                useAvc = useAvc,
                avcBitrate = avcBitrate
            )

            val result = startStreamUseCase(host, port, camera, config)
            
            if (result.isSuccess) {
                // Start the service
                val intent = Intent(context, StreamService::class.java).apply {
                    action = "com.example.handycam.ACTION_START"
                    putExtra("host", host)
                    putExtra("port", port)
                    putExtra("width", width)
                    putExtra("height", height)
                    putExtra("camera", camera)
                    putExtra("jpegQuality", jpegQuality)
                    putExtra("targetFps", fps)
                    putExtra("useAvc", useAvc)
                    avcBitrate?.let { putExtra("avcBitrate", it) }
                }
                context.startForegroundService(intent)
                _uiEvent.emit(UiEvent.StreamStarted)
            } else {
                _uiEvent.emit(UiEvent.Error(result.exceptionOrNull()?.message ?: "Failed to start stream"))
            }
        }
    }

    fun stopStreaming() {
        viewModelScope.launch {
            stopStreamUseCase()
            
            // Stop the service
            val intent = Intent(context, StreamService::class.java).apply {
                action = "com.example.handycam.ACTION_STOP"
            }
            context.startService(intent)
            _uiEvent.emit(UiEvent.StreamStopped)
        }
    }

    fun switchCamera(camera: String) {
        viewModelScope.launch {
            val result = switchCameraUseCase(camera)
            if (result.isSuccess) {
                // Notify service to switch camera
                val intent = Intent(context, StreamService::class.java).apply {
                    action = "com.example.handycam.ACTION_SET_CAMERA"
                    putExtra("camera", camera)
                }
                context.startService(intent)
            } else {
                _uiEvent.emit(UiEvent.Error(result.exceptionOrNull()?.message ?: "Failed to switch camera"))
            }
        }
    }

    fun updateHost(host: String) {
        viewModelScope.launch {
            settingsRepository.updateHost(host)
        }
    }

    fun updatePort(port: Int) {
        viewModelScope.launch {
            settingsRepository.updatePort(port)
        }
    }

    fun updateResolution(width: Int, height: Int) {
        viewModelScope.launch {
            settingsRepository.updateResolution(width, height)
        }
    }

    fun updateJpegQuality(quality: Int) {
        viewModelScope.launch {
            settingsRepository.updateJpegQuality(quality)
        }
    }

    fun updateFps(fps: Int) {
        viewModelScope.launch {
            settingsRepository.updateFps(fps)
        }
    }

    fun updateUseAvc(useAvc: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateUseAvc(useAvc)
        }
    }

    fun updateAvcBitrate(bitrate: Int?) {
        viewModelScope.launch {
            settingsRepository.updateAvcBitrate(bitrate)
        }
    }

    sealed class UiEvent {
        object StreamStarted : UiEvent()
        object StreamStopped : UiEvent()
        data class Error(val message: String) : UiEvent()
    }
}
