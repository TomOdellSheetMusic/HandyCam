package com.example.handycam.presentation.cameracontrol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.handycam.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Refactored CameraControlActivity using MVVM architecture.
 * All business logic moved to CameraControlViewModel.
 * Uses proper dependency injection and state management.
 */
@AndroidEntryPoint
class CameraControlActivity : AppCompatActivity() {

    private val viewModel: CameraControlViewModel by viewModels()

    private lateinit var previewView: PreviewView
    private lateinit var flashBtn: ImageButton
    private lateinit var cameraSpinner: Spinner
    private lateinit var exposureLabel: TextView
    private lateinit var focusRing: View

    private var adjustingExposure = false
    private var exposureStartY = 0f
    private var lastExposureIndex = 0

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            setupPreview()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_control)

        initializeViews()
        setupObservers()
        setupListeners()
        checkPermissionsAndSetup()
    }

    private fun initializeViews() {
        previewView = findViewById(R.id.previewView)
        flashBtn = findViewById(R.id.flashBtn)
        cameraSpinner = findViewById(R.id.cameraSpinner)
        exposureLabel = findViewById(R.id.exposureLabel)
        focusRing = findViewById(R.id.focusRing)
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe app settings
                launch {
                    viewModel.appSettings.collect { settings ->
                        if (!settings.isStreaming) {
                            Toast.makeText(
                                this@CameraControlActivity,
                                "Please start streaming first",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        }
                    }
                }

                // Observe available cameras
                launch {
                    viewModel.availableCameras.collect { cameras ->
                        populateCameraSpinner(cameras)
                    }
                }

                // Observe torch state
                launch {
                    viewModel.torchEnabled.collect { enabled ->
                        updateFlashButton(enabled)
                    }
                }

                // Observe UI events
                launch {
                    viewModel.uiEvent.collect { event ->
                        when (event) {
                            is CameraControlViewModel.UiEvent.Error -> {
                                Toast.makeText(this@CameraControlActivity, event.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        flashBtn.setOnClickListener {
            val currentCamera = viewModel.appSettings.value.camera
            viewModel.toggleTorch(currentCamera)
        }

        cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val cameras = viewModel.availableCameras.value
                if (position < cameras.size) {
                    val camera = cameras[position]
                    viewModel.switchCamera(camera.id)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Touch listener for focus and exposure
        previewView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleTouchDown(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleTouchMove(event)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handleTouchUp()
                    true
                }
                else -> false
            }
        }
    }

    private fun populateCameraSpinner(cameras: List<com.example.handycam.data.model.CameraInfo>) {
        val cameraNames = cameras.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        cameraSpinner.adapter = adapter

        // Select current camera
        val currentCamera = viewModel.appSettings.value.camera
        val index = cameras.indexOfFirst { it.id == currentCamera || it.facing == currentCamera }
        if (index >= 0) {
            cameraSpinner.setSelection(index)
        }
    }

    private fun updateFlashButton(enabled: Boolean) {
        flashBtn.setImageResource(
            if (enabled) android.R.drawable.ic_menu_camera
            else android.R.drawable.ic_menu_gallery
        )
    }

    private fun checkPermissionsAndSetup() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            setupPreview()
        }
    }

    private fun setupPreview() {
        // Preview setup will use the SharedSurfaceProvider pattern from the original code
        // This connects to the StreamService's camera preview
        // The actual camera handling is done by StreamService
    }

    private fun handleTouchDown(event: MotionEvent) {
        if (event.pointerCount > 1) {
            adjustingExposure = true
            exposureStartY = event.getY(0)
        } else {
            // Show focus ring at touch location
            focusRing.visibility = View.VISIBLE
            focusRing.x = event.x - focusRing.width / 2
            focusRing.y = event.y - focusRing.height / 2
        }
    }

    private fun handleTouchMove(event: MotionEvent) {
        if (adjustingExposure && event.pointerCount > 1) {
            val deltaY = exposureStartY - event.getY(0)
            val steps = (deltaY / 50).toInt()
            if (steps != lastExposureIndex) {
                lastExposureIndex = steps
                exposureLabel.text = "Exposure: $steps"
                exposureLabel.visibility = View.VISIBLE
            }
        }
    }

    private fun handleTouchUp() {
        adjustingExposure = false
        focusRing.visibility = View.GONE
        exposureLabel.visibility = View.GONE
        lastExposureIndex = 0
        exposureStartY = 0f
    }
}
