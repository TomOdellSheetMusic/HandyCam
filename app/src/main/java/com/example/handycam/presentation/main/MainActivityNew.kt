package com.example.handycam.presentation.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.handycam.CameraControlActivity
import com.example.handycam.SettingsPagerAdapter
import com.example.handycam.data.model.CameraInfo
import com.example.handycam.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivityNew : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    private var pendingStartBundle: Bundle? = null
    private val cameraList = mutableListOf<CameraInfo>()

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        initializeViews()
        setupObservers()
        setupListeners()
        requestCameraPermissionEarly()
    }

    private fun initializeViews() {
        // Setup FPS spinner
        val fpsChoices = listOf("15", "24", "30", "50", "60")
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsChoices).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.fpsSpinner.adapter = fpsAdapter

        // Setup settings pager
        binding.settingsPager.adapter = SettingsPagerAdapter(this)
        com.google.android.material.tabs.TabLayoutMediator(binding.settingsTabLayout, binding.settingsPager) { tab, position ->
            tab.text = if (position == 0) "MJPEG" else "AVC"
        }.attach()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.appSettings.collect { settings ->
                        binding.hostEdit.setText(settings.host)
                        binding.portEdit.setText(settings.port.toString())
                        updateStreamingButton(settings.isStreaming)
                    }
                }

                launch {
                    viewModel.streamConfig.collect { config ->
                        binding.widthEdit.setText(config.width.toString())
                        binding.heightEdit.setText(config.height.toString())

                        val cameraLabel = cameraList.find { it.id == config.camera }?.displayName
                        if (cameraLabel != null) {
                            binding.cameraDropdown.setText(cameraLabel, false)
                        }

                        val fpsChoices = listOf("15", "24", "30", "50", "60")
                        val fpsIndex = fpsChoices.indexOf(config.fps.toString()).coerceAtLeast(0)
                        binding.fpsSpinner.setSelection(fpsIndex)
                    }
                }

                launch {
                    viewModel.availableCameras.collect { cameras ->
                        populateCameraList(cameras)
                    }
                }

                launch {
                    viewModel.streamState.collect { state ->
                        updateStreamingButton(state.isActive)
                        if (state.error != null) {
                            Toast.makeText(this@MainActivityNew, state.error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                launch {
                    viewModel.uiEvent.collect { event ->
                        when (event) {
                            is MainViewModel.UiEvent.StreamStarted -> {
                                Toast.makeText(this@MainActivityNew, "Stream started", Toast.LENGTH_SHORT).show()
                            }
                            is MainViewModel.UiEvent.StreamStopped -> {
                                Toast.makeText(this@MainActivityNew, "Stream stopped", Toast.LENGTH_SHORT).show()
                            }
                            is MainViewModel.UiEvent.Error -> {
                                Toast.makeText(this@MainActivityNew, event.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.startButton.setOnClickListener {
            val isCurrentlyStreaming = viewModel.streamState.value.isActive
            if (isCurrentlyStreaming) {
                viewModel.stopStreaming()
            } else {
                handleStartStreaming()
            }
        }

        binding.previewButton.setOnClickListener {
            startActivity(Intent(this, CameraControlActivity::class.java))
        }

        binding.hostEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.updateHost(binding.hostEdit.text.toString())
            }
        }

        binding.portEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                binding.portEdit.text.toString().toIntOrNull()?.let {
                    viewModel.updatePort(it)
                }
            }
        }

        binding.widthEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateResolution()
        }
        binding.heightEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateResolution()
        }

        binding.fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val fps = parent?.getItemAtPosition(position).toString().toIntOrNull() ?: 60
                viewModel.updateFps(fps)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.cameraDropdown.onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
            val selectedLabel = parent.getItemAtPosition(position).toString()
            cameraList.find { it.displayName == selectedLabel }?.let {
                viewModel.updateCamera(it.id)
            }
        }
    }

    private fun populateCameraList(cameras: List<CameraInfo>) {
        cameraList.clear()
        cameraList.addAll(cameras)

        val cameraLabels = cameras.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, cameraLabels)
        binding.cameraDropdown.setAdapter(adapter)

        val currentCameraId = viewModel.streamConfig.value.camera
        val currentCamera = cameras.find { it.id == currentCameraId }
        if (currentCamera != null) {
            binding.cameraDropdown.setText(currentCamera.displayName, false)
        }
    }

    private fun handleStartStreaming() {
        val host = binding.hostEdit.text.toString()
        val port = binding.portEdit.text.toString().toIntOrNull() ?: 4747
        val width = binding.widthEdit.text.toString().toIntOrNull() ?: 1080
        val height = binding.heightEdit.text.toString().toIntOrNull() ?: 1920
        val selectedCameraLabel = binding.cameraDropdown.text.toString()
        val camera = cameraList.find { it.displayName == selectedCameraLabel }?.id ?: "back"
        val fps = binding.fpsSpinner.selectedItem.toString().toIntOrNull() ?: 60

        val useAvc = binding.settingsPager.currentItem == 1
        val jpegQuality = if (!useAvc) {
            (binding.settingsPager.adapter as? SettingsPagerAdapter)?.getMjpegFragment()?.getJpegQuality() ?: 85
        } else 85
        val avcBitrate = if (useAvc) {
            (binding.settingsPager.adapter as? SettingsPagerAdapter)?.getAvcFragment()?.getBitrateMbps()?.let {
                (it * 1_000_000).toInt()
            }
        } else null

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val fgPerm = "android.permission.FOREGROUND_SERVICE_CAMERA"
            if (ContextCompat.checkSelfPermission(this, fgPerm) != PackageManager.PERMISSION_GRANTED) {
                fgCameraPermissionLauncher.launch(fgPerm)
                return
            }
        }

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
        binding.startButton.text = if (isStreaming) "Stop Server" else "Start Server"
        binding.previewButton.visibility = if (isStreaming) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateResolution() {
        val width = binding.widthEdit.text.toString().toIntOrNull() ?: 1080
        val height = binding.heightEdit.text.toString().toIntOrNull() ?: 1920
        viewModel.updateResolution(width, height)
    }
}