package com.example.handycam.presentation.main

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.handycam.KtorHttpServerService
import com.example.handycam.StreamService
import com.example.handycam.StreamActions
import com.example.handycam.HttpServerActions
import com.example.handycam.data.model.CameraInfo
import com.example.handycam.data.model.StreamConfig
import com.example.handycam.data.preferences.PreferencesManager
import com.example.handycam.data.repository.CameraRepository
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
    private val preferences: PreferencesManager,
    private val cameraRepository: CameraRepository,
) : ViewModel() {

    val isStreaming: StateFlow<Boolean> = streamStateHolder.isStreaming
    val httpsRunning: StateFlow<Boolean> = streamStateHolder.httpsRunning

    private val _availableCameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val availableCameras: StateFlow<List<CameraInfo>> = _availableCameras.asStateFlow()

    val localIp: String get() = getLocalIpAddress() ?: "0.0.0.0"

    fun maxStreamingFps(camera: String, width: Int, height: Int, avc: Boolean): Int =
        cameraRepository.maxStreamingFps(camera, width, height, avc)

    init {
        viewModelScope.launch { _availableCameras.value = cameraRepository.getAvailableCameras() }
        viewModelScope.launch {
            val settings = preferences.appSettingsFlow.first()
            streamStateHolder.setHost(settings.host)
            streamStateHolder.setStreamingPort(settings.streamingPort)
            streamStateHolder.setCamera(settings.camera)
            streamStateHolder.setUseScreenCapture(settings.useScreenCapture)
        }
        viewModelScope.launch {
            val config = preferences.streamConfigFlow.first()
            streamStateHolder.setWidth(config.width)
            streamStateHolder.setHeight(config.height)
            streamStateHolder.setJpegQuality(config.jpegQuality)
            streamStateHolder.setFps(config.fps)
            streamStateHolder.setUseAvc(config.useAvc)
            streamStateHolder.setUseHevc(config.useHevc)
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
            val config = StreamConfig(width, height, camera, jpegQuality, fps, useAvc, avcBitrate, streamStateHolder.useHevc.value)
            try {
                preferences.updateHost(host)
                preferences.updateStreamingPort(port)
                preferences.updateCamera(camera)
                preferences.updateStreamConfig(config)
                preferences.updateStreamingState(true)
            } catch (_: Exception) {
                // Preserve the existing behavior: start the service even if saving fails.
            }

            val intent = Intent(context, StreamService::class.java).apply {
                action = StreamActions.START
                putExtra("host", host)
                putExtra("streamingPort", port)
                putExtra("width", width)
                putExtra("height", height)
                putExtra("camera", camera)
                putExtra("jpegQuality", jpegQuality)
                putExtra("targetFps", fps)
                putExtra("useAvc", useAvc)
                putExtra("useHevc", streamStateHolder.useHevc.value)
                putExtra("useHdr", streamStateHolder.useHdr.value)
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
            try {
                preferences.updateStreamingState(false)
            } catch (_: Exception) {
                // Stopping the service must not depend on saving preferences.
            }
            context.startService(Intent(context, StreamService::class.java).apply {
                action = StreamActions.STOP
            })
        }
    }

    fun updateHost(host: String) {
        streamStateHolder.setHost(host)
        viewModelScope.launch { preferences.updateHost(host) }
    }

    fun updateStreamingPort(port: Int) {
        streamStateHolder.setStreamingPort(port)
        viewModelScope.launch { preferences.updateStreamingPort(port) }
    }

    fun updateCamera(camera: String) {
        streamStateHolder.setCamera(camera)
        viewModelScope.launch { preferences.updateCamera(camera) }
    }

    fun updateResolution(width: Int, height: Int) {
        streamStateHolder.setWidth(width)
        streamStateHolder.setHeight(height)
        viewModelScope.launch { preferences.updateResolution(width, height) }
    }

    fun updateJpegQuality(quality: Int) {
        streamStateHolder.setJpegQuality(quality)
        viewModelScope.launch { preferences.updateJpegQuality(quality) }
    }

    fun updateFps(fps: Int) {
        streamStateHolder.setFps(fps)
        viewModelScope.launch { preferences.updateFps(fps) }
    }

    fun updateUseAvc(useAvc: Boolean) {
        streamStateHolder.setUseAvc(useAvc)
        viewModelScope.launch { preferences.updateUseAvc(useAvc) }
    }

    fun updateUseHevc(value: Boolean) {
        streamStateHolder.setUseHevc(value)
        viewModelScope.launch { preferences.updateUseHevc(value) }
    }

    fun updateAvcBitrate(bitrate: Int?) {
        streamStateHolder.setAvcBitrate(bitrate ?: -1)
        viewModelScope.launch { preferences.updateAvcBitrate(bitrate) }
    }

    fun updateUseScreenCapture(useScreenCapture: Boolean) {
        streamStateHolder.setUseScreenCapture(useScreenCapture)
        viewModelScope.launch { preferences.updateUseScreenCapture(useScreenCapture) }
    }

    fun startHttpsServer(port: Int) {
        val intent = Intent(context, KtorHttpServerService::class.java).apply {
            action = HttpServerActions.START
            putExtra("httpPort", port)
        }
        context.startForegroundService(intent)
    }

    fun stopHttpsServer() {
        context.startService(Intent(context, KtorHttpServerService::class.java).apply {
            action = HttpServerActions.STOP
        })
    }

    fun switchCamera(camera: String) {
        context.startService(Intent(context, StreamService::class.java).apply {
            action = StreamActions.SET_CAMERA
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

}
