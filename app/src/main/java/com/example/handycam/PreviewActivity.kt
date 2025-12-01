package com.example.handycam

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private const val TAG = "PreviewActivity"

class PreviewActivity : ComponentActivity() {
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: androidx.camera.core.Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // lock to landscape for preview
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContentView(R.layout.activity_preview)

        // Enter immersive fullscreen (hide system navigation and status bars)
        try {
            val decorView = window.decorView
            val insetsController = WindowCompat.getInsetsController(window, decorView)
            insetsController?.let {
                it.hide(WindowInsetsCompat.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to set immersive mode", e)
        }

        val previewView = findViewById<PreviewView>(R.id.previewViewFull)
        val stopBtn = findViewById<Button>(R.id.stopPreviewButton)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, selector, preview)
                boundCamera = camera
                CameraBridge.camera = camera
                CameraBridge.previewView = previewView
                setupControlsForCamera(camera)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind preview", e)
            }
        }, ContextCompat.getMainExecutor(this))

        stopBtn.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        CameraBridge.camera = null
        CameraBridge.previewView = null
    }

    private fun setupControlsForCamera(camera: androidx.camera.core.Camera) {
        val zoomSeek = findViewById<android.widget.SeekBar>(R.id.zoomSeek)
        val exposureSeek = findViewById<android.widget.SeekBar>(R.id.exposureSeek)
        val focusBtn = findViewById<android.widget.Button>(R.id.focusCenterButton)
        val toggleBtn = findViewById<android.widget.ImageButton>(R.id.toggleControlsButton)
        val overlay = findViewById<android.view.View>(R.id.previewControlsOverlay)
        val stopBtn = findViewById<android.widget.Button>(R.id.stopPreviewButton)

        // toggle overlay
        toggleBtn.setOnClickListener {
            overlay.visibility = if (overlay.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
        }

        stopBtn.setOnClickListener {
            finish()
        }

        // Zoom
        try {
            val zs = camera.cameraInfo.zoomState.value
            val min = zs?.minZoomRatio ?: 1f
            val max = zs?.maxZoomRatio ?: 4f
            zoomSeek.progress = ((zs?.zoomRatio ?: 1f - min) / (max - min) * 100).toInt().coerceIn(0,100)
            zoomSeek.setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val ratio = min + (max - min) * (progress / 100.0f)
                    camera.cameraControl.setZoomRatio(ratio)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        } catch (e: Exception) {
            Log.w(TAG, "Zoom init failed", e)
            zoomSeek.isEnabled = false
        }

        // Exposure compensation
        try {
            val expState = camera.cameraInfo.exposureState
            val range = expState.exposureCompensationRange
            val steps = range.upper - range.lower
            exposureSeek.max = if (steps > 0) steps else 1
            exposureSeek.progress = expState.exposureCompensationIndex - range.lower
            exposureSeek.setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val idx = range.lower + progress
                    camera.cameraControl.setExposureCompensationIndex(idx)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        } catch (e: Exception) {
            Log.w(TAG, "Exposure controls unavailable", e)
            exposureSeek.isEnabled = false
        }

        focusBtn.setOnClickListener {
            try {
                val pv = CameraBridge.previewView
                if (pv != null) {
                    val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(pv.width.toFloat(), pv.height.toFloat())
                    val point = factory.createPoint(pv.width / 2f, pv.height / 2f)
                    val action = androidx.camera.core.FocusMeteringAction.Builder(point, androidx.camera.core.FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
                    camera.cameraControl.startFocusAndMetering(action)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Focus center failed", e)
            }
        }
    }
}
