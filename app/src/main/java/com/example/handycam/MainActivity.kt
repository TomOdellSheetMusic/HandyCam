package com.example.handycam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.handycam.presentation.cameracontrol.CameraControlScreen
import com.example.handycam.presentation.cameracontrol.CameraControlViewModel
import com.example.handycam.presentation.main.MainScreen
import com.example.handycam.presentation.main.MainViewModel
import com.example.handycam.ui.theme.HandyCamTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val mainViewModel: MainViewModel by viewModels()
    private val cameraControlViewModel: CameraControlViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled; services start independently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()
        setContent {
            HandyCamTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            viewModel = mainViewModel,
                            onNavigateToCameraControl = { navController.navigate("camera_control") }
                        )
                    }
                    composable("camera_control") {
                        CameraControlScreen(
                            viewModel = cameraControlViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        maybeStartPendingRemoteStream()
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            perms.add("android.permission.FOREGROUND_SERVICE_CAMERA")
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun maybeStartPendingRemoteStream() {
        val prefs = getSharedPreferences(RemoteStartKeys.PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(RemoteStartKeys.KEY_PENDING_REMOTE_START, false)
        if (!pending) return

        prefs.edit().putBoolean(RemoteStartKeys.KEY_PENDING_REMOTE_START, false).apply()

        val intent = Intent(this, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_START"
            putExtra("host", mainViewModel.streamStateHolder.host.value)
            putExtra("port", mainViewModel.streamStateHolder.port.value)
            putExtra("width", mainViewModel.streamStateHolder.width.value)
            putExtra("height", mainViewModel.streamStateHolder.height.value)
            putExtra("camera", mainViewModel.streamStateHolder.camera.value)
            putExtra("jpegQuality", mainViewModel.streamStateHolder.jpegQuality.value)
            putExtra("targetFps", mainViewModel.streamStateHolder.fps.value)
            putExtra("useAvc", mainViewModel.streamStateHolder.useAvc.value)
            putExtra("avcBitrate", mainViewModel.streamStateHolder.avcBitrate.value)
        }
        try {
            startForegroundService(intent)
            Log.i(TAG, "Started deferred remote stream request on resume")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start deferred remote stream", e)
        }
    }
}
