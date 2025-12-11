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
import android.view.Surface
import android.view.View
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.ArrayAdapter
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

class CameraControlActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var flashBtn: ImageButton
    private lateinit var cameraSpinner: Spinner
    private lateinit var exposureLabel: TextView
    private lateinit var focusRing: View
    private var cameraManager: android.hardware.camera2.CameraManager? = null

    private var cameraControl: androidx.camera.core.CameraControl? = null
    private var cameraInfo: androidx.camera.core.CameraInfo? = null

    private var adjustingExposure = false
    private var exposureStartY = 0f
    private var lastExposureIndex = 0
    
    private var cameraReadyReceiver: BroadcastReceiver? = null
    private var useCameraX = false // Track which camera system is being used

    private lateinit var settingsManager: SettingsManager

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

        settingsManager.cameraSetting.observe(this) { cameraSetting ->
            // Update UI or functionality based on camera setting
        }

        settingsManager.streamSetting.observe(this) { streamSetting ->
            // Update UI or functionality based on stream setting
        }

        previewView = findViewById(R.id.previewView)
        flashBtn = findViewById(R.id.flashBtn)
        cameraSpinner = findViewById(R.id.cameraSpinner)
        exposureLabel = findViewById(R.id.exposureLabel)
        focusRing = findViewById(R.id.focusRing)

        try {
            cameraManager = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        } catch (_: Exception) {}
        
        // Check if streaming service is running to determine camera mode
        val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
        val isStreaming = prefs.getBoolean("isStreaming", false)
        useCameraX = prefs.getBoolean("useAvc", false).not() // CameraX for MJPEG, Camera2 for AVC
        
        if (!isStreaming) {
            Toast.makeText(this, "Please start streaming first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        flashBtn.setOnClickListener {
            val ctrl = SharedSurfaceProvider.cameraControl
            val mgr = cameraManager
            if (ctrl == null) {
                // Try to toggle via CameraManager as a fallback
                val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
                val prefCam = prefs.getString("camera", null)
                val camIdFallback = try {
                    if (prefCam != null && mgr?.cameraIdList?.contains(prefCam) == true) prefCam else mgr?.cameraIdList?.getOrNull(cameraSpinner.selectedItemPosition)
                } catch (_: Exception) { null }
                if (camIdFallback != null) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        try {
                            val info = SharedSurfaceProvider.cameraInfo
                            val currentTorch = info?.torchState?.value
                            val enable = currentTorch != androidx.camera.core.TorchState.ON
                            try {
                                mgr?.setTorchMode(camIdFallback, enable)
                            } catch (e: Exception) {
                                Log.w(TAG, "CameraManager.setTorchMode failed", e)
                                Toast.makeText(this@CameraControlActivity, "Torch toggle failed", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Torch toggle fallback failed", e)
                            Toast.makeText(this@CameraControlActivity, "Torch toggle failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
                }
            } else {
                // toggle torch via CameraX control, with CameraManager fallback
                lifecycleScope.launch(Dispatchers.Main) {
                    try {
                        val info = SharedSurfaceProvider.cameraInfo ?: return@launch
                        val torchState = info.torchState.value
                        val enable = torchState != androidx.camera.core.TorchState.ON
                        try {
                            ctrl.enableTorch(enable)
                        } catch (e: Exception) {
                            // Some implementations return a ListenableFuture; attempt to call anyway
                            try {
                                val f = ctrl.javaClass.getMethod("enableTorch", Boolean::class.javaPrimitiveType).invoke(ctrl, enable)
                            } catch (_: Exception) {}
                        }

                        // Also attempt CameraManager fallback to ensure hardware torch toggles on some devices
                        try {
                            val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
                            val prefCam = prefs.getString("camera", null)
                            val camIdFallback = try { if (prefCam != null && mgr?.cameraIdList?.contains(prefCam) == true) prefCam else mgr?.cameraIdList?.getOrNull(cameraSpinner.selectedItemPosition) } catch (_: Exception) { null }
                            if (camIdFallback != null) {
                                try { mgr?.setTorchMode(camIdFallback, enable) } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.w(TAG, "Torch toggle failed", e)
                        Toast.makeText(this@CameraControlActivity, "Torch toggle failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // populate camera list into spinner
        try {
            val mgr = cameraManager
            if (mgr != null) {
                val ids = mgr.cameraIdList.toList()
                val labels = mutableListOf<String>()
                for (id in ids) {
                    try {
                        val chars = mgr.getCameraCharacteristics(id)
                        val facing = when (chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "front"
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "back"
                            else -> "unknown"
                        }
                        labels.add("$id ($facing)")
                    } catch (_: Exception) {
                        labels.add(id)
                    }
                }
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                cameraSpinner.adapter = adapter
                cameraSpinner.setSelection(0)
                cameraSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                        val camId = try { mgr.cameraIdList[position] } catch (_: Exception) { null }
                        if (camId != null) {
                            // Tell the service to switch camera
                            val intent = Intent(this@CameraControlActivity, StreamService::class.java).apply {
                                action = "com.example.handycam.ACTION_SET_CAMERA"
                                putExtra("camera", camId)
                            }
                            startService(intent)
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
                })
            }
        } catch (_: Exception) {}

        // gestures: tap to focus, long-press then slide vertically to change exposure
        val gd = object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                performTapToFocus(e.x, e.y)
                return true
            }

            override fun onLongPress(e: android.view.MotionEvent) {
                adjustingExposure = true
                exposureStartY = e.y
                // snapshot current exposure index
                lastExposureIndex = SharedSurfaceProvider.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
                exposureLabel.visibility = View.VISIBLE
            }
        }

        val gestureDetector = android.view.GestureDetector(this, gd)

        previewView.setOnTouchListener { _, motion ->
            gestureDetector.onTouchEvent(motion)
            when (motion.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (adjustingExposure) {
                        val dy = exposureStartY - motion.y
                        val height = previewView.height.coerceAtLeast(1)
                        // map dy to exposure range
                        val range = SharedSurfaceProvider.cameraInfo?.exposureState?.exposureCompensationRange
                        if (range != null) {
                            val span = range.upper - range.lower
                            if (span > 0) {
                                val deltaIndex = ((dy / height) * span).toInt()
                                val target = (lastExposureIndex + deltaIndex).coerceIn(range.lower, range.upper)
                                try {
                                    SharedSurfaceProvider.cameraControl?.setExposureCompensationIndex(target)
                                    exposureLabel.text = "EV: $target"
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
        
        // Register receiver to know when camera is ready
        cameraReadyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, "Camera ready signal received")
                runOnUiThread {
                    updateCameraControls()
                }
            }
        }
        registerReceiver(cameraReadyReceiver, IntentFilter("com.example.handycam.CAMERA_READY"), Context.RECEIVER_NOT_EXPORTED)

        // permission check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else setupPreview()
    }

    private fun setupPreview() {
        // For both modes, provide surface provider to the service
        // The service will handle Camera2 or CameraX appropriately
        SharedSurfaceProvider.previewSurfaceProvider = previewView.surfaceProvider
        
        // Notify service to reconfigure with preview
        val intent = Intent(this, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_SET_PREVIEW_SURFACE"
            putExtra("surfaceToken", if (useCameraX) "camerax_preview" else "camera2_preview")
        }
        startService(intent)
        
        Log.i(TAG, "Setup preview surface provider for ${if (useCameraX) "CameraX" else "Camera2"} mode")
        
        // Update controls with current camera state
        updateCameraControls()
    }
    
    private fun updateCameraControls() {
        // Update UI with current camera state from shared provider
        SharedSurfaceProvider.cameraInfo?.let { info ->
            val torchState = info.torchState.value
            try {
                // set lightning bolt and tint it to indicate state
                flashBtn.setImageResource(R.drawable.ic_flash)
                val color = if (torchState == androidx.camera.core.TorchState.ON) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                flashBtn.imageTintList = android.content.res.ColorStateList.valueOf(color)
            } catch (_: Exception) {}
            val ev = info.exposureState.exposureCompensationIndex
            exposureLabel.text = "EV: $ev"
        } ?: run {
            // If CameraInfo not available yet, try to infer flash availability from CameraManager
            try {
                val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
                val prefCam = prefs.getString("camera", null)
                val mgr = cameraManager
                val camIdFallback = try { if (prefCam != null && mgr?.cameraIdList?.contains(prefCam) == true) prefCam else mgr?.cameraIdList?.getOrNull(cameraSpinner.selectedItemPosition) } catch (_: Exception) { null }
                if (camIdFallback != null) {
                    val chars = mgr?.getCameraCharacteristics(camIdFallback)
                    val hasFlash = chars?.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    flashBtn.setImageResource(R.drawable.ic_flash)
                    flashBtn.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    flashBtn.alpha = if (hasFlash) 1.0f else 0.4f
                }
            } catch (_: Exception) {}
        }
    }

    private fun performTapToFocus(x: Float, y: Float) {
        try {
            val factory: MeteringPointFactory = previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3_000, TimeUnit.MILLISECONDS)
                .build()
            SharedSurfaceProvider.cameraControl?.startFocusAndMetering(action)
            // show focus ring at touch point
            try {
                val ring = focusRing
                val halfW = ring.width / 2f
                val halfH = ring.height / 2f
                // position: previewView's coordinate space -> parent FrameLayout
                val location = IntArray(2)
                previewView.getLocationOnScreen(location)
                val pvX = location[0]
                val pvY = location[1]
                // get parent location
                val parentLoc = IntArray(2)
                (ring.parent as View).getLocationOnScreen(parentLoc)
                val relX = x + pvX - parentLoc[0]
                val relY = y + pvY - parentLoc[1]
                ring.translationX = relX - halfW
                ring.translationY = relY - halfH
                ring.scaleX = 0.6f
                ring.scaleY = 0.6f
                ring.alpha = 1f
                ring.visibility = View.VISIBLE
                ring.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(650).withEndAction {
                    ring.visibility = View.GONE
                }.start()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show focus ring", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tap-to-focus failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up preview surface
        if (useCameraX) {
            SharedSurfaceProvider.previewSurfaceProvider = null
        } else {
            SharedSurfaceProvider.previewSurface = null
        }
        
        // Notify service to remove preview
        try {
            val intent = Intent(this, StreamService::class.java).apply {
                action = "com.example.handycam.ACTION_SET_PREVIEW_SURFACE"
                putExtra("surfaceToken", null as String?)
            }
            startService(intent)
        } catch (_: Exception) {}
        
        try { cameraReadyReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }
}
