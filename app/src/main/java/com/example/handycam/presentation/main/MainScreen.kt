package com.example.handycam.presentation.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.ClipData
import android.media.projection.MediaProjectionManager
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FPS_OPTIONS = listOf(15, 30, 60, 120, 240)

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
    val isScreenStreaming by viewModel.streamStateHolder.useScreenCapture.collectAsState()
    val hostValue by viewModel.streamStateHolder.host.collectAsState()
    val streamingPort by viewModel.streamStateHolder.streamingPort.collectAsState()
    val width by viewModel.streamStateHolder.width.collectAsState()
    val height by viewModel.streamStateHolder.height.collectAsState()
    val selectedCameraId by viewModel.streamStateHolder.camera.collectAsState()
    val fpsValue by viewModel.streamStateHolder.fps.collectAsState()
    val jpegQualityValue by viewModel.streamStateHolder.jpegQuality.collectAsState()
    val useAvc by viewModel.streamStateHolder.useAvc.collectAsState()
    val useHevc by viewModel.streamStateHolder.useHevc.collectAsState()
    val useHdr by viewModel.streamStateHolder.useHdr.collectAsState()
    val avcBitrateValue by viewModel.streamStateHolder.avcBitrate.collectAsState()

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val mediaProjectionManager = remember {
        context.getSystemService(MediaProjectionManager::class.java)
    }

    val host = viewModel.localIp
    var port by remember(streamingPort) { mutableStateOf(streamingPort.toString()) }
    val matchingPreset = remember(width, height) {
        RESOLUTION_PRESETS.firstOrNull { it.label != "Custom" && it.width == width && it.height == height }
    }
    var selectedPreset by remember { mutableStateOf(matchingPreset ?: RESOLUTION_PRESETS.last()) }
    var customWidth by remember(width) { mutableStateOf(width.toString()) }
    var customHeight by remember(height) { mutableStateOf(height.toString()) }
    val selectedCamera = remember(cameras, selectedCameraId) {
        cameras.firstOrNull { it.id == selectedCameraId }
    }
    var jpegQuality by remember(jpegQualityValue) { mutableFloatStateOf(jpegQualityValue.toFloat()) }
    var avcBitrate by remember(avcBitrateValue) {
        mutableStateOf(if (avcBitrateValue > 0) (avcBitrateValue / 1_000_000f).toString() else "")
    }
    var httpPort by remember { mutableStateOf("8080") }

    var cameraDropdownOpen by remember { mutableStateOf(false) }
    val isScreenSource = isScreenStreaming
    val maxFps = remember(selectedCameraId, width, height, useAvc, isScreenSource) {
        if (isScreenSource) 60 else viewModel.maxStreamingFps(selectedCameraId, width, height, useAvc)
    }
    LaunchedEffect(maxFps, fpsValue, isStreaming) {
        if (!isStreaming && fpsValue > maxFps) viewModel.updateFps(maxFps)
    }
    LaunchedEffect(width, height) {
        selectedPreset = matchingPreset ?: RESOLUTION_PRESETS.last()
    }

    // Capture resolved (w, h) once so the screen capture launcher can also use it
    val resolvedWidth by remember { derivedStateOf {
        if (selectedPreset.label != "Custom") selectedPreset.width
        else customWidth.toIntOrNull() ?: 1920
    } }
    val resolvedHeight by remember { derivedStateOf {
        if (selectedPreset.label != "Custom") selectedPreset.height
        else customHeight.toIntOrNull() ?: 1080
    } }

    // Launcher for MediaProjection consent dialog
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resolvedPort = port.toIntOrNull() ?: streamingPort
            val resolvedAvcBitrate = avcBitrate.toFloatOrNull()?.let { (it * 1_000_000).toInt() }
            viewModel.startStreaming(
                host = host.ifBlank { "0.0.0.0" },
                port = resolvedPort,
                width = resolvedWidth, height = resolvedHeight,
                camera = "screen",
                jpegQuality = jpegQuality.toInt(),
                fps = fpsValue,
                useAvc = useAvc,
                avcBitrate = resolvedAvcBitrate,
                useScreenCapture = true,
                mediaProjectionResultCode = result.resultCode,
                mediaProjectionData = result.data
            )
        }
    }

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(2f)) {
                        Text("IP Address", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(host, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                coroutineScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("IP address", host))) }
                            },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy IP",
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                        Text("Your phone's Wi-Fi IP", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it
                            val parsedPort = it.toIntOrNull()
                            if (parsedPort != null) viewModel.updateStreamingPort(parsedPort)
                        },
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
                if (!isScreenSource) {
                    Text("Resolution", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        RESOLUTION_PRESETS.forEachIndexed { i, preset ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(i, RESOLUTION_PRESETS.size),
                                selected = selectedPreset == preset,
                                enabled = !isStreaming && !isScreenSource,
                                onClick = {
                                    if (!isStreaming) {
                                        selectedPreset = preset
                                        if (preset.label != "Custom") {
                                            viewModel.updateResolution(preset.width, preset.height)
                                        } else {
                                            customWidth = width.toString()
                                            customHeight = height.toString()
                                        }
                                    }
                                },
                                icon = { if (preset.label != "Custom") SegmentedButtonDefaults.Icon(active = selectedPreset == preset) },
                                label = { Text(preset.label, maxLines = 1) }
                            )
                        }
                    }

                    if (selectedPreset.label == "Custom") {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customWidth,
                                onValueChange = {
                                    customWidth = it
                                    val parsedWidth = it.toIntOrNull()
                                    val parsedHeight = customHeight.toIntOrNull()
                                    if (parsedWidth != null && parsedHeight != null) {
                                        viewModel.updateResolution(parsedWidth, parsedHeight)
                                    }
                                },
                                label = { Text("Width") }, modifier = Modifier.weight(1f),
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !isStreaming && !isScreenSource
                            )
                            OutlinedTextField(
                                value = customHeight,
                                onValueChange = {
                                    customHeight = it
                                    val parsedWidth = customWidth.toIntOrNull()
                                    val parsedHeight = it.toIntOrNull()
                                    if (parsedWidth != null && parsedHeight != null) {
                                        viewModel.updateResolution(parsedWidth, parsedHeight)
                                    }
                                },
                                label = { Text("Height") }, modifier = Modifier.weight(1f),
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !isStreaming && !isScreenSource
                            )
                        }
                    }

                }

                Spacer(Modifier.height(4.dp))

                // FPS
                Text("Frame rate", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val fpsPresets = (FPS_OPTIONS.filter { it <= maxFps } + maxFps).distinct().sorted()
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    fpsPresets.forEach { fps ->
                        FilterChip(
                            selected = fpsValue == fps,
                            onClick = { viewModel.updateFps(fps) },
                            label = { Text("$fps fps") },
                            enabled = !isStreaming,
                        )
                    }
                }
                Text("Target: $fpsValue fps", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = fpsValue.coerceIn(1, maxFps).toFloat(),
                    onValueChange = { value ->
                        val fps = value.roundToInt().coerceIn(1, maxFps)
                        if (fps != fpsValue) viewModel.updateFps(fps)
                    },
                    valueRange = 1f..maxFps.toFloat(),
                    enabled = !isStreaming,
                    thumb = {
                        Box(Modifier.size(20.dp).background(
                            if (isStreaming) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else MaterialTheme.colorScheme.primary,
                            CircleShape,
                        ))
                    },
                )
                Text("1–$maxFps fps · Actual frame rate may vary", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(4.dp))

                // Source type: Camera or Screen
                Text("Source", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        selected = !isScreenSource,
                        onClick = {
                            if (!isStreaming) {
                                viewModel.updateUseScreenCapture(false)
                                if (selectedCamera == null) {
                                    cameras.firstOrNull()?.let { viewModel.updateCamera(it.id) }
                                }
                            }
                        },
                        label = { Text("Camera") }
                    )
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        selected = isScreenSource,
                        onClick = { if (!isStreaming) viewModel.updateUseScreenCapture(true) },
                        label = { Text("Screen") }
                    )
                }

                // Camera picker — only shown in camera mode
                AnimatedVisibility(visible = !isScreenSource) {
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
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                            enabled = !isStreaming
                        )
                        ExposedDropdownMenu(expanded = cameraDropdownOpen, onDismissRequest = { cameraDropdownOpen = false }) {
                            cameras.forEach { cam ->
                                DropdownMenuItem(
                                    text = { Text(cam.displayName) },
                                    onClick = {
                                        viewModel.updateCamera(cam.id)
                                        cameraDropdownOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── Codec ─────────────────────────────────────────────────
            SectionCard(title = "Codec") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(0, 3),
                        selected = !useAvc,
                        onClick = { if (!isStreaming) { viewModel.updateUseHevc(false); viewModel.updateUseAvc(false) } },
                        label = { Text("MJPEG") }
                    )
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(1, 3),
                        selected = useAvc && !useHevc,
                        onClick = { if (!isStreaming) { viewModel.updateUseHevc(false); viewModel.updateUseAvc(true) } },
                        label = { Text("H.264") }
                    )
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(2, 3),
                        selected = useAvc && useHevc,
                        onClick = { if (!isStreaming) { viewModel.updateUseHevc(true); viewModel.updateUseAvc(true) } },
                        label = { Text("HEVC") }
                    )
                }
                if (useAvc && useHevc && !isScreenSource) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useHdr, onCheckedChange = { viewModel.streamStateHolder.setUseHdr(it) }, enabled = !isStreaming)
                        Text("HDR (HLG10, Android 13+)")
                    }
                    Text("Requires an HLG10 camera and HEVC Main10 encoder. Select HDR in OBS. Camera preview is unavailable in HDR mode.", style = MaterialTheme.typography.bodySmall)
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
                            onValueChange = {
                                if (!isStreaming) {
                                    jpegQuality = it
                                    viewModel.updateJpegQuality(it.toInt())
                                }
                            },
                            valueRange = 10f..100f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = avcBitrate,
                        onValueChange = {
                            avcBitrate = it
                            val parsed = it.toFloatOrNull()?.let { value -> (value * 1_000_000).toInt() }
                            viewModel.updateAvcBitrate(parsed)
                        },
                        label = { Text("Bitrate (Mbps)") },
                        supportingText = { Text("Leave blank for auto") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        enabled = !isStreaming
                    )
                }
            }

            // ── Start / Stop ──────────────────────────────────────────
            Text(
                "Anyone on the network who can reach this device can access the video and audio stream. Use a trusted network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Button(
                onClick = {
                    if (isStreaming) viewModel.stopStreaming()
                    else if (isScreenSource) {
                        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    } else {
                        val resolvedPort = port.toIntOrNull() ?: streamingPort
                        val resolvedAvcBitrate = avcBitrate.toFloatOrNull()?.let { (it * 1_000_000).toInt() }
                        val resolvedCamera = selectedCamera?.id ?: cameras.firstOrNull()?.id ?: "back"
                        viewModel.startStreaming(
                            host = host.ifBlank { "0.0.0.0" },
                            port = resolvedPort,
                            width = resolvedWidth, height = resolvedHeight,
                            camera = resolvedCamera,
                            jpegQuality = jpegQuality.toInt(),
                            fps = fpsValue,
                            useAvc = useAvc,
                            avcBitrate = resolvedAvcBitrate
                        )
                    }
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

            AnimatedVisibility(visible = isStreaming && !isScreenStreaming) {
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
                    "Anyone who can reach the HTTP Server can control the camera. Use a trusted network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = httpPort, onValueChange = { httpPort = it },
                        label = { Text("Port") },
                        modifier = Modifier.width(120.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !httpsRunning
                    )
                    Button(
                        onClick = {
                            if (httpsRunning) viewModel.stopHttpsServer()
                            else viewModel.startHttpsServer(httpPort.toIntOrNull() ?: 8443)
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
                    val serverUrl = "http://$host:$httpPort/camera"
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
                            onClick = {
                                coroutineScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Server URL", serverUrl))) }
                            },
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

