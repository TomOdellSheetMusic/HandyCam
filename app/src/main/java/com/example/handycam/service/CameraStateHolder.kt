package com.example.handycam.service

import android.view.Surface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable singleton replacing the old SharedSurfaceProvider object.
 * Bridges the camera state (CameraX handles + preview surfaces) between
 * StreamService (which owns the camera) and CameraControlActivity (which shows the preview).
 */
@Singleton
class CameraStateHolder @Inject constructor() {

    /** Set by StreamService (AVC path) when a preview Surface is needed. */
    @Volatile
    var previewSurface: Surface? = null

    /** Set by CameraControlActivity so StreamService can attach a preview to CameraX. */
    @Volatile
    var previewSurfaceProvider: Preview.SurfaceProvider? = null

    /** Set by StreamService once camera is bound (CameraX or Camera2). */
    @Volatile
    var cameraControl: CameraControl? = null

    /** Set by StreamService once camera is bound (CameraX). */
    @Volatile
    var cameraInfo: CameraInfo? = null

    private val _encoderWidth = MutableStateFlow(0)
    val encoderWidth: StateFlow<Int> = _encoderWidth

    private val _encoderHeight = MutableStateFlow(0)
    val encoderHeight: StateFlow<Int> = _encoderHeight

    private val _sensorOrientation = MutableStateFlow(0)
    val sensorOrientation: StateFlow<Int> = _sensorOrientation
    private val _frontFacing = MutableStateFlow(false)
    val frontFacing: StateFlow<Boolean> = _frontFacing

    fun setCameraOrientation(sensorOrientation: Int, frontFacing: Boolean) {
        _sensorOrientation.value = sensorOrientation
        _frontFacing.value = frontFacing
    }

    private val _exposureMin = MutableStateFlow(0)
    val exposureMin: StateFlow<Int> = _exposureMin

    private val _exposureMax = MutableStateFlow(0)
    val exposureMax: StateFlow<Int> = _exposureMax

    private val _exposureStep = MutableStateFlow(0f)
    val exposureStep: StateFlow<Float> = _exposureStep

    fun setEncoderSize(width: Int, height: Int) {
        _encoderWidth.value = width
        _encoderHeight.value = height
    }

    fun setExposureRange(min: Int, max: Int, step: Float = 0f) {
        _exposureMin.value = min
        _exposureMax.value = max
        _exposureStep.value = step
    }

    fun clear() {
        previewSurface = null
        previewSurfaceProvider = null
        cameraControl = null
        cameraInfo = null
        _encoderWidth.value = 0
        _encoderHeight.value = 0
        _sensorOrientation.value = 0
        _frontFacing.value = false
        _exposureMin.value = 0
        _exposureMax.value = 0
        _exposureStep.value = 0f
    }
}
