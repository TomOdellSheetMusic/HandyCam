package com.example.handycam.presentation.main

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.handycam.KtorHttpsServerService
import com.example.handycam.StreamService
import com.example.handycam.data.model.CameraInfo
import com.example.handycam.data.model.StreamConfig
import com.example.handycam.data.repository.SettingsRepository
import com.example.handycam.domain.usecase.GetAvailableCamerasUseCase
import com.example.handycam.domain.usecase.StartStreamUseCase
import com.example.handycam.domain.usecase.StopStreamUseCase
import com.example.handycam.service.StreamStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val streamStateHolder: StreamStateHolder,
    private val settingsRepository: SettingsRepository,
    private val getAvailableCamerasUseCase: GetAvailableCamerasUseCase,
    private val startStreamUseCase: StartStreamUseCase,
    private val stopStreamUseCase: StopStreamUseCase,
) : ViewModel() {

    val isStreaming: StateFlow<Boolean> = streamStateHolder.isStreaming
    val httpsRunning: StateFlow<Boolean> = streamStateHolder.httpsRunning

    private val _availableCameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val availableCameras: StateFlow<List<CameraInfo>> = _availableCameras.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    val localIp: String get() = getLocalIpAddress() ?: "0.0.0.0"

    init {
        viewModelScope.launch { _availableCameras.value = getAvailableCamerasUseCase() }
        viewModelScope.launch {
            val settings = settingsRepository.appSettings.first()
            streamStateHolder.setHost(settings.host)
            streamStateHolder.setStreamingPort(settings.streamingPort)
            streamStateHolder.setCamera(settings.camera)
            streamStateHolder.setUseScreenCapture(settings.useScreenCapture)
        }
        viewModelScope.launch {
            val config = settingsRepository.streamConfig.first()
            streamStateHolder.setWidth(config.width)
            streamStateHolder.setHeight(config.height)
            streamStateHolder.setJpegQuality(config.jpegQuality)
            streamStateHolder.setFps(config.fps)
            streamStateHolder.setUseAvc(config.useAvc)
            streamStateHolder.setAvcBitrate(config.avcBitrate ?: -1)
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
        avcBitrate: Int? = null,
        useScreenCapture: Boolean = false,
        mediaProjectionResultCode: Int = 0,
        mediaProjectionData: android.content.Intent? = null
    ) {
        viewModelScope.launch {
            val config = StreamConfig(width, height, camera, jpegQuality, fps, useAvc, avcBitrate)
            startStreamUseCase(host, port, camera, config)

            val intent = Intent(context, StreamService::class.java).apply {
                action = "com.example.handycam.ACTION_START"
                putExtra("host", host)
                putExtra("streamingPort", port)
                putExtra("width", width)
                putExtra("height", height)
                putExtra("camera", camera)
                putExtra("jpegQuality", jpegQuality)
                putExtra("targetFps", fps)
                putExtra("useAvc", useAvc)
                avcBitrate?.let { putExtra("avcBitrate", it) }
                putExtra("useScreenCapture", useScreenCapture)
                if (useScreenCapture) {
                    putExtra("mediaProjectionResultCode", mediaProjectionResultCode)
                    putExtra("mediaProjectionData", mediaProjectionData)
                }
            }
            context.startForegroundService(intent)
        }
    }

    fun stopStreaming() {
        viewModelScope.launch {
            stopStreamUseCase()
            context.startService(Intent(context, StreamService::class.java).apply {
                action = "com.example.handycam.ACTION_STOP"
            })
        }
    }

    fun updateHost(host: String) {
        streamStateHolder.setHost(host)
        viewModelScope.launch { settingsRepository.updateHost(host) }
    }

    fun updateStreamingPort(port: Int) {
        streamStateHolder.setStreamingPort(port)
        viewModelScope.launch { settingsRepository.updateStreamingPort(port) }
    }

    fun updateCamera(camera: String) {
        streamStateHolder.setCamera(camera)
        viewModelScope.launch { settingsRepository.updateCamera(camera) }
    }

    fun updateResolution(width: Int, height: Int) {
        streamStateHolder.setWidth(width)
        streamStateHolder.setHeight(height)
        viewModelScope.launch { settingsRepository.updateResolution(width, height) }
    }

    fun updateJpegQuality(quality: Int) {
        streamStateHolder.setJpegQuality(quality)
        viewModelScope.launch { settingsRepository.updateJpegQuality(quality) }
    }

    fun updateFps(fps: Int) {
        streamStateHolder.setFps(fps)
        viewModelScope.launch { settingsRepository.updateFps(fps) }
    }

    fun updateUseAvc(useAvc: Boolean) {
        streamStateHolder.setUseAvc(useAvc)
        viewModelScope.launch { settingsRepository.updateUseAvc(useAvc) }
    }

    fun updateAvcBitrate(bitrate: Int?) {
        streamStateHolder.setAvcBitrate(bitrate ?: -1)
        viewModelScope.launch { settingsRepository.updateAvcBitrate(bitrate) }
    }

    fun updateUseScreenCapture(useScreenCapture: Boolean) {
        streamStateHolder.setUseScreenCapture(useScreenCapture)
        viewModelScope.launch { settingsRepository.updateUseScreenCapture(useScreenCapture) }
    }

    fun startHttpsServer(port: Int) {
        val intent = Intent(context, KtorHttpsServerService::class.java).apply {
            action = "com.example.handycam.ACTION_START_HTTPS_SERVER"
            putExtra("httpPort", port)
        }
        context.startForegroundService(intent)
    }

    fun stopHttpsServer() {
        context.startService(Intent(context, KtorHttpsServerService::class.java).apply {
            action = "com.example.handycam.ACTION_STOP_HTTPS_SERVER"
        })
    }

    fun switchCamera(camera: String) {
        context.startService(Intent(context, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_SET_CAMERA"
            putExtra("camera", camera)
        })
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val candidates = mutableListOf<String>()
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.") || host.startsWith("10.") || host.startsWith("172.")) return host
                        candidates.add(host)
                    }
                }
            }
            candidates.firstOrNull()
        } catch (_: Exception) { null }
    }

    sealed class UiEvent {
        data class Error(val message: String) : UiEvent()
    }
}
