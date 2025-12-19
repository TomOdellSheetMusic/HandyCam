package com.example.handycam

import android.content.Context
import android.os.Looper
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

    private val _httpsRunning = MutableLiveData<Boolean>(false)
    val httpsRunning: LiveData<Boolean> get() = _httpsRunning
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

    // AVC bitrate (kbps) for encoder when using AVC path
    private val _avcBitrate = MutableLiveData<Int>(-1)
    val avcBitrate: LiveData<Int> get() = _avcBitrate

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
    
    // Manual focus (0-100)
    private val _focus = MutableLiveData<Int>(0)
    val focus: LiveData<Int> get() = _focus
    
    // Auto exposure toggle
    private val _autoExposure = MutableLiveData<Boolean>(true)
    val autoExposure: LiveData<Boolean> get() = _autoExposure

    // Update methods
    fun setStreaming(value: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) _isStreaming.value = value else _isStreaming.postValue(value)
    }

    fun setCamera(value: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) _camera.value = value else _camera.postValue(value)
    }

    fun setPort(value: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) _port.value = value else _port.postValue(value)
    }

    fun setWidth(value: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) _width.value = value else _width.postValue(value)
    }

    fun setHeight(value: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) _height.value = value else _height.postValue(value)
    }

    fun setFps(value: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) _fps.value = value else _fps.postValue(value)
    }

    fun setJpegQuality(value: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) _jpegQuality.value = value else _jpegQuality.postValue(value)
    }

    fun setUseAvc(value: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) _useAvc.value = value else _useAvc.postValue(value)
    }

    fun setAvcBitrate(value: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) _avcBitrate.value = value else _avcBitrate.postValue(value)
    }

    fun setHost(value: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) _host.value = value else _host.postValue(value)
    }

    fun setTorchEnabled(value: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) _torchEnabled.value = value else _torchEnabled.postValue(value)
    }

    fun setAutoFocus(value: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) _autoFocus.value = value else _autoFocus.postValue(value)
    }

    fun setExposure(value: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) _exposure.value = value else _exposure.postValue(value)
    }

    fun setFocus(value: Int) {
        val v = value.coerceIn(0, 100)
        if (Looper.myLooper() == Looper.getMainLooper()) _focus.value = v else _focus.postValue(v)
    }

    fun setAutoExposure(value: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) _autoExposure.value = value else _autoExposure.postValue(value)
    }
}