package com.example.handycam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.camera.view.PreviewView
import java.util.concurrent.TimeUnit

private const val TAG = "CameraControlActivity"

// Camera2 AWB mode constants (mirrors CaptureRequest.CONTROL_AWB_MODE_*)
private const val AWB_AUTO = 1
private const val AWB_DAYLIGHT = 2
private const val AWB_INCANDESCENT = 3
private const val AWB_FLUORESCENT = 4
private const val AWB_CLOUDY = 8
private const val AWB_SHADE = 9

class CameraControlActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var flashBtn: ImageButton
    private lateinit var cameraSpinner: Spinner
    private lateinit var exposureLabel: TextView
    private lateinit var focusRing: View
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var zoomLabel: TextView
    private var cameraManager: android.hardware.camera2.CameraManager? = null

    private var cameraControl: androidx.camera.core.CameraControl? = null
    private var cameraInfo: androidx.camera.core.CameraInfo? = null

    private var adjustingExposure = false
    private var exposureStartY = 0f
    private var lastExposureIndex = 0
    private var currentLinearZoom = 0f

    private var cameraReadyReceiver: BroadcastReceiver? = null
    private var useCameraX = false
    private lateinit var settingsManager: SettingsManager
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) setupPreview() else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_control)

        settingsManager = SettingsManager.getInstance(this)

        settingsManager.isStreaming.observe(this) { streaming ->
            if (!streaming) finish()
        }
        settingsManager.exposure.observe(this) { exposure ->
            exposureLabel.text = "EV: $exposure"
        }

        previewView = findViewById(R.id.previewView)
        flashBtn = findViewById(R.id.flashBtn)
        cameraSpinner = findViewById(R.id.cameraSpinner)
        exposureLabel = findViewById(R.id.exposureLabel)
        focusRing = findViewById(R.id.focusRing)
        zoomSeekBar = findViewById(R.id.zoomSeekBar)
        zoomLabel = findViewById(R.id.zoomLabel)

        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        } catch (_: Exception) {}

        val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
        val isStreaming = prefs.getBoolean("isStreaming", false)
        useCameraX = !prefs.getBoolean("useAvc", false)

        if (!isStreaming) {
            Toast.makeText(this, "Please start streaming first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupFlashButton()
        setupCameraSpinner()
        setupZoomControls()
        setupTouchGestures()

        cameraReadyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                runOnUiThread { updateCameraControls() }
            }
        }
        registerReceiver(cameraReadyReceiver, IntentFilter("com.example.handycam.CAMERA_READY"), Context.RECEIVER_NOT_EXPORTED)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else setupPreview()
    }

    private fun setupFlashButton() {
        flashBtn.setOnClickListener {
            val ctrl = SharedSurfaceProvider.cameraControl
            val mgr = cameraManager
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    val info = SharedSurfaceProvider.cameraInfo
                    val enable = info?.torchState?.value != androidx.camera.core.TorchState.ON
                    if (ctrl != null) {
                        ctrl.enableTorch(enable)
                    } else {
                        val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
                        val prefCam = prefs.getString("camera", null)
                        val camId = try {
                            if (prefCam != null && mgr?.cameraIdList?.contains(prefCam) == true) prefCam
                            else mgr?.cameraIdList?.getOrNull(cameraSpinner.selectedItemPosition)
                        } catch (_: Exception) { null }
                        if (camId != null) mgr?.setTorchMode(camId, enable)
                    }
                    settingsManager.setTorchEnabled(enable)
                    updateFlashButton(enable)
                } catch (e: Exception) {
                    Log.w(TAG, "Torch toggle failed", e)
                    Toast.makeText(this@CameraControlActivity, "Torch toggle failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupCameraSpinner() {
        try {
            val mgr = cameraManager ?: return
            val ids = mgr.cameraIdList.toList()
            val labels = ids.map { id ->
                try {
                    val chars = mgr.getCameraCharacteristics(id)
                    val facing = when (chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "back"
                        else -> "unknown"
                    }
                    "$id ($facing)"
                } catch (_: Exception) { id }
            }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            cameraSpinner.adapter = adapter
            cameraSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                    val camId = try { mgr.cameraIdList[position] } catch (_: Exception) { null } ?: return
                    startService(Intent(this@CameraControlActivity, StreamService::class.java).apply {
                        action = "com.example.handycam.ACTION_SET_CAMERA"
                        putExtra("camera", camId)
                    })
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            })
        } catch (_: Exception) {}
    }

    private fun setupZoomControls() {
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) applyZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val info = SharedSurfaceProvider.cameraInfo
                val maxZoom = info?.zoomState?.value?.maxZoomRatio ?: 8f
                val minZoom = info?.zoomState?.value?.minZoomRatio ?: 1f
                val currentZoom = minZoom + (maxZoom - minZoom) * currentLinearZoom
                val newZoom = (currentZoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                val linear = ((newZoom - minZoom) / (maxZoom - minZoom)).coerceIn(0f, 1f)
                applyZoom(linear)
                return true
            }
        })
    }

    private fun applyZoom(linearZoom: Float) {
        currentLinearZoom = linearZoom
        settingsManager.setZoom(linearZoom)
        val info = SharedSurfaceProvider.cameraInfo
        val zoomRatio = info?.zoomState?.value?.let { state ->
            state.minZoomRatio + (state.maxZoomRatio - state.minZoomRatio) * linearZoom
        } ?: (1f + 7f * linearZoom)
        zoomLabel.text = String.format("%.1fx", zoomRatio)
    }


    private fun setupTouchGestures() {
        val gd = object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                performTapToFocus(e.x, e.y)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                adjustingExposure = true
                exposureStartY = e.y
                lastExposureIndex = SharedSurfaceProvider.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
                exposureLabel.visibility = View.VISIBLE
            }
        }
        val gestureDetector = android.view.GestureDetector(this, gd)

        previewView.setOnTouchListener { _, motion ->
            scaleGestureDetector.onTouchEvent(motion)
            if (!scaleGestureDetector.isInProgress) {
                gestureDetector.onTouchEvent(motion)
            }
            when (motion.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (adjustingExposure && !scaleGestureDetector.isInProgress) {
                        val dy = exposureStartY - motion.y
                        val height = previewView.height.coerceAtLeast(1)
                        val range = SharedSurfaceProvider.cameraInfo?.exposureState?.exposureCompensationRange
                        if (range != null) {
                            val span = range.upper - range.lower
                            if (span > 0) {
                                val deltaIndex = ((dy / height) * span).toInt()
                                val target = (lastExposureIndex + deltaIndex).coerceIn(range.lower, range.upper)
                                try {
                                    SharedSurfaceProvider.cameraControl?.setExposureCompensationIndex(target)
                                    exposureLabel.text = "EV: $target"
                                    settingsManager.setExposure(target)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed setting exposure index", e)
                                }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (adjustingExposure) {
                        adjustingExposure = false
                        exposureLabel.postDelayed({ exposureLabel.visibility = View.INVISIBLE }, 800)
                    }
                }
            }
            true
        }
    }

    private fun setupPreview() {
        SharedSurfaceProvider.previewSurfaceProvider = previewView.surfaceProvider
        startService(Intent(this, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_SET_PREVIEW_SURFACE"
            putExtra("surfaceToken", if (useCameraX) "camerax_preview" else "camera2_preview")
        })
        updateCameraControls()
    }

    private fun updateCameraControls() {
        SharedSurfaceProvider.cameraInfo?.let { info ->
            val torchOn = info.torchState.value == androidx.camera.core.TorchState.ON
            updateFlashButton(torchOn)
            val ev = info.exposureState.exposureCompensationIndex
            exposureLabel.text = "EV: $ev"
            info.zoomState.value?.let { zoomState ->
                val linear = ((zoomState.zoomRatio - zoomState.minZoomRatio) /
                        (zoomState.maxZoomRatio - zoomState.minZoomRatio)).coerceIn(0f, 1f)
                currentLinearZoom = linear
                zoomSeekBar.progress = (linear * 100).toInt()
                zoomLabel.text = String.format("%.1fx", zoomState.zoomRatio)
            }
        } ?: run {
            try {
                val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
                val prefCam = prefs.getString("camera", null)
                val mgr = cameraManager
                val camId = try {
                    if (prefCam != null && mgr?.cameraIdList?.contains(prefCam) == true) prefCam
                    else mgr?.cameraIdList?.getOrNull(cameraSpinner.selectedItemPosition)
                } catch (_: Exception) { null }
                if (camId != null) {
                    val chars = mgr?.getCameraCharacteristics(camId)
                    val hasFlash = chars?.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    flashBtn.setImageResource(R.drawable.ic_flash)
                    flashBtn.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    flashBtn.alpha = if (hasFlash) 1.0f else 0.4f
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateFlashButton(enabled: Boolean) {
        try {
            flashBtn.setImageResource(R.drawable.ic_flash)
            val color = if (enabled) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
            flashBtn.imageTintList = android.content.res.ColorStateList.valueOf(color)
        } catch (_: Exception) {}
    }

    private fun performTapToFocus(x: Float, y: Float) {
        try {
            val factory: MeteringPointFactory = previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3_000, TimeUnit.MILLISECONDS)
                .build()
            SharedSurfaceProvider.cameraControl?.startFocusAndMetering(action)
            val ring = focusRing
            val halfW = ring.width / 2f
            val halfH = ring.height / 2f
            val location = IntArray(2)
            previewView.getLocationOnScreen(location)
            val parentLoc = IntArray(2)
            (ring.parent as View).getLocationOnScreen(parentLoc)
            ring.translationX = x + location[0] - parentLoc[0] - halfW
            ring.translationY = y + location[1] - parentLoc[1] - halfH
            ring.scaleX = 0.6f; ring.scaleY = 0.6f; ring.alpha = 1f
            ring.visibility = View.VISIBLE
            ring.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(650).withEndAction {
                ring.visibility = View.GONE
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "Tap-to-focus failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useCameraX) SharedSurfaceProvider.previewSurfaceProvider = null
        else SharedSurfaceProvider.previewSurface = null
        try {
            startService(Intent(this, StreamService::class.java).apply {
                action = "com.example.handycam.ACTION_SET_PREVIEW_SURFACE"
                putExtra("surfaceToken", null as String?)
            })
        } catch (_: Exception) {}
        try { cameraReadyReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }
}
