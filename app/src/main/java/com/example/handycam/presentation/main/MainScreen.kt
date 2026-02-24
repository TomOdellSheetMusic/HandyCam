package com.example.handycam.presentation.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val FPS_OPTIONS = listOf("15", "24", "30", "50", "60")

private data class ResolutionPreset(val label: String, val width: Int, val height: Int)
private val RESOLUTION_PRESETS = listOf(
    ResolutionPreset("720p", 1280, 720),
    ResolutionPreset("1080p", 1920, 1080),
    ResolutionPreset("4K", 3840, 2160),
    ResolutionPreset("Custom", 0, 0),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToCameraControl: () -> Unit,
) {
    val isStreaming by viewModel.isStreaming.collectAsState()
    val httpsRunning by viewModel.httpsRunning.collectAsState()
    val cameras by viewModel.availableCameras.collectAsState()

    var host by remember { mutableStateOf(viewModel.localIp) }
    var port by remember { mutableStateOf("4747") }
    var selectedPreset by remember { mutableStateOf(RESOLUTION_PRESETS[1]) } // default 1080p
    var customWidth by remember { mutableStateOf("1920") }
    var customHeight by remember { mutableStateOf("1080") }
    var selectedCamera by remember(cameras) { mutableStateOf(cameras.firstOrNull()) }
    var selectedFps by remember { mutableStateOf("30") }
    var jpegQuality by remember { mutableFloatStateOf(85f) }
    var avcBitrate by remember { mutableStateOf("") }
    var useAvc by remember { mutableStateOf(false) }
    var httpsPort by remember { mutableStateOf("8443") }

    var cameraDropdownOpen by remember { mutableStateOf(false) }

    // Pulse animation for live indicator
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.3f, label = "alpha",
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HandyCam", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (isStreaming) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Icon(
                                Icons.Filled.Circle,
                                contentDescription = null,
                                tint = Color.Red.copy(alpha = pulseAlpha),
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("LIVE", style = MaterialTheme.typography.labelMedium, color = Color.Red)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Connection ────────────────────────────────────────────
            SectionCard(title = "Connection") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("IP Address") },
                        supportingText = { Text("Your phone's Wi-Fi IP") },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        enabled = !isStreaming
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        supportingText = { Text("4747") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isStreaming
                    )
                }
            }

            // ── Video ─────────────────────────────────────────────────
            SectionCard(title = "Video") {
                // Resolution presets
                Text("Resolution", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    RESOLUTION_PRESETS.forEachIndexed { i, preset ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(i, RESOLUTION_PRESETS.size),
                            selected = selectedPreset == preset,
                            onClick = { if (!isStreaming) selectedPreset = preset },
                            label = { Text(preset.label) }
                        )
                    }
                }
                if (selectedPreset.label == "Custom") {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customWidth, onValueChange = { customWidth = it },
                            label = { Text("Width") }, modifier = Modifier.weight(1f),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !isStreaming
                        )
                        OutlinedTextField(
                            value = customHeight, onValueChange = { customHeight = it },
                            label = { Text("Height") }, modifier = Modifier.weight(1f),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !isStreaming
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // FPS
                Text("Frame rate", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    FPS_OPTIONS.forEachIndexed { i, fps ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(i, FPS_OPTIONS.size),
                            selected = selectedFps == fps,
                            onClick = { if (!isStreaming) selectedFps = fps },
                            label = { Text("${fps}fps") }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Camera
                ExposedDropdownMenuBox(
                    expanded = cameraDropdownOpen,
                    onExpandedChange = { if (!isStreaming) cameraDropdownOpen = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedCamera?.displayName ?: "Select camera",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Camera") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cameraDropdownOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        enabled = !isStreaming
                    )
                    ExposedDropdownMenu(expanded = cameraDropdownOpen, onDismissRequest = { cameraDropdownOpen = false }) {
                        cameras.forEach { cam ->
                            DropdownMenuItem(
                                text = { Text(cam.displayName) },
                                onClick = { selectedCamera = cam; cameraDropdownOpen = false }
                            )
                        }
                    }
                }
            }

            // ── Codec ─────────────────────────────────────────────────
            SectionCard(title = "Codec") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        selected = !useAvc,
                        onClick = { if (!isStreaming) useAvc = false },
                        label = { Text("MJPEG") }
                    )
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        selected = useAvc,
                        onClick = { if (!isStreaming) useAvc = true },
                        label = { Text("H.264 (AVC)") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (!useAvc) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Quality: ${jpegQuality.toInt()}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(96.dp)
                        )
                        Slider(
                            value = jpegQuality,
                            onValueChange = { if (!isStreaming) jpegQuality = it },
                            valueRange = 10f..100f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = avcBitrate, onValueChange = { avcBitrate = it },
                        label = { Text("Bitrate (Mbps)") },
                        supportingText = { Text("Leave blank for auto") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        enabled = !isStreaming
                    )
                }
            }

            // ── Start / Stop ──────────────────────────────────────────
            val (width, height) = when {
                selectedPreset.label != "Custom" -> selectedPreset.width to selectedPreset.height
                else -> (customWidth.toIntOrNull() ?: 1920) to (customHeight.toIntOrNull() ?: 1080)
            }
            Button(
                onClick = {
                    if (isStreaming) viewModel.stopStreaming()
                    else viewModel.startStreaming(
                        host = host.ifBlank { "0.0.0.0" },
                        port = port.toIntOrNull() ?: 4747,
                        width = width, height = height,
                        camera = selectedCamera?.id ?: "back",
                        jpegQuality = jpegQuality.toInt(),
                        fps = selectedFps.toIntOrNull() ?: 30,
                        useAvc = useAvc,
                        avcBitrate = avcBitrate.toFloatOrNull()?.let { (it * 1_000_000).toInt() }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = if (isStreaming) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.buttonColors()
            ) {
                Icon(
                    if (isStreaming) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                    contentDescription = null, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isStreaming) "Stop Streaming" else "Start Streaming",
                    style = MaterialTheme.typography.labelLarge)
            }

            AnimatedVisibility(visible = isStreaming) {
                OutlinedButton(
                    onClick = onNavigateToCameraControl,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Preview & Controls")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── HTTPS server ──────────────────────────────────────────
            SectionCard(title = "Web Control Server") {
                Text(
                    "Start an HTTPS server to control the camera from a browser or the REST API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = httpsPort, onValueChange = { httpsPort = it },
                        label = { Text("Port") },
                        modifier = Modifier.width(120.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !httpsRunning
                    )
                    Button(
                        onClick = {
                            if (httpsRunning) viewModel.stopHttpsServer()
                            else viewModel.startHttpsServer(httpsPort.toIntOrNull() ?: 8443)
                        },
                        modifier = Modifier.weight(1f),
                        colors = if (httpsRunning) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ) else ButtonDefaults.buttonColors()
                    ) {
                        Text(if (httpsRunning) "Stop" else "Start")
                    }
                }
                if (httpsRunning) {
                    val serverUrl = "https://$host:$httpsPort/camera"
                    val clipboard = LocalClipboardManager.current
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(serverUrl)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy URL",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

