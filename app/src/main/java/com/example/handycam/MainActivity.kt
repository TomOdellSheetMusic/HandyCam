package com.example.handycam

import android.Manifest
import android.os.Build
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
import androidx.camera.core.CameraControl

private const val DEFAULT_PORT = 4747

class MainActivity : AppCompatActivity() {

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

    private var isStreaming = false
    private var streamStateReceiver: android.content.BroadcastReceiver? = null
    private var httpsServerStateReceiver: android.content.BroadcastReceiver? = null
    private val PREFS = "handy_prefs"
    private var pendingStartBundle: android.os.Bundle? = null
    private var isHttpsServerRunning = false
    private var pagerAdapter: SettingsPagerAdapter? = null
    private lateinit var settingsManager: SettingsManager

    private fun tryStartPendingIfPermsGranted() {
        val b = pendingStartBundle ?: return
        // check permissions
        val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val fgPerm = "android.permission.FOREGROUND_SERVICE_CAMERA"
        val fgGranted = if (Build.VERSION.SDK_INT >= 34) {
            ContextCompat.checkSelfPermission(this, fgPerm) == PackageManager.PERMISSION_GRANTED
        } else true
        val notifGranted = if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (camGranted && fgGranted && notifGranted) {
            val host = b.getString("host") ?: "0.0.0.0"
            val port = b.getInt("port", DEFAULT_PORT)
            val width = b.getInt("width", 1080)
            val height = b.getInt("height", 1920)
            val camera = b.getString("camera") ?: "back"
            val jpeg = b.getInt("jpegQuality", 85)
            val fps = b.getInt("fps", 50)
            val useAvc = b.getBoolean("useAvc", false)
            try {
                startStreaming(host, port, width, height, camera, jpeg, fps, useAvc)
                isStreaming = true
                pendingStartBundle = null
                try {
                    val btn = findViewById<Button>(R.id.startButton)
                    runOnUiThread { btn.text = "Stop Server" }
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager.getInstance(this)

        setContentView(R.layout.activity_main)

        // Setup observers for settings manager
        settingsManager.isStreaming.observe(this) { streaming ->
            runOnUiThread {
                try {
                    val btn = findViewById<Button>(R.id.startButton)
                    btn.text = if (streaming) "Stop Server" else "Start Server"
                    isStreaming = streaming
                } catch (_: Exception) {}
            }
        }

        settingsManager.camera.observe(this) { camera ->
            runOnUiThread {
                try {
                    val cameraEdit = findViewById<EditText>(R.id.cameraEdit)
                    cameraEdit.setText(camera)
                } catch (_: Exception) {}
            }
        }

        settingsManager.port.observe(this) { port ->
            runOnUiThread {
                try {
                    val portEdit = findViewById<EditText>(R.id.portEdit)
                    if (portEdit.text.toString().toIntOrNull() != port) {
                        portEdit.setText(port.toString())
                    }
                } catch (_: Exception) {}
            }
        }

        settingsManager.width.observe(this) { width ->
            runOnUiThread {
                try {
                    val widthEdit = findViewById<EditText>(R.id.widthEdit)
                    if (widthEdit.text.toString().toIntOrNull() != width) {
                        widthEdit.setText(width.toString())
                    }
                } catch (_: Exception) {}
            }
        }

        settingsManager.height.observe(this) { height ->
            runOnUiThread {
                try {
                    val heightEdit = findViewById<EditText>(R.id.heightEdit)
                    if (heightEdit.text.toString().toIntOrNull() != height) {
                        heightEdit.setText(height.toString())
                    }
                } catch (_: Exception) {}
            }
        }

        settingsManager.fps.observe(this) { fps ->
            runOnUiThread {
                try {
                    val fpsSpinner = findViewById<android.widget.Spinner>(R.id.fpsSpinner)
                    val fpsChoices = listOf("15", "24", "30", "50", "60")
                    val index = fpsChoices.indexOf(fps.toString())
                    if (index >= 0 && fpsSpinner.selectedItemPosition != index) {
                        fpsSpinner.setSelection(index)
                    }
                } catch (_: Exception) {}
            }
        }
        settingsManager.httpsRunning.observe(this) { httpsRunning ->
            runOnUiThread {
                try {
                    val httpsServerButton = findViewById<Button>(R.id.httpsServerButton)
                    val httpsServerStatus = findViewById<TextView>(R.id.httpsServerStatus)
                    httpsServerButton.text = if (httpsRunning) "Stop HTTPS Server" else "Start HTTPS Server"
                    httpsServerStatus.text = if (httpsRunning) {
                        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        val port = prefs.getInt("httpsServerPort", 8443)
                        "Server running on port $port\nAccess at: http://"+settingsManager.host.value+":"+settingsManager.port.value
                    } else {
                        "Server stopped"
                    }
                } catch (_: Exception) {}
            }
        }

        val hostEdit = findViewById<EditText>(R.id.hostEdit)
        val portEdit = findViewById<EditText>(R.id.portEdit)
        val widthEdit = findViewById<EditText>(R.id.widthEdit)
        val heightEdit = findViewById<EditText>(R.id.heightEdit)
        val cameraEdit = findViewById<EditText>(R.id.cameraEdit)
        val cameraListLayout = findViewById<LinearLayout>(R.id.cameraListLayout)
        val settingsPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.settingsPager)
        val settingsTabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.settingsTabLayout)
        val fpsSpinner = findViewById<android.widget.Spinner>(R.id.fpsSpinner)
        val startButton = findViewById<Button>(R.id.startButton)
        val previewButton = findViewById<Button>(R.id.previewButton)
        val httpsPortEdit = findViewById<EditText>(R.id.httpsPortEdit)
        val httpsServerButton = findViewById<Button>(R.id.httpsServerButton)
        val httpsServerStatus = findViewById<TextView>(R.id.httpsServerStatus)

        // load saved settings
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hostEdit.setText(prefs.getString("host", "0.0.0.0"))
        portEdit.setText(prefs.getInt("port", DEFAULT_PORT).toString())
        widthEdit.setText(prefs.getInt("width", 1920).toString())
        heightEdit.setText(prefs.getInt("height", 1080).toString())
        // defer camera display until we've populated the camera list; remember saved value
        val savedCamera = prefs.getString("camera", "back")
        // populate FPS spinner with common choices
        val fpsChoices = listOf("15", "24", "30", "50", "60")
        val fpsAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsChoices).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        fpsSpinner.adapter = fpsAdapter
        val savedFps = prefs.getInt("fps", 60).toString()
        fpsSpinner.setSelection(fpsChoices.indexOf(savedFps).coerceAtLeast(0))

        // Request camera permission early
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // populate camera list (buttons show id, facing and focal length when available)
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // also add logical named picks for convenience
            val backBtn = Button(this).apply {
                text = "back"
                setOnClickListener {
                    if (isStreaming) {
                        val intent = Intent(this@MainActivity, StreamService::class.java).apply {
                            action = "com.example.handycam.ACTION_SET_CAMERA"
                            putExtra("camera", "back")
                        }
                        startService(intent)
                    } else {
                        cameraEdit.tag = null
                        cameraEdit.setText("back")
                        try { prefs.edit().putString("camera", "back").apply() } catch (_: Exception) {}
                    }
                }
            }
            val frontBtn = Button(this).apply {
                text = "front"
                setOnClickListener {
                    if (isStreaming) {
                        val intent = Intent(this@MainActivity, StreamService::class.java).apply {
                            action = "com.example.handycam.ACTION_SET_CAMERA"
                            putExtra("camera", "front")
                        }
                        startService(intent)
                    } else {
                        cameraEdit.tag = null
                        cameraEdit.setText("front")
                        try { prefs.edit().putString("camera", "front").apply() } catch (_: Exception) {}
                    }
                }
            }
            cameraListLayout.addView(backBtn)
            cameraListLayout.addView(frontBtn)
            cm.cameraIdList.forEach { id ->
                try {
                    val chars = cm.getCameraCharacteristics(id)
                    val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        else -> "unknown"
                    }
                    // try to get a representative focal length to distinguish wide/normal
                    val focalArr = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            val focalDesc = if (focalArr != null && focalArr.isNotEmpty()) {
                                val f = focalArr[0]
                                String.format("f=%.1fmm", f)
                            } else ""

                            val label = if (focalDesc.isNotEmpty()) "$id ($facing, $focalDesc)" else "$id ($facing)"
                            val btn = Button(this).apply {
                                text = label
                                setOnClickListener {
                                    if (isStreaming) {
                                        val intent = Intent(this@MainActivity, StreamService::class.java).apply {
                                            action = "com.example.handycam.ACTION_SET_CAMERA"
                                            putExtra("camera", id)
                                        }
                                        startService(intent)
                                    } else {
                                        // display human label but keep the real id in tag
                                        cameraEdit.tag = id
                                        cameraEdit.setText(label)
                                        try { prefs.edit().putString("camera", id).apply() } catch (_: Exception) {}
                                    }
                                }
                            }
                    cameraListLayout.addView(btn)
                } catch (_: Exception) {}
            }
                    // after populating list, restore saved camera selection (id or logical)
                    try {
                        if (!savedCamera.isNullOrEmpty()) {
                            if (savedCamera == "back" || savedCamera == "front") {
                                cameraEdit.tag = null
                                cameraEdit.setText(savedCamera)
                            } else {
                                // if saved value matches a physical id, find its button label
                                if (cm.cameraIdList.contains(savedCamera)) {
                                    val chars2 = cm.getCameraCharacteristics(savedCamera)
                                    val facing2 = when (chars2.get(CameraCharacteristics.LENS_FACING)) {
                                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                                        else -> "unknown"
                                    }
                                    val focalArr2 = chars2.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                    val focalDesc2 = if (focalArr2 != null && focalArr2.isNotEmpty()) String.format("f=%.1fmm", focalArr2[0]) else ""
                                    val lbl = if (focalDesc2.isNotEmpty()) "$savedCamera ($facing2, $focalDesc2)" else "$savedCamera ($facing2)"
                                    cameraEdit.tag = savedCamera
                                    cameraEdit.setText(lbl)
                                } else {
                                    cameraEdit.tag = null
                                    cameraEdit.setText(savedCamera)
                                }
                            }
                        }
                    } catch (_: Exception) {}
        } catch (_: Exception) {}

        // setup tabs + pager
        pagerAdapter = SettingsPagerAdapter(this)
        settingsPager.adapter = pagerAdapter
        com.google.android.material.tabs.TabLayoutMediator(settingsTabs, settingsPager) { tab, position ->
            tab.text = if (position == 0) "MJPEG" else "AVC"
        }.attach()

        // restore selected tab from prefs
        val selectedTab = prefs.getInt("selectedTab", 0).coerceIn(0, 1)
        settingsTabs.getTabAt(selectedTab)?.select()

        // persist tab selection changes
        settingsTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                try {
                    prefs.edit().putInt("selectedTab", tab?.position ?: 0).apply()
                } catch (_: Exception) {}
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        startButton.setOnClickListener {
            val host = hostEdit.text.toString().ifBlank { "0.0.0.0" }
            val port = portEdit.text.toString().toIntOrNull() ?: DEFAULT_PORT
            val width = widthEdit.text.toString().toIntOrNull() ?: 1920
            val height = heightEdit.text.toString().toIntOrNull() ?: 1080
            val camera = (cameraEdit.tag as? String) ?: cameraEdit.text.toString().ifBlank { "back" }
            val fps = (fpsSpinner.selectedItem as? String)?.toIntOrNull() ?: 25

            // read codec-specific settings from fragments
            val mjpegQuality = pagerAdapter?.getMjpegFragment()?.getJpegQuality() ?: 85
            val avcBitrateMbps = pagerAdapter?.getAvcFragment()?.getBitrateMbps()

            // determine which codec tab is selected
            val useAvc = settingsTabs.selectedTabPosition == 1

            val jpeg = mjpegQuality

            // Update settings manager
            settingsManager.setHost(host)
            settingsManager.setPort(port)
            settingsManager.setWidth(width)
            settingsManager.setHeight(height)
            settingsManager.setCamera(camera)
            settingsManager.setJpegQuality(jpeg)
            settingsManager.setFps(fps)
            settingsManager.setUseAvc(useAvc)

            // save current settings to prefs
            try {
                val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                p.putString("host", host)
                p.putInt("port", port)
                p.putInt("width", width)
                p.putInt("height", height)
                p.putString("camera", camera)
                p.putInt("jpegQuality", jpeg)
                p.putInt("fps", fps)
                p.putBoolean("useAvc", useAvc)
                if (avcBitrateMbps != null) p.putFloat("avcMbps", avcBitrateMbps)
                p.apply()
            } catch (_: Exception) {}

            if (!isStreaming) {
                // queue start params and ask for any missing permissions; auto-start when granted
                val b = android.os.Bundle()
                b.putString("host", host)
                b.putInt("port", port)
                b.putInt("width", width)
                b.putInt("height", height)
                b.putString("camera", camera)
                b.putInt("jpegQuality", jpeg)
                b.putInt("fps", fps)
                b.putBoolean("useAvc", useAvc)
                pendingStartBundle = b
                val startedImmediately = ensurePermissionsAndStart(host, port, width, height, camera, jpeg, fps, useAvc)
                if (startedImmediately) {
                    // optimistic UI update; service will also broadcast state
                    isStreaming = true
                    startButton.text = "Stop Server"
                    settingsManager.setStreaming(true)
                }
            } else {
                stopStreaming()
                startButton.text = "Start Server"
                isStreaming = false
                settingsManager.setStreaming(false)
                pendingStartBundle = null
            }
        }

        // Launch preview activity
        previewButton.setOnClickListener {
            // open preview without stopping the streaming service
            startActivity(Intent(this, CameraControlActivity::class.java))
        }
        // initialize startButton from saved streaming state and register receiver
        val wasStreaming = prefs.getBoolean("isStreaming", false)
        isStreaming = wasStreaming
        startButton.text = if (isStreaming) "Stop Server" else "Start Server"

        streamStateReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val running = intent?.getBooleanExtra("isStreaming", false) ?: false
                runOnUiThread {
                    isStreaming = running
                    startButton.text = if (running) "Stop Server" else "Start Server"
                }
            }
        }
        // register as not exported to comply with Android 14+ receiver requirements
        registerReceiver(streamStateReceiver, android.content.IntentFilter("com.example.handycam.STREAM_STATE"), Context.RECEIVER_NOT_EXPORTED)
        
        // HTTPS Server controls
        val httpsRunning = prefs.getBoolean("httpsServerRunning", false)
        isHttpsServerRunning = httpsRunning
        httpsServerButton.text = if (httpsRunning) "Stop HTTPS Server" else "Start HTTPS Server"
        httpsServerStatus.text = if (httpsRunning) {
            val port = prefs.getInt("httpsServerPort", 8443)
            "Server running on port $port"
        } else {
            "Server stopped"
        }
        
        httpsServerButton.setOnClickListener {
            if (isHttpsServerRunning) {
                stopHttpsServer()
                // Don't update UI here - let the broadcast receiver handle it
            } else {
                val port = httpsPortEdit.text.toString().toIntOrNull() ?: 8443
                startHttpsServer(port)
                // Don't update UI here - let the broadcast receiver handle it
            }
        }
        
        httpsServerStateReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val running = intent?.getBooleanExtra("isRunning", false) ?: false
                val port = intent?.getIntExtra("port", 8443) ?: 8443
                runOnUiThread {
                    isHttpsServerRunning = running
                    httpsServerButton.text = if (running) "Stop HTTPS Server" else "Start HTTPS Server"
                    httpsServerStatus.text = if (running) {
                        "Server running on port $port\nAccess at: http://localhost:$port"
                    } else {
                        "Server stopped"
                    }
                }
            }
        }
        registerReceiver(httpsServerStateReceiver, android.content.IntentFilter("com.example.handycam.HTTPS_SERVER_STATE"), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { streamStateReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { httpsServerStateReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
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

    private fun ensurePermissionsAndStart(bindHost: String, port: Int, width: Int, height: Int, camera: String, jpegQuality: Int, targetFps: Int, useAvc: Boolean): Boolean {
        // Ensure CAMERA permission
        val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!camGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return false
        }

        // Ensure FOREGROUND_SERVICE_CAMERA permission (Android 14+ requirement when targeting newer SDKs)
        val fgPerm = "android.permission.FOREGROUND_SERVICE_CAMERA"
        if (Build.VERSION.SDK_INT >= 34) {
            val fgGranted = ContextCompat.checkSelfPermission(this, fgPerm) == PackageManager.PERMISSION_GRANTED
            if (!fgGranted) {
                fgCameraPermissionLauncher.launch(fgPerm)
                return false
            }
        }

        // Ensure POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return false
            }
        }

        // All required permissions present — start the service
        startStreaming(bindHost, port, width, height, camera, jpegQuality, targetFps, useAvc)
        return true
    }

    private fun stopStreaming() {
        val intent = Intent(this, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_STOP"
        }
        startService(intent)
    }
    
    private fun startHttpsServer(port: Int) {
        val intent = Intent(this, KtorHttpsServerService::class.java).apply {
            action = "com.example.handycam.ACTION_START_HTTPS_SERVER"
            putExtra("port", port)
        }
        ContextCompat.startForegroundService(this, intent)
    }
    
    private fun stopHttpsServer() {
        val intent = Intent(this, KtorHttpsServerService::class.java).apply {
            action = "com.example.handycam.ACTION_STOP_HTTPS_SERVER"
        }
        startService(intent)
    }

}
