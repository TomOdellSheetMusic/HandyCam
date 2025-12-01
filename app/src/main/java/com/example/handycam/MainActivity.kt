package com.example.handycam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private const val DEFAULT_PORT = 4747

class MainActivity : ComponentActivity() {

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // no-op; we requested CAMERA early so the service can use it
    }

    private val fgCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted — nothing else to do here; user can press Start again or we could auto-start.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            StreamingUI()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun StreamingUI() {
        var host by remember { mutableStateOf("0.0.0.0") }
        var portStr by remember { mutableStateOf(DEFAULT_PORT.toString()) }
        var resW by remember { mutableStateOf("720") }
        var resH by remember { mutableStateOf("1280") }
        var isStreaming by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Bind host (0.0.0.0)") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = portStr, onValueChange = { portStr = it }, label = { Text("Port") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = resW, onValueChange = { resW = it }, label = { Text("Width") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = resH, onValueChange = { resH = it }, label = { Text("Height") })
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (!isStreaming) {
                    val port = portStr.toIntOrNull() ?: DEFAULT_PORT
                    ensurePermissionsAndStart(host, port, resW.toIntOrNull() ?: 1280, resH.toIntOrNull() ?: 720)
                } else {
                    stopStreaming()
                }
                isStreaming = !isStreaming
            }) {
                Text(if (!isStreaming) "Start Server" else "Stop Server")
            }
        }
    }

    private fun startStreaming(bindHost: String, port: Int, width: Int, height: Int) {
        val intent = Intent(this, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_START"
            putExtra("host", bindHost)
            putExtra("port", port)
            putExtra("width", width)
            putExtra("height", height)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun ensurePermissionsAndStart(bindHost: String, port: Int, width: Int, height: Int) {
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
        startStreaming(bindHost, port, width, height)
    }

    private fun stopStreaming() {
        val intent = Intent(this, StreamService::class.java).apply {
            action = "com.example.handycam.ACTION_STOP"
        }
        startService(intent)
    }

}
