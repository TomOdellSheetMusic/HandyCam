package com.example.handycam.presentation.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.handycam.CameraControlActivity
import com.example.handycam.R
import com.example.handycam.SettingsPagerAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Refactored MainActivity using MVVM architecture.
 * All business logic moved to MainViewModel.
 * Uses DataStore for preferences (replaced SharedPreferences).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    // UI components
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var widthEdit: EditText
    private lateinit var heightEdit: EditText
    private lateinit var cameraEdit: EditText
    private lateinit var cameraListLayout: LinearLayout
    private lateinit var fpsSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var previewButton: Button
    private lateinit var settingsPager: androidx.viewpager2.widget.ViewPager2
    private lateinit var settingsTabs: com.google.android.material.tabs.TabLayout

    private var pendingStartBundle: Bundle? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) tryStartPendingIfPermsGranted()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) tryStartPendingIfPermsGranted()
    }

    private val fgCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) tryStartPendingIfPermsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupObservers()
        setupListeners()
        requestCameraPermissionEarly()
    }

    private fun initializeViews() {
        hostEdit = findViewById(R.id.hostEdit)
        portEdit = findViewById(R.id.portEdit)
        widthEdit = findViewById(R.id.widthEdit)
        heightEdit = findViewById(R.id.heightEdit)
        cameraEdit = findViewById(R.id.cameraEdit)
        cameraListLayout = findViewById(R.id.cameraListLayout)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        startButton = findViewById(R.id.startButton)
        previewButton = findViewById(R.id.previewButton)
        settingsPager = findViewById(R.id.settingsPager)
        settingsTabs = findViewById(R.id.settingsTabLayout)

        // Setup FPS spinner
        val fpsChoices = listOf("15", "24", "30", "50", "60")
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsChoices).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        fpsSpinner.adapter = fpsAdapter

        // Setup settings pager
        settingsPager.adapter = SettingsPagerAdapter(this)
        com.google.android.material.tabs.TabLayoutMediator(settingsTabs, settingsPager) { tab, position ->
            tab.text = if (position == 0) "MJPEG" else "AVC"
        }.attach()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe app settings
                launch {
                    viewModel.appSettings.collect { settings ->
                        hostEdit.setText(settings.host)
                        portEdit.setText(settings.port.toString())
                        updateStreamingButton(settings.isStreaming)
                    }
                }

                // Observe stream config
                launch {
                    viewModel.streamConfig.collect { config ->
                        widthEdit.setText(config.width.toString())
                        heightEdit.setText(config.height.toString())
                        
                        val fpsChoices = listOf("15", "24", "30", "50", "60")
                        val fpsIndex = fpsChoices.indexOf(config.fps.toString()).coerceAtLeast(0)
                        fpsSpinner.setSelection(fpsIndex)
                    }
                }

                // Observe available cameras
                launch {
                    viewModel.availableCameras.collect { cameras ->
                        populateCameraList(cameras)
                    }
                }

                // Observe stream state
                launch {
                    viewModel.streamState.collect { state ->
                        updateStreamingButton(state.isActive)
                        if (state.error != null) {
                            Toast.makeText(this@MainActivity, state.error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // Observe UI events
                launch {
                    viewModel.uiEvent.collect { event ->
                        when (event) {
                            is MainViewModel.UiEvent.StreamStarted -> {
                                Toast.makeText(this@MainActivity, "Stream started", Toast.LENGTH_SHORT).show()
                            }
                            is MainViewModel.UiEvent.StreamStopped -> {
                                Toast.makeText(this@MainActivity, "Stream stopped", Toast.LENGTH_SHORT).show()
                            }
                            is MainViewModel.UiEvent.Error -> {
                                Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        startButton.setOnClickListener {
            val isCurrentlyStreaming = viewModel.streamState.value.isActive
            if (isCurrentlyStreaming) {
                viewModel.stopStreaming()
            } else {
                handleStartStreaming()
            }
        }

        previewButton.setOnClickListener {
            startActivity(Intent(this, CameraControlActivity::class.java))
        }

        // Save host when it changes
        hostEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.updateHost(hostEdit.text.toString())
            }
        }

        // Save port when it changes
        portEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                portEdit.text.toString().toIntOrNull()?.let { port ->
                    viewModel.updatePort(port)
                }
            }
        }

        // Save resolution when it changes
        widthEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateResolution()
        }
        heightEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateResolution()
        }

        // Save FPS when it changes
        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val fps = parent?.getItemAtPosition(position).toString().toIntOrNull() ?: 60
                viewModel.updateFps(fps)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun populateCameraList(cameras: List<com.example.handycam.data.model.CameraInfo>) {
        cameraListLayout.removeAllViews()

        // Add logical camera buttons
        val backBtn = Button(this).apply {
            text = "back"
            setOnClickListener {
                if (viewModel.streamState.value.isActive) {
                    viewModel.switchCamera("back")
                } else {
                    cameraEdit.setText("back")
                    viewModel.updateHost("back")
                }
            }
        }
        cameraListLayout.addView(backBtn)

        val frontBtn = Button(this).apply {
            text = "front"
            setOnClickListener {
                if (viewModel.streamState.value.isActive) {
                    viewModel.switchCamera("front")
                } else {
                    cameraEdit.setText("front")
                    viewModel.updateHost("front")
                }
            }
        }
        cameraListLayout.addView(frontBtn)

        // Add physical camera buttons
        cameras.forEach { camera ->
            val btn = Button(this).apply {
                text = "${camera.displayName}${camera.focalLength?.let { " $it" } ?: ""}"
                setOnClickListener {
                    if (viewModel.streamState.value.isActive) {
                        viewModel.switchCamera(camera.id)
                    } else {
                        cameraEdit.setText(camera.id)
                        viewModel.updateHost(camera.id)
                    }
                }
            }
            cameraListLayout.addView(btn)
        }
    }

    private fun handleStartStreaming() {
        val host = hostEdit.text.toString()
        val port = portEdit.text.toString().toIntOrNull() ?: 4747
        val width = widthEdit.text.toString().toIntOrNull() ?: 1080
        val height = heightEdit.text.toString().toIntOrNull() ?: 1920
        val camera = cameraEdit.text.toString().ifEmpty { "back" }
        val fps = fpsSpinner.selectedItem.toString().toIntOrNull() ?: 60

        // Get settings from fragments
        val useAvc = settingsPager.currentItem == 1
        val jpegQuality = if (!useAvc) {
            (settingsPager.adapter as? SettingsPagerAdapter)?.getMjpegFragment()?.getJpegQuality() ?: 85
        } else {
            85
        }
        val avcBitrate = if (useAvc) {
            (settingsPager.adapter as? SettingsPagerAdapter)?.getAvcFragment()?.getBitrateMbps()?.let { 
                (it * 1_000_000).toInt()
            }
        } else {
            null
        }

        pendingStartBundle = Bundle().apply {
            putString("host", host)
            putInt("port", port)
            putInt("width", width)
            putInt("height", height)
            putString("camera", camera)
            putInt("jpegQuality", jpegQuality)
            putInt("fps", fps)
            putBoolean("useAvc", useAvc)
            avcBitrate?.let { putInt("avcBitrate", it) }
        }

        requestPermissionsAndStart()
    }

    private fun requestPermissionsAndStart() {
        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // Check foreground service permission (Android 14+)
        if (Build.VERSION.SDK_INT >= 34) {
            val fgPerm = "android.permission.FOREGROUND_SERVICE_CAMERA"
            if (ContextCompat.checkSelfPermission(this, fgPerm) != PackageManager.PERMISSION_GRANTED) {
                fgCameraPermissionLauncher.launch(fgPerm)
                return
            }
        }

        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        tryStartPendingIfPermsGranted()
    }

    private fun tryStartPendingIfPermsGranted() {
        val bundle = pendingStartBundle ?: return

        // Check all permissions are granted
        val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val fgGranted = if (Build.VERSION.SDK_INT >= 34) {
            ContextCompat.checkSelfPermission(this, "android.permission.FOREGROUND_SERVICE_CAMERA") == PackageManager.PERMISSION_GRANTED
        } else true
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (camGranted && fgGranted && notifGranted) {
            val host = bundle.getString("host") ?: "0.0.0.0"
            val port = bundle.getInt("port", 4747)
            val width = bundle.getInt("width", 1080)
            val height = bundle.getInt("height", 1920)
            val camera = bundle.getString("camera") ?: "back"
            val jpegQuality = bundle.getInt("jpegQuality", 85)
            val fps = bundle.getInt("fps", 60)
            val useAvc = bundle.getBoolean("useAvc", false)
            val avcBitrate = if (bundle.containsKey("avcBitrate")) bundle.getInt("avcBitrate") else null

            viewModel.startStreaming(host, port, width, height, camera, jpegQuality, fps, useAvc, avcBitrate)
            pendingStartBundle = null
        }
    }

    private fun requestCameraPermissionEarly() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateStreamingButton(isStreaming: Boolean) {
        startButton.text = if (isStreaming) "Stop Server" else "Start Server"
    }

    private fun updateResolution() {
        val width = widthEdit.text.toString().toIntOrNull() ?: 1080
        val height = heightEdit.text.toString().toIntOrNull() ?: 1920
        viewModel.updateResolution(width, height)
    }
}
