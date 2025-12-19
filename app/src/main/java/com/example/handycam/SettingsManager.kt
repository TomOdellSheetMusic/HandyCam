package com.example.handycam

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class SettingsManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Streaming status
    private val _isStreaming = MutableLiveData<Boolean>(false)
    val isStreaming: LiveData<Boolean> get() = _isStreaming

    // Camera selection
    private val _camera = MutableLiveData<String>("back")
    val camera: LiveData<String> get() = _camera

    // Stream settings
    private val _port = MutableLiveData<Int>(4747)
    val port: LiveData<Int> get() = _port

    private val _width = MutableLiveData<Int>(1080)
    val width: LiveData<Int> get() = _width

    private val _height = MutableLiveData<Int>(1920)
    val height: LiveData<Int> get() = _height

    private val _fps = MutableLiveData<Int>(30)
    val fps: LiveData<Int> get() = _fps

    private val _jpegQuality = MutableLiveData<Int>(85)
    val jpegQuality: LiveData<Int> get() = _jpegQuality

    private val _useAvc = MutableLiveData<Boolean>(false)
    val useAvc: LiveData<Boolean> get() = _useAvc

    // Host/IP settings
    private val _host = MutableLiveData<String>("0.0.0.0")
    val host: LiveData<String> get() = _host

    // Torch/Flash settings
    private val _torchEnabled = MutableLiveData<Boolean>(false)
    val torchEnabled: LiveData<Boolean> get() = _torchEnabled

    // Focus settings
    private val _autoFocus = MutableLiveData<Boolean>(true)
    val autoFocus: LiveData<Boolean> get() = _autoFocus

    // Exposure settings
    private val _exposure = MutableLiveData<Int>(0)
    val exposure: LiveData<Int> get() = _exposure

    // Update methods
    fun setStreaming(value: Boolean) {
        _isStreaming.value = value
    }

    fun setCamera(value: String) {
        _camera.value = value
    }

    fun setPort(value: Int) {
        _port.value = value
    }

    fun setWidth(value: Int) {
        _width.value = value
    }

    fun setHeight(value: Int) {
        _height.value = value
    }

    fun setFps(value: Int) {
        _fps.value = value
    }

    fun setJpegQuality(value: Int) {
        _jpegQuality.value = value
    }

    fun setUseAvc(value: Boolean) {
        _useAvc.value = value
    }

    fun setHost(value: String) {
        _host.value = value
    }

    fun setTorchEnabled(value: Boolean) {
        _torchEnabled.value = value
    }

    fun setAutoFocus(value: Boolean) {
        _autoFocus.value = value
    }

    fun setExposure(value: Int) {
        _exposure.value = value
    }
}