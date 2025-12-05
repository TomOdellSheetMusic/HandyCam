package com.example.handycam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
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
import androidx.camera.lifecycle.ProcessCameraProvider
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

    private var cameraProvider: ProcessCameraProvider? = null
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraControl: androidx.camera.core.CameraControl? = null
    private var cameraInfo: androidx.camera.core.CameraInfo? = null

    private var adjustingExposure = false
    private var exposureStartY = 0f
    private var lastExposureIndex = 0

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_control)

        previewView = findViewById(R.id.previewView)
        flashBtn = findViewById(R.id.flashBtn)
        cameraSpinner = findViewById(R.id.cameraSpinner)
        exposureLabel = findViewById(R.id.exposureLabel)
        focusRing = findViewById(R.id.focusRing)

        try {
            cameraManager = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        } catch (_: Exception) {}

        flashBtn.setOnClickListener {
            cameraControl?.let { ctrl ->
                // toggle torch
                lifecycleScope.launch(Dispatchers.Main) {
                    try {
                        val info = cameraInfo ?: return@launch
                        val torchState = info.torchState.value
                        val enable = torchState != androidx.camera.core.TorchState.ON
                        ctrl.enableTorch(enable)
                    } catch (e: Exception) {
                        Log.w(TAG, "Torch toggle failed", e)
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
                            // pick lens based on characteristics
                            try {
                                val chars = mgr.getCameraCharacteristics(camId)
                                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                                currentLensFacing = if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                                cameraSelector = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                                bindCameraUseCases()
                            } catch (_: Exception) {}
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
                lastExposureIndex = cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
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
                        val range = cameraInfo?.exposureState?.exposureCompensationRange
                        if (range != null) {
                            val span = range.upper - range.lower
                            if (span > 0) {
                                val deltaIndex = ((dy / height) * span).toInt()
                                val target = (lastExposureIndex + deltaIndex).coerceIn(range.lower, range.upper)
                                try {
                                    cameraControl?.setExposureCompensationIndex(target)
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

        // permission check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else startCamera()
    }

    private fun startCamera() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(this@CameraControlActivity)
                cameraProvider = providerFuture.get()
                cameraSelector = if (currentLensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider init failed", e)
                Toast.makeText(this@CameraControlActivity, "Unable to start camera", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun bindCameraUseCases() {
        try {
            val provider = cameraProvider ?: return
            provider.unbindAll()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val selector = cameraSelector
            val camera = provider.bindToLifecycle(this, selector, preview)

            cameraControl = camera.cameraControl
            cameraInfo = camera.cameraInfo

            // update UI initial state
            cameraInfo?.let { info ->
                val torchState = info.torchState.value
                try {
                    if (torchState == androidx.camera.core.TorchState.ON) {
                        flashBtn.setImageResource(android.R.drawable.ic_menu_camera)
                    } else {
                        flashBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    }
                } catch (_: Exception) {}
                val ev = info.exposureState.exposureCompensationIndex
                exposureLabel.text = "EV: $ev"
            }

        } catch (e: Exception) {
            Log.e(TAG, "bindCameraUseCases failed", e)
            Toast.makeText(this, "Camera bind failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performTapToFocus(x: Float, y: Float) {
        try {
            val factory: MeteringPointFactory = previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3_000, TimeUnit.MILLISECONDS)
                .build()
            cameraControl?.startFocusAndMetering(action)
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
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
    }
}
