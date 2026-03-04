package com.example.handycam.service

import android.view.Surface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview
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

    fun clear() {
        previewSurface = null
        previewSurfaceProvider = null
        cameraControl = null
        cameraInfo = null
    }
}
