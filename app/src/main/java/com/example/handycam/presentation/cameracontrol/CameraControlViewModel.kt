package com.example.handycam.presentation.cameracontrol

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.handycam.StreamService
import com.example.handycam.data.model.CameraInfo
import com.example.handycam.domain.usecase.GetAvailableCamerasUseCase
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
    private val getAvailableCamerasUseCase: GetAvailableCamerasUseCase,
) : ViewModel() {

    val isStreaming: StateFlow<Boolean> = streamStateHolder.isStreaming
    val torchEnabled: StateFlow<Boolean> = streamStateHolder.torchEnabled
    val exposure: StateFlow<Int> = streamStateHolder.exposure
    val zoom: StateFlow<Float> = streamStateHolder.zoom
    val whiteBalance: StateFlow<Int> = streamStateHolder.whiteBalance
    val useAvc: StateFlow<Boolean> = streamStateHolder.useAvc

    private val _availableCameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val availableCameras: StateFlow<List<CameraInfo>> = _availableCameras.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    init {
        viewModelScope.launch { _availableCameras.value = getAvailableCamerasUseCase() }
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
        val clamped = linearZoom.coerceIn(0f, 1f)
        streamStateHolder.setZoom(clamped)
        try { cameraStateHolder.cameraControl?.setLinearZoom(clamped) } catch (_: Exception) {}
    }

    fun setWhiteBalance(awbMode: Int) {
        streamStateHolder.setWhiteBalance(awbMode)
    }

    fun tapToFocus(meteringAction: androidx.camera.core.FocusMeteringAction) {
        try { cameraStateHolder.cameraControl?.startFocusAndMetering(meteringAction) } catch (_: Exception) {}
    }

    fun switchCamera(cameraId: String) {
        streamStateHolder.setCamera(cameraId)
        context.startService(Intent(context, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_SET_CAMERA"
            putExtra("camera", cameraId)
        })
    }

    fun setPreviewSurfaceProvider(provider: androidx.camera.core.Preview.SurfaceProvider?) {
        cameraStateHolder.previewSurfaceProvider = provider
        context.startService(Intent(context, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_SET_PREVIEW_SURFACE"
            putExtra("surfaceToken", if (provider != null) "camerax_preview" else null as String?)
        })
    }

    /** Used in AVC mode: passes the raw SurfaceView Surface directly to the Camera2 session. */
    fun setPreviewSurface(surface: android.view.Surface?) {
        cameraStateHolder.previewSurface = surface
        context.startService(Intent(context, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_SET_PREVIEW_SURFACE"
            putExtra("surfaceToken", if (surface != null) "camera2_preview" else null as String?)
        })
    }

    sealed class UiEvent {
        data class Error(val message: String) : UiEvent()
    }
}
