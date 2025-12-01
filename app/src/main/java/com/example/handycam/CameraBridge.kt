package com.example.handycam

import androidx.camera.core.Camera
import androidx.camera.view.PreviewView
import android.view.Surface

object CameraBridge {
    // set by MainActivity when preview is active
    @Volatile
    var camera: Camera? = null

    @Volatile
    var previewView: PreviewView? = null

    // expose encoder input surface if service creates one (not used in this simple approach)
    @Volatile
    var encoderSurface: Surface? = null
}
