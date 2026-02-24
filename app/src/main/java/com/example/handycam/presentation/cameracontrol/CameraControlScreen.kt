package com.example.handycam.presentation.cameracontrol

import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
    val cameras by viewModel.availableCameras.collectAsState()

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

    // EV and focus ring auto-hide
    LaunchedEffect(showEvLabel) { if (showEvLabel) { delay(1500); showEvLabel = false } }
    LaunchedEffect(focusRingVisible) { if (focusRingVisible) { delay(700); focusRingVisible = false } }

    // Go back when stream stops
    LaunchedEffect(isStreaming) { if (!isStreaming) onBack() }

    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Camera preview ────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { pv ->
                    previewViewRef = pv
                    viewModel.setPreviewSurfaceProvider(pv.surfaceProvider)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

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
                                        if (kotlin.math.abs(dy) > 40) {
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
            visible = showEvLabel,
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

        // ── Hint: drag to adjust exposure ────────────────────────────
        if (!showEvLabel && !isDragging) {
            Text(
                "↕ drag for exposure · tap to focus · pinch to zoom",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0x99FFFFFF),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
            )
        }

        // ── Zoom bar ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val minZoom = viewModel.cameraStateHolder.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
            val maxZoom = viewModel.cameraStateHolder.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 8f
            val zoomRatio = minZoom + (maxZoom - minZoom) * zoom
            Text(
                "%.1fx".format(zoomRatio),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(40.dp)
            )
            Slider(
                value = zoom,
                onValueChange = { viewModel.setZoom(it) },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.setPreviewSurfaceProvider(null) }
    }
}

