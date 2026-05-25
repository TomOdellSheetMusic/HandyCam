package com.example.handycam.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable singleton replacing the old SettingsManager.
 * Holds all runtime/live state as StateFlow streams.
 * Persistent settings (host, port, etc.) live in PreferencesManager/DataStore;
 * this class carries ephemeral in-memory state shared across components.
 */
@Singleton
class StreamStateHolder @Inject constructor() {

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _httpsRunning = MutableStateFlow(false)
    val httpsRunning: StateFlow<Boolean> = _httpsRunning.asStateFlow()

    private val _camera = MutableStateFlow("back")
    val camera: StateFlow<String> = _camera.asStateFlow()

    private val _streamingPort = MutableStateFlow(4747)
    val streamingPort: StateFlow<Int> = _streamingPort.asStateFlow()

    private val _host = MutableStateFlow("0.0.0.0")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _width = MutableStateFlow(1080)
    val width: StateFlow<Int> = _width.asStateFlow()

    private val _height = MutableStateFlow(1920)
    val height: StateFlow<Int> = _height.asStateFlow()

    private val _fps = MutableStateFlow(30)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _jpegQuality = MutableStateFlow(85)
    val jpegQuality: StateFlow<Int> = _jpegQuality.asStateFlow()

    private val _useAvc = MutableStateFlow(false)
    val useAvc: StateFlow<Boolean> = _useAvc.asStateFlow()

    private val _avcBitrate = MutableStateFlow(-1)
    val avcBitrate: StateFlow<Int> = _avcBitrate.asStateFlow()

    private val _useScreenCapture = MutableStateFlow(false)
    val useScreenCapture: StateFlow<Boolean> = _useScreenCapture.asStateFlow()

    // Camera controls (observed by StreamService to apply live changes)
    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()

    private val _autoFocus = MutableStateFlow(true)
    val autoFocus: StateFlow<Boolean> = _autoFocus.asStateFlow()

    private val _exposure = MutableStateFlow(0)
    val exposure: StateFlow<Int> = _exposure.asStateFlow()

    private val _focus = MutableStateFlow(0)
    val focus: StateFlow<Int> = _focus.asStateFlow()

    private val _zoom = MutableStateFlow(0f)
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    private val _whiteBalance = MutableStateFlow(1)
    val whiteBalance: StateFlow<Int> = _whiteBalance.asStateFlow()

    private val _whiteBalanceLocked = MutableStateFlow(false)
    val whiteBalanceLocked: StateFlow<Boolean> = _whiteBalanceLocked.asStateFlow()

    private val _autoExposure = MutableStateFlow(true)
    val autoExposure: StateFlow<Boolean> = _autoExposure.asStateFlow()

    private val _iso = MutableStateFlow(0)
    val iso: StateFlow<Int> = _iso.asStateFlow()

    private val _isoLocked = MutableStateFlow(false)
    val isoLocked: StateFlow<Boolean> = _isoLocked.asStateFlow()

    private val _shutterSpeedNs = MutableStateFlow(0L)
    val shutterSpeedNs: StateFlow<Long> = _shutterSpeedNs.asStateFlow()

    private val _shutterLocked = MutableStateFlow(false)
    val shutterLocked: StateFlow<Boolean> = _shutterLocked.asStateFlow()

    private val _zoomLocked = MutableStateFlow(false)
    val zoomLocked: StateFlow<Boolean> = _zoomLocked.asStateFlow()

    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled.asStateFlow()

    // Setters
    fun setStreaming(value: Boolean) { _isStreaming.value = value }
    fun setHttpsRunning(value: Boolean) { _httpsRunning.value = value }
    fun setCamera(value: String) { _camera.value = value }
    fun setStreamingPort(value: Int) { _streamingPort.value = value }
    fun setHost(value: String) { _host.value = value }
    fun setWidth(value: Int) { _width.value = value }
    fun setHeight(value: Int) { _height.value = value }
    fun setFps(value: Int) { _fps.value = value }
    fun setJpegQuality(value: Int) { _jpegQuality.value = value }
    fun setUseAvc(value: Boolean) { _useAvc.value = value }
    fun setAvcBitrate(value: Int) { _avcBitrate.value = value }
    fun setUseScreenCapture(value: Boolean) { _useScreenCapture.value = value }
    fun setTorchEnabled(value: Boolean) { _torchEnabled.value = value }
    fun setAutoFocus(value: Boolean) { _autoFocus.value = value }
    fun setExposure(value: Int) { _exposure.value = value }
    fun setFocus(value: Int) { _focus.value = value.coerceIn(0, 100) }
    fun setZoom(value: Float) { _zoom.value = value.coerceIn(0f, 1f) }
    fun setWhiteBalance(value: Int) { _whiteBalance.value = value }
    fun setWhiteBalanceLocked(value: Boolean) { _whiteBalanceLocked.value = value }
    fun setAutoExposure(value: Boolean) { _autoExposure.value = value }
    fun setIso(value: Int) { _iso.value = value.coerceAtLeast(0) }
    fun setIsoLocked(value: Boolean) { _isoLocked.value = value }
    fun setShutterSpeedNs(value: Long) { _shutterSpeedNs.value = value.coerceAtLeast(0L) }
    fun setShutterLocked(value: Boolean) { _shutterLocked.value = value }
    fun setZoomLocked(value: Boolean) { _zoomLocked.value = value }
    fun setGridEnabled(value: Boolean) { _gridEnabled.value = value }
}
