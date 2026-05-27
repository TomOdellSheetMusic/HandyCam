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

    fun setEncoderSize(width: Int, height: Int) {
        _encoderWidth.value = width
        _encoderHeight.value = height
    }

    fun clear() {
        previewSurface = null
        previewSurfaceProvider = null
        cameraControl = null
        cameraInfo = null
        _encoderWidth.value = 0
        _encoderHeight.value = 0
    }
}
