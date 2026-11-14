package com.example.handycam.presentation.cameracontrol

import android.content.Context
import android.content.Intent
import androidx.camera.core.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.handycam.StreamService
import com.example.handycam.StreamActions
import com.example.handycam.data.model.CameraInfo
import com.example.handycam.data.repository.CameraRepository
import com.example.handycam.service.CameraStateHolder
import com.example.handycam.service.StreamStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraControlViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val streamStateHolder: StreamStateHolder,
    val cameraStateHolder: CameraStateHolder,
    private val cameraRepository: CameraRepository,
) : ViewModel() {

    @Volatile
    private var previewProviderToken: Long = 0L

    val isStreaming: StateFlow<Boolean> = streamStateHolder.isStreaming
    val torchEnabled: StateFlow<Boolean> = streamStateHolder.torchEnabled
    val exposure: StateFlow<Int> = streamStateHolder.exposure
    val zoom: StateFlow<Float> = streamStateHolder.zoom
    val whiteBalance: StateFlow<Int> = streamStateHolder.whiteBalance
    val whiteBalanceLocked: StateFlow<Boolean> = streamStateHolder.whiteBalanceLocked
    val autoFocus: StateFlow<Boolean> = streamStateHolder.autoFocus
    val autoExposure: StateFlow<Boolean> = streamStateHolder.autoExposure
    val iso: StateFlow<Int> = streamStateHolder.iso
    val isoLocked: StateFlow<Boolean> = streamStateHolder.isoLocked
    val shutterSpeedNs: StateFlow<Long> = streamStateHolder.shutterSpeedNs
    val shutterLocked: StateFlow<Boolean> = streamStateHolder.shutterLocked
    val zoomLocked: StateFlow<Boolean> = streamStateHolder.zoomLocked
    val gridEnabled: StateFlow<Boolean> = streamStateHolder.gridEnabled
    val useAvc: StateFlow<Boolean> = streamStateHolder.useAvc
    val encoderWidth: StateFlow<Int> = cameraStateHolder.encoderWidth
    val encoderHeight: StateFlow<Int> = cameraStateHolder.encoderHeight
    val sensorOrientation: StateFlow<Int> = cameraStateHolder.sensorOrientation
    val frontFacing: StateFlow<Boolean> = cameraStateHolder.frontFacing
    val exposureMin: StateFlow<Int> = cameraStateHolder.exposureMin
    val exposureMax: StateFlow<Int> = cameraStateHolder.exposureMax
    val exposureStep: StateFlow<Float> = cameraStateHolder.exposureStep

    private val _availableCameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val availableCameras: StateFlow<List<CameraInfo>> = _availableCameras.asStateFlow()

    init {
        viewModelScope.launch { _availableCameras.value = cameraRepository.getAvailableCameras() }
    }

    fun toggleTorch() {
        val newState = !streamStateHolder.torchEnabled.value
        streamStateHolder.setTorchEnabled(newState)
        try {
            cameraStateHolder.cameraControl?.enableTorch(newState)
        } catch (_: Exception) {}
    }

    fun setExposure(value: Int) {
        streamStateHolder.setExposure(value)
        try {
            val range = cameraStateHolder.cameraInfo?.exposureState?.exposureCompensationRange
            val clamped = if (range != null) value.coerceIn(range.lower, range.upper) else value
            cameraStateHolder.cameraControl?.setExposureCompensationIndex(clamped)
        } catch (_: Exception) {}
    }

    fun setZoom(linearZoom: Float) {
        if (streamStateHolder.zoomLocked.value) return
        val clamped = linearZoom.coerceIn(0f, 1f)
        streamStateHolder.setZoom(clamped)
        try { cameraStateHolder.cameraControl?.setLinearZoom(clamped) } catch (_: Exception) {}
    }

    fun setWhiteBalance(awbMode: Int) {
        if (streamStateHolder.whiteBalanceLocked.value) return
        streamStateHolder.setWhiteBalance(awbMode)
    }

    fun setWhiteBalanceLocked(locked: Boolean) {
        streamStateHolder.setWhiteBalanceLocked(locked)
    }

    fun setAutoFocus(enabled: Boolean) {
        streamStateHolder.setAutoFocus(enabled)
    }

    fun setAutoExposure(enabled: Boolean) {
        streamStateHolder.setAutoExposure(enabled)
    }

    fun setIso(value: Int) {
        if (streamStateHolder.isoLocked.value) return
        streamStateHolder.setIso(value)
    }

    fun setIsoLocked(locked: Boolean) {
        streamStateHolder.setIsoLocked(locked)
    }

    fun setShutterSpeedNs(value: Long) {
        if (streamStateHolder.shutterLocked.value) return
        streamStateHolder.setShutterSpeedNs(value)
    }

    fun setShutterLocked(locked: Boolean) {
        streamStateHolder.setShutterLocked(locked)
    }

    fun setZoomLocked(locked: Boolean) {
        streamStateHolder.setZoomLocked(locked)
    }

    fun setGridEnabled(enabled: Boolean) {
        streamStateHolder.setGridEnabled(enabled)
    }

    fun tapToFocus(meteringAction: androidx.camera.core.FocusMeteringAction) {
        try { cameraStateHolder.cameraControl?.startFocusAndMetering(meteringAction) } catch (_: Exception) {}
    }

    fun tapToFocus(normalizedX: Float, normalizedY: Float) {
        if (!streamStateHolder.useAvc.value) return
        context.startService(Intent(context, StreamService::class.java).apply {
            action = StreamActions.SET_FOCUS_POINT
            putExtra("x", normalizedX)
            putExtra("y", normalizedY)
        })
    }

    fun switchCamera(cameraId: String) {
        streamStateHolder.setCamera(cameraId)
        context.startService(Intent(context, StreamService::class.java).apply {
            action = StreamActions.SET_CAMERA
            putExtra("camera", cameraId)
        })
    }

    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?): Long {
        if (provider != null && cameraStateHolder.previewSurfaceProvider === provider) {
            return previewProviderToken
        }
        val token = if (provider != null) {
            previewProviderToken += 1
            previewProviderToken
        } else {
            previewProviderToken
        }
        cameraStateHolder.previewSurfaceProvider = provider
        context.startService(Intent(context, StreamService::class.java).apply {
            action = StreamActions.SET_PREVIEW_SURFACE
            putExtra("surfaceToken", if (provider != null) "camerax_preview" else null as String?)
        })
        return token
    }

    fun clearPreviewSurfaceProvider(expected: Preview.SurfaceProvider?, token: Long) {
        val matches = cameraStateHolder.previewSurfaceProvider === expected
        val tokenMatches = token == previewProviderToken
        if (!matches || !tokenMatches) return
        cameraStateHolder.previewSurfaceProvider = null
        context.startService(Intent(context, StreamService::class.java).apply {
            action = StreamActions.SET_PREVIEW_SURFACE
            putExtra("surfaceToken", null as String?)
        })
    }

    /** Used in AVC mode: passes the raw SurfaceView Surface directly to the Camera2 session. */
    fun setPreviewSurface(surface: android.view.Surface?) {
        cameraStateHolder.previewSurface = surface
        context.startService(Intent(context, StreamService::class.java).apply {
            action = StreamActions.SET_PREVIEW_SURFACE
            putExtra("surfaceToken", if (surface != null) "camera2_preview" else null as String?)
        })
    }

}
