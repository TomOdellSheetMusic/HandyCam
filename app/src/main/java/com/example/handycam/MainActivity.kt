package com.example.handycam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import android.view.View
import android.util.Size

private const val DEFAULT_PORT = 4747

class MainActivity : AppCompatActivity() {

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // no-op
    }

    private val fgCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // no-op
    }

    private var isStreaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val hostEdit = findViewById<EditText>(R.id.hostEdit)
        val portEdit = findViewById<EditText>(R.id.portEdit)
        val widthEdit = findViewById<EditText>(R.id.widthEdit)
        val heightEdit = findViewById<EditText>(R.id.heightEdit)
        val cameraEdit = findViewById<EditText>(R.id.cameraEdit)
        val cameraListLayout = findViewById<LinearLayout>(R.id.cameraListLayout)
        val settingsPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.settingsPager)
        val settingsTabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.settingsTabLayout)
        val fpsEdit = findViewById<EditText>(R.id.fpsEdit)
        val startButton = findViewById<Button>(R.id.startButton)
        val previewButton = findViewById<Button>(R.id.previewButton)

        hostEdit.setText("0.0.0.0")
        portEdit.setText(DEFAULT_PORT.toString())
        widthEdit.setText("1080")
        heightEdit.setText("1920")
        fpsEdit.setText("60")

        // Request camera permission early
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // populate camera list
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.forEach { id ->
                try {
                    val chars = cm.getCameraCharacteristics(id)
                    val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        else -> "unknown"
                    }
                    val btn = Button(this).apply {
                        text = "$id ($facing)"
                        setOnClickListener { cameraEdit.setText(id) }
                    }
                    cameraListLayout.addView(btn)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // setup tabs + pager
        val pagerAdapter = SettingsPagerAdapter(this)
        settingsPager.adapter = pagerAdapter
        com.google.android.material.tabs.TabLayoutMediator(settingsTabs, settingsPager) { tab, position ->
            tab.text = if (position == 0) "MJPEG" else "AVC"
        }.attach()

        startButton.setOnClickListener {
            val host = hostEdit.text.toString().ifBlank { "0.0.0.0" }
            val port = portEdit.text.toString().toIntOrNull() ?: DEFAULT_PORT
            val width = widthEdit.text.toString().toIntOrNull() ?: 1280
            val height = heightEdit.text.toString().toIntOrNull() ?: 720
            val camera = cameraEdit.text.toString().ifBlank { "back" }
            val fps = fpsEdit.text.toString().toIntOrNull() ?: 25

            // read codec-specific settings from fragments
            val mjpegQuality = pagerAdapter.getMjpegFragment().getJpegQuality()
            val avcBitrateMbps = pagerAdapter.getAvcFragment().getBitrateMbps()

            // determine which codec tab is selected
            val useAvc = settingsTabs.selectedTabPosition == 1

            val jpeg = mjpegQuality

            if (!isStreaming) {
                // pass bitrate only if AVC selected
                val intent = Intent(this, StreamService::class.java).apply {
                    action = "com.example.handycam.ACTION_START"
                    putExtra("host", host)
                    putExtra("port", port)
                    putExtra("width", width)
                    putExtra("height", height)
                    putExtra("camera", camera)
                    putExtra("jpegQuality", jpeg)
                    putExtra("targetFps", fps)
                    putExtra("useAvc", useAvc)
                    if (useAvc && avcBitrateMbps != null) {
                        val bps = (avcBitrateMbps * 1_000_000f).toInt()
                        if (bps > 0) putExtra("avcBitrate", bps)
                    }
                }
                ContextCompat.startForegroundService(this, intent)
                startButton.text = "Stop Server"
            } else {
                stopStreaming()
                startButton.text = "Start Server"
            }
            isStreaming = !isStreaming
        }

        // Launch preview activity
        previewButton.setOnClickListener {
            // open preview without stopping the streaming service
            startActivity(Intent(this, PreviewActivity::class.java))
        }
    }

    // preview binding moved to PreviewActivity

    private fun startStreaming(bindHost: String, port: Int, width: Int, height: Int, camera: String, jpegQuality: Int, targetFps: Int, useAvc: Boolean) {
        val intent = Intent(this, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_START"
            putExtra("host", bindHost)
            putExtra("port", port)
            putExtra("width", width)
            putExtra("height", height)
            putExtra("camera", camera)
            putExtra("jpegQuality", jpegQuality)
            putExtra("targetFps", targetFps)
            putExtra("useAvc", useAvc)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun ensurePermissionsAndStart(bindHost: String, port: Int, width: Int, height: Int, camera: String, jpegQuality: Int, targetFps: Int, useAvc: Boolean) {
        // Ensure CAMERA permission
        val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!camGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        // Ensure FOREGROUND_SERVICE_CAMERA permission (Android 14+ requirement when targeting newer SDKs)
        val fgPerm = "android.permission.FOREGROUND_SERVICE_CAMERA"
        val fgGranted = ContextCompat.checkSelfPermission(this, fgPerm) == PackageManager.PERMISSION_GRANTED
        if (!fgGranted) {
            fgCameraPermissionLauncher.launch(fgPerm)
            return
        }

        // All required permissions present — start the service
        startStreaming(bindHost, port, width, height, camera, jpegQuality, targetFps, useAvc)
    }

    private fun stopStreaming() {
        val intent = Intent(this, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_STOP"
        }
        startService(intent)
    }

}
