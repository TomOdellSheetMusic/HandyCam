package com.example.handycam.presentation.cameracontrol

import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraControlScreen(
    viewModel: CameraControlViewModel,
    onBack: () -> Unit,
) {
    val isStreaming by viewModel.isStreaming.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()
    val exposure by viewModel.exposure.collectAsState()
    val zoom by viewModel.zoom.collectAsState()
    val autoExposure by viewModel.autoExposure.collectAsState()
    val whiteBalance by viewModel.whiteBalance.collectAsState()
    val whiteBalanceLocked by viewModel.whiteBalanceLocked.collectAsState()
    val iso by viewModel.iso.collectAsState()
    val isoLocked by viewModel.isoLocked.collectAsState()
    val shutterSpeedNs by viewModel.shutterSpeedNs.collectAsState()
    val shutterLocked by viewModel.shutterLocked.collectAsState()
    val zoomLocked by viewModel.zoomLocked.collectAsState()
    val gridEnabled by viewModel.gridEnabled.collectAsState()
    val cameras by viewModel.availableCameras.collectAsState()
    val useAvc by viewModel.useAvc.collectAsState()

    var showEvLabel by remember { mutableStateOf(false) }
    var focusRingVisible by remember { mutableStateOf(false) }
    var focusRingOffset by remember { mutableStateOf(Offset.Zero) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    var cameraDropdownOpen by remember { mutableStateOf(false) }
    var selectedCamera by remember(cameras) { mutableStateOf(cameras.firstOrNull()) }

    // Touch gesture state
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var dragStartExposure by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var whiteBalanceMenuOpen by remember { mutableStateOf(false) }

    val whiteBalanceModes = remember {
        listOf(
            1 to "Auto",
            2 to "Incandescent",
            3 to "Fluorescent",
            4 to "Warm fluorescent",
            5 to "Daylight",
            6 to "Cloudy daylight",
            7 to "Twilight",
            8 to "Shade",
        )
    }

    fun shutterLabel(valueNs: Long): String {
        if (valueNs <= 0L) return "Auto"
        val seconds = valueNs / 1_000_000_000.0
        return if (seconds >= 1.0) "${"%.1f".format(seconds)}s" else "1/${(1.0 / seconds).roundToInt().coerceAtLeast(1)}"
    }

    // EV and focus ring auto-hide
    LaunchedEffect(showEvLabel) { if (showEvLabel) { delay(1500); showEvLabel = false } }
    LaunchedEffect(focusRingVisible) { if (focusRingVisible) { delay(700); focusRingVisible = false } }

    // Go back when stream stops
    LaunchedEffect(isStreaming) { if (!isStreaming) onBack() }

    // Clean up preview surface on exit
    DisposableEffect(useAvc) {
        onDispose {
            if (useAvc) viewModel.setPreviewSurface(null)
            else viewModel.setPreviewSurfaceProvider(null)
        }
    }

    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Camera preview ────────────────────────────────────────────
        if (useAvc) {
            // AVC: Camera2 owns the camera — use a SurfaceView whose Surface is
            // added directly to the Camera2 capture session (no CameraX conflict).
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also { sv ->
                        sv.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                viewModel.setPreviewSurface(holder.surface)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                                viewModel.setPreviewSurface(holder.surface)
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                viewModel.setPreviewSurface(null)
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { pv ->
                        previewViewRef = pv
                        viewModel.setPreviewSurfaceProvider(pv.surfaceProvider)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (gridEnabled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val thirdWidth = size.width / 3f
                val thirdHeight = size.height / 3f
                for (i in 1..2) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.38f),
                        start = Offset(thirdWidth * i, 0f),
                        end = Offset(thirdWidth * i, size.height),
                        strokeWidth = 1.5f
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.38f),
                        start = Offset(0f, thirdHeight * i),
                        end = Offset(size.width, thirdHeight * i),
                        strokeWidth = 1.5f
                    )
                }
            }
        }

        // ── Gesture layer (tap-to-focus, drag-to-EV, pinch-to-zoom) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Pinch-to-zoom via scale gesture
                    awaitPointerEventScope {
                        var lastSpan = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.filter { it.pressed }

                            if (pointers.size == 2) {
                                // Pinch gesture
                                if (!zoomLocked) {
                                    val p0 = pointers[0].position
                                    val p1 = pointers[1].position
                                    val span = (p0 - p1).getDistance()
                                    if (lastSpan > 0f) {
                                        val scaleFactor = span / lastSpan
                                        val newZoom = (viewModel.zoom.value + (scaleFactor - 1f) * 0.5f).coerceIn(0f, 1f)
                                        viewModel.setZoom(newZoom)
                                    }
                                    lastSpan = span
                                    pointers.forEach { it.consume() }
                                }
                            } else {
                                lastSpan = 0f
                                val change = event.changes.firstOrNull() ?: continue

                                when {
                                    change.pressed && !change.previousPressed -> {
                                        dragStartY = change.position.y
                                        dragStartExposure = viewModel.exposure.value
                                        isDragging = false
                                    }
                                    change.pressed && change.previousPressed -> {
                                        val dy = dragStartY - change.position.y
                                        if (autoExposure && kotlin.math.abs(dy) > 40) {
                                            isDragging = true
                                            val range = viewModel.cameraStateHolder.cameraInfo
                                                ?.exposureState?.exposureCompensationRange
                                            if (range != null) {
                                                val span2 = range.upper - range.lower
                                                val delta = ((dy / size.height) * span2).toInt()
                                                val newEv = (dragStartExposure + delta).coerceIn(range.lower, range.upper)
                                                viewModel.setExposure(newEv)
                                                showEvLabel = true
                                            }
                                            change.consume()
                                        }
                                    }
                                    !change.pressed && change.previousPressed -> {
                                        if (!isDragging) {
                                            // Tap-to-focus
                                            val pv = previewViewRef ?: return@awaitPointerEventScope
                                            val point = pv.meteringPointFactory.createPoint(
                                                change.position.x, change.position.y
                                            )
                                            viewModel.tapToFocus(
                                                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                                    .setAutoCancelDuration(3_000, TimeUnit.MILLISECONDS)
                                                    .build()
                                            )
                                            focusRingOffset = change.position
                                            focusRingVisible = true
                                        }
                                        isDragging = false
                                    }
                                }
                            }
                        }
                    }
                }
        )

        // ── Focus ring ────────────────────────────────────────────────
        AnimatedVisibility(
            visible = focusRingVisible,
            enter = scaleIn(initialScale = 0.5f) + fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.offset {
                IntOffset(
                    (focusRingOffset.x - with(density) { 48.dp.toPx() }).roundToInt(),
                    (focusRingOffset.y - with(density) { 48.dp.toPx() }).roundToInt()
                )
            }
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .border(2.dp, Color.White, CircleShape)
            )
        }

        // ── Top bar ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            Spacer(Modifier.weight(1f))

            // Torch toggle
            IconButton(onClick = { viewModel.toggleTorch() }) {
                Icon(
                    imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "Toggle torch",
                    tint = if (torchEnabled) Color.Yellow else Color.White
                )
            }

            // Camera switcher
            ExposedDropdownMenuBox(
                expanded = cameraDropdownOpen,
                onExpandedChange = { cameraDropdownOpen = it }
            ) {
                TextButton(
                    onClick = { cameraDropdownOpen = true },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = selectedCamera?.let {
                            when (it.facing) {
                                "front" -> "Front"
                                "back" -> "Back"
                                else -> it.id
                            }
                        } ?: "Camera",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                ExposedDropdownMenu(expanded = cameraDropdownOpen, onDismissRequest = { cameraDropdownOpen = false }) {
                    cameras.forEach { cam ->
                        DropdownMenuItem(
                            text = { Text(cam.displayName) },
                            onClick = {
                                selectedCamera = cam
                                viewModel.switchCamera(cam.id)
                                cameraDropdownOpen = false
                            }
                        )
                    }
                }
            }
        }

        // ── EV label ──────────────────────────────────────────────────
        AnimatedVisibility(
                visible = showEvLabel && autoExposure,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        ) {
            Surface(
                color = Color(0xCC000000),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (exposure >= 0) "EV +$exposure" else "EV $exposure",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // ── Pro controls panel ───────────────────────────────────────
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 8.dp),
            color = Color(0xC0181B22),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (useAvc) "Pro controls" else "Basic controls",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = if (useAvc) "AVC mode enables Camera2 manual controls. On some cameras, ISO and shutter stay in auto if manual sensor mode is unsupported." else "CameraX mode only supports torch, zoom, tap-to-focus, and exposure compensation in this screen.",
                    color = Color(0xB3FFFFFF),
                    style = MaterialTheme.typography.bodySmall
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = autoExposure, onClick = { viewModel.setAutoExposure(!autoExposure) }, label = { Text(if (autoExposure) "Exposure auto" else "Exposure manual") })
                    FilterChip(selected = whiteBalanceLocked, onClick = { viewModel.setWhiteBalanceLocked(!whiteBalanceLocked) }, label = { Text(if (whiteBalanceLocked) "WB locked" else "WB lock") })
                    FilterChip(selected = isoLocked, onClick = { viewModel.setIsoLocked(!isoLocked) }, label = { Text(if (isoLocked) "ISO locked" else "ISO lock") })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = shutterLocked, onClick = { viewModel.setShutterLocked(!shutterLocked) }, label = { Text(if (shutterLocked) "Shutter locked" else "Shutter lock") })
                    FilterChip(selected = zoomLocked, onClick = { viewModel.setZoomLocked(!zoomLocked) }, label = { Text(if (zoomLocked) "Zoom locked" else "Zoom lock") })
                    FilterChip(selected = gridEnabled, onClick = { viewModel.setGridEnabled(!gridEnabled) }, label = { Text(if (gridEnabled) "Grid on" else "Grid") })
                }

                if (autoExposure) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Exposure compensation", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = if (exposure >= 0) "EV +$exposure" else "EV $exposure",
                            color = Color(0xB3FFFFFF),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Slider(
                            value = exposure.toFloat(),
                            onValueChange = { viewModel.setExposure(it.roundToInt()) },
                            valueRange = -12f..12f,
                            enabled = useAvc,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("White balance", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        ExposedDropdownMenuBox(expanded = whiteBalanceMenuOpen, onExpandedChange = { whiteBalanceMenuOpen = it }) {
                            OutlinedTextField(
                                value = whiteBalanceModes.firstOrNull { it.first == whiteBalance }?.second ?: whiteBalance.toString(),
                                onValueChange = {},
                                readOnly = true,
                                enabled = useAvc,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = whiteBalanceMenuOpen) }
                            )
                            ExposedDropdownMenu(expanded = whiteBalanceMenuOpen, onDismissRequest = { whiteBalanceMenuOpen = false }) {
                                whiteBalanceModes.forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            viewModel.setWhiteBalance(mode)
                                            whiteBalanceMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Zoom", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        val minZoom = viewModel.cameraStateHolder.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
                        val maxZoom = viewModel.cameraStateHolder.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 8f
                        val zoomRatio = minZoom + (maxZoom - minZoom) * zoom
                        Text("%.1fx".format(zoomRatio), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = zoom,
                            onValueChange = { if (!zoomLocked) viewModel.setZoom(it) },
                            enabled = useAvc && !zoomLocked,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
                        )
                    }
                }

                if (!autoExposure) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ISO", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Text(if (iso > 0) iso.toString() else "Auto", color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = maxOf(iso, 100).toFloat(),
                            onValueChange = { viewModel.setIso(it.roundToInt()) },
                            valueRange = 100f..3200f,
                            steps = 30,
                            enabled = useAvc && isoLocked,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Shutter", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Text(shutterLabel(shutterSpeedNs), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = maxOf(shutterSpeedNs, 250_000L).toFloat(),
                            onValueChange = { viewModel.setShutterSpeedNs(it.toLong()) },
                            valueRange = 250_000f..66_000_000f,
                            enabled = useAvc && shutterLocked,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
                        )
                    }
                }

                Text(
                    text = if (autoExposure) "Tip: drag on the preview for EV, tap to focus, pinch to zoom." else "Tip: manual exposure uses ISO and shutter sliders.",
                    color = Color(0x99FFFFFF),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.setPreviewSurfaceProvider(null) }
    }
}

