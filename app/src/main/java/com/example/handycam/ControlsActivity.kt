package com.example.handycam

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "ControlsActivity"

class ControlsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controls)

        val zoomSeek = findViewById<SeekBar>(R.id.zoomSeek)
        val zoomLabel = findViewById<TextView>(R.id.zoomLabel)
        val exposureSeek = findViewById<SeekBar>(R.id.exposureSeek)
        val exposureLabel = findViewById<TextView>(R.id.exposureLabel)
        val focusBtn = findViewById<Button>(R.id.focusCenterButton)

        val camera = CameraBridge.camera
        val previewView = CameraBridge.previewView

        if (camera == null) {
            zoomLabel.text = "Camera not available"
            return
        }

        // Zoom: map 0..100 to available zoom ratio
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val zs = camera.cameraInfo.zoomState.value
                val min = zs?.minZoomRatio ?: 1f
                val max = zs?.maxZoomRatio ?: 4f
                zoomLabel.text = "Zoom (min=${"%.2f".format(min)} max=${"%.2f".format(max)})"
                zoomSeek.progress = ((zs?.zoomRatio ?: 1f - min) / (max - min) * 100).toInt().coerceIn(0,100)

                zoomSeek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val ratio = min + (max - min) * (progress / 100.0f)
                        camera.cameraControl.setZoomRatio(ratio)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            } catch (e: Exception) {
                Log.w(TAG, "Zoom init failed", e)
            }
        }

        // Exposure compensation mapping if available
        try {
            val expState = camera.cameraInfo.exposureState
            val range = expState.exposureCompensationRange
            val steps = range.upper - range.lower
            exposureLabel.text = "Exposure (min=${range.lower} max=${range.upper})"
            exposureSeek.max = if (steps > 0) steps else 1
            exposureSeek.progress = expState.exposureCompensationIndex - range.lower
            exposureSeek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val idx = range.lower + progress
                    camera.cameraControl.setExposureCompensationIndex(idx)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        } catch (e: Exception) {
            Log.w(TAG, "Exposure controls unavailable", e)
            exposureLabel.text = "Exposure not supported"
        }

        focusBtn.setOnClickListener {
            try {
                val pv = previewView
                if (pv != null) {
                    val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(pv.width.toFloat(), pv.height.toFloat())
                    val point = factory.createPoint(pv.width / 2f, pv.height / 2f)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
                    camera.cameraControl.startFocusAndMetering(action)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Focus center failed", e)
            }
        }
    }
}
