package com.example.handycam

import android.Manifest
import android.content.BroadcastReceiver
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import com.google.android.material.tabs.TabLayout
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import com.example.handycam.databinding.ActivityMainBinding

private const val DEFAULT_PORT = 4747
private const val PREFS_NAME = "handy_prefs"

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
    private var streamStateReceiver: BroadcastReceiver? = null
    private var httpsServerStateReceiver: BroadcastReceiver? = null
    private var pendingStartBundle: Bundle? = null
    private var isHttpsServerRunning = false
    private var pagerAdapter: SettingsPagerAdapter? = null
    private lateinit var settingsManager: SettingsManager
    private lateinit var binding: ActivityMainBinding
    private val cameraList = mutableListOf<Pair<String, String>>()

    private fun tryStartPendingIfPermsGranted() {
        val b = pendingStartBundle ?: return
        val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val fgPerm = "android.permission.FOREGROUND_SERVICE_CAMERA"
        val fgGranted = if (Build.VERSION.SDK_INT >= 34) {
            ContextCompat.checkSelfPermission(this, fgPerm) == PackageManager.PERMISSION_GRANTED
        } else true
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
            startStreaming(host, port, width, height, camera, jpeg, fps, useAvc)
            isStreaming = true
            pendingStartBundle = null
            runOnUiThread { binding.startButton.text = getString(R.string.stop_server) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        settingsManager = SettingsManager.getInstance(this)

        setupObservers()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedHost = prefs.getString("host", null)
        val autoHost = if (!savedHost.isNullOrBlank() && savedHost != "0.0.0.0") savedHost else (getLocalIpAddress() ?: "0.0.0.0")
        binding.hostEdit.setText(autoHost)
        binding.portEdit.setText(prefs.getInt("port", DEFAULT_PORT).toString())
        binding.widthEdit.setText(prefs.getInt("width", 1080).toString())
        binding.heightEdit.setText(prefs.getInt("height", 1920).toString())
        val savedCamera = prefs.getString("camera", "back")

        val fpsChoices = listOf("15", "24", "30", "50", "60")
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsChoices).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.fpsSpinner.adapter = fpsAdapter
        val savedFps = prefs.getInt("fps", 60).toString()
        binding.fpsSpinner.setSelection(fpsChoices.indexOf(savedFps).coerceAtLeast(0))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        populateCameraList(savedCamera)

        pagerAdapter = SettingsPagerAdapter(this)
        binding.settingsPager.adapter = pagerAdapter
        com.google.android.material.tabs.TabLayoutMediator(binding.settingsTabLayout, binding.settingsPager) { tab, position ->
            tab.text = if (position == 0) "MJPEG" else "AVC"
        }.attach()

        val selectedTab = prefs.getInt("selectedTab", 0).coerceIn(0, 1)
        binding.settingsTabLayout.getTabAt(selectedTab)?.select()

        binding.settingsTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                prefs.edit().putInt("selectedTab", tab?.position ?: 0).apply()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.startButton.setOnClickListener {
            handleStartStopClick()
        }

        binding.previewButton.setOnClickListener {
            startActivity(Intent(this, CameraControlActivity::class.java))
        }

        val wasStreaming = prefs.getBoolean("isStreaming", false)
        isStreaming = wasStreaming
        binding.startButton.text = if (isStreaming) getString(R.string.stop_server) else getString(R.string.start_server)
        binding.previewButton.visibility = if (isStreaming) android.view.View.VISIBLE else android.view.View.GONE

        registerStreamStateReceiver()
        
        val httpsRunning = prefs.getBoolean("httpsServerRunning", false)
        isHttpsServerRunning = httpsRunning
        binding.httpsServerButton.text = if (httpsRunning) getString(R.string.stop_https_server) else getString(R.string.start_https_server)
        binding.httpsServerStatus.text = if (httpsRunning) {
            val port = prefs.getInt("httpsServerPort", 8443)
            getString(R.string.server_running_on_port, port)
        } else {
            getString(R.string.server_stopped)
        }
        
        binding.httpsServerButton.setOnClickListener {
            if (isHttpsServerRunning) {
                stopHttpsServer()
            } else {
                val port = binding.httpsPortEdit.text.toString().toIntOrNull() ?: 8443
                startHttpsServer(port)
            }
        }
        
        registerHttpsServerStateReceiver()
    }

    private fun setupObservers() {
        settingsManager.isStreaming.observe(this) { streaming ->
            binding.startButton.text = if (streaming) getString(R.string.stop_server) else getString(R.string.start_server)
            isStreaming = streaming
        }
        settingsManager.camera.observe(this) { camera ->
            val cameraLabel = cameraList.find { it.second == camera }?.first
            binding.cameraDropdown.setText(cameraLabel, false)
        }
        settingsManager.port.observe(this) { port ->
            if (binding.portEdit.text.toString().toIntOrNull() != port) {
                binding.portEdit.setText(port.toString())
            }
        }
        settingsManager.width.observe(this) { width ->
            if (binding.widthEdit.text.toString().toIntOrNull() != width) {
                binding.widthEdit.setText(width.toString())
            }
        }
        settingsManager.height.observe(this) { height ->
            if (binding.heightEdit.text.toString().toIntOrNull() != height) {
                binding.heightEdit.setText(height.toString())
            }
        }
        settingsManager.fps.observe(this) { fps ->
            val fpsChoices = listOf("15", "24", "30", "50", "60")
            val index = fpsChoices.indexOf(fps.toString())
            if (index >= 0 && binding.fpsSpinner.selectedItemPosition != index) {
                binding.fpsSpinner.setSelection(index)
            }
        }
        settingsManager.httpsRunning.observe(this) { httpsRunning ->
            binding.httpsServerButton.text = if (httpsRunning) getString(R.string.stop_https_server) else getString(R.string.start_https_server)
            binding.httpsServerStatus.text = if (httpsRunning) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val port = prefs.getInt("httpsServerPort", 8443)
                "Server running on port $port\nAccess at: https://" + settingsManager.host.value + ":" + settingsManager.port.value
            } else {
                getString(R.string.server_stopped)
            }
        }
    }

    private fun populateCameraList(savedCamera: String?) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraList.add(Pair("Back", "back"))
        cameraList.add(Pair("Front", "front"))
        cm.cameraIdList.forEach { id ->
            val chars = cm.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                else -> "unknown"
            }
            val focalArr = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalDesc = if (focalArr != null && focalArr.isNotEmpty()) {
                String.format(Locale.US, "f=%.1fmm", focalArr[0])
            } else ""

            val label = if (focalDesc.isNotEmpty()) "$id ($facing, $focalDesc)" else "$id ($facing)"
            cameraList.add(Pair(label, id))
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, cameraList.map { it.first })
        binding.cameraDropdown.setAdapter(adapter)

        val initialCamera = cameraList.find { it.second == savedCamera } ?: cameraList.first()
        binding.cameraDropdown.setText(initialCamera.first, false)
    }

    private fun handleStartStopClick() {
        val host = binding.hostEdit.text.toString().ifBlank { "0.0.0.0" }
        val port = binding.portEdit.text.toString().toIntOrNull() ?: DEFAULT_PORT
        val width = binding.widthEdit.text.toString().toIntOrNull() ?: 1920
        val height = binding.heightEdit.text.toString().toIntOrNull() ?: 1080
        val selectedCameraLabel = binding.cameraDropdown.text.toString()
        val camera = cameraList.find { it.first == selectedCameraLabel }?.second ?: "back"
        val fps = (binding.fpsSpinner.selectedItem as? String)?.toIntOrNull() ?: 60
        val mjpegQuality = pagerAdapter?.getMjpegFragment()?.getJpegQuality() ?: 85
        val avcBitrateMbps = pagerAdapter?.getAvcFragment()?.getBitrateMbps()
        val useAvc = binding.settingsTabLayout.selectedTabPosition == 1

        settingsManager.setHost(host)
        settingsManager.setPort(port)
        settingsManager.setWidth(width)
        settingsManager.setHeight(height)
        settingsManager.setCamera(camera)
        settingsManager.setJpegQuality(mjpegQuality)
        settingsManager.setFps(fps)
        settingsManager.setUseAvc(useAvc)

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("host", host)
            putInt("port", port)
            putInt("width", width)
            putInt("height", height)
            putString("camera", camera)
            putInt("jpegQuality", mjpegQuality)
            putInt("fps", fps)
            putBoolean("useAvc", useAvc)
            avcBitrateMbps?.let { putFloat("avcMbps", it) }
            apply()
        }

        if (!isStreaming) {
            val b = Bundle().apply {
                putString("host", host)
                putInt("port", port)
                putInt("width", width)
                putInt("height", height)
                putString("camera", camera)
                putInt("jpegQuality", mjpegQuality)
                putInt("fps", fps)
                putBoolean("useAvc", useAvc)
            }
            pendingStartBundle = b
            val startedImmediately = ensurePermissionsAndStart(host, port, width, height, camera, mjpegQuality, fps, useAvc)
            if (startedImmediately) {
                isStreaming = true
                binding.startButton.text = getString(R.string.stop_server)
                settingsManager.setStreaming(true)
            }
        } else {
            stopStreaming()
            binding.startButton.text = getString(R.string.start_server)
            isStreaming = false
            settingsManager.setStreaming(false)
            pendingStartBundle = null
        }
    }

    private fun registerStreamStateReceiver() {
        streamStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val running = intent?.getBooleanExtra("isStreaming", false) ?: false
                isStreaming = running
                binding.startButton.text = if (running) getString(R.string.stop_server) else getString(R.string.start_server)
                binding.previewButton.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        val intentFilter = android.content.IntentFilter("com.example.handycam.STREAM_STATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamStateReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(streamStateReceiver, intentFilter, null, null, Context.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun registerHttpsServerStateReceiver() {
        httpsServerStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val running = intent?.getBooleanExtra("isRunning", false) ?: false
                val port = intent?.getIntExtra("port", 8443) ?: 8443
                isHttpsServerRunning = running
                binding.httpsServerButton.text = if (running) getString(R.string.stop_https_server) else getString(R.string.start_https_server)
                binding.httpsServerStatus.text = if (running) {
                    getString(R.string.server_running_on_port_with_access, port, settingsManager.host.value, settingsManager.port.value)
                } else {
                    getString(R.string.server_stopped)
                }
            }
        }
        val intentFilter = android.content.IntentFilter("com.example.handycam.HTTPS_SERVER_STATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(httpsServerStateReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(httpsServerStateReceiver, intentFilter, null, null, Context.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamStateReceiver?.let { unregisterReceiver(it) }
        httpsServerStateReceiver?.let { unregisterReceiver(it) }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            val candidates = mutableListOf<String>()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (host != null) {
                            if (host.startsWith("192.") || host.startsWith("10.") || host.startsWith("172.")) return host
                            candidates.add(host)
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) return candidates.first()
        } catch (_: Exception) {}
        return null
    }

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
        val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!camGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return false
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val fgPerm = "android.permission.FOREGROUND_SERVICE_CAMERA"
            val fgGranted = ContextCompat.checkSelfPermission(this, fgPerm) == PackageManager.PERMISSION_GRANTED
            if (!fgGranted) {
                fgCameraPermissionLauncher.launch(fgPerm)
                return false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return false
            }
        }

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
