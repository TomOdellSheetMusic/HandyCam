package com.example.handycam.presentation.cameracontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.handycam.data.model.AppSettings
import com.example.handycam.data.model.CameraInfo
import com.example.handycam.data.repository.SettingsRepository
import com.example.handycam.domain.usecase.GetAvailableCamerasUseCase
import com.example.handycam.domain.usecase.SwitchCameraUseCase
import com.example.handycam.domain.usecase.ToggleTorchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for CameraControlActivity.
 * Manages camera control UI state and operations.
 */
@HiltViewModel
class CameraControlViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val getAvailableCamerasUseCase: GetAvailableCamerasUseCase,
    private val switchCameraUseCase: SwitchCameraUseCase,
    private val toggleTorchUseCase: ToggleTorchUseCase
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _availableCameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val availableCameras: StateFlow<List<CameraInfo>> = _availableCameras.asStateFlow()

    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()

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

    fun switchCamera(camera: String) {
        viewModelScope.launch {
            val result = switchCameraUseCase(camera)
            if (result.isFailure) {
                _uiEvent.emit(UiEvent.Error(result.exceptionOrNull()?.message ?: "Failed to switch camera"))
            }
        }
    }

    fun toggleTorch(cameraId: String) {
        viewModelScope.launch {
            val newState = !_torchEnabled.value
            val result = toggleTorchUseCase(cameraId, newState)
            if (result.isSuccess) {
                _torchEnabled.value = newState
            } else {
                _uiEvent.emit(UiEvent.Error("Failed to toggle torch"))
            }
        }
    }

    sealed class UiEvent {
        data class Error(val message: String) : UiEvent()
    }
}
