package com.example.handycam.presentation.cameracontrol

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.NumberPicker
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CameraControlScreen(
    viewModel: CameraControlViewModel,
    onBack: () -> Unit,
) {
    val isStreaming by viewModel.isStreaming.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()
    val exposure by viewModel.exposure.collectAsState()
    val zoom by viewModel.zoom.collectAsState()
    val autoFocus by viewModel.autoFocus.collectAsState()
    val autoExposure by viewModel.autoExposure.collectAsState()
    val selectedCameraId by viewModel.streamStateHolder.camera.collectAsState()
    val whiteBalance by viewModel.whiteBalance.collectAsState()
    val whiteBalanceLocked by viewModel.whiteBalanceLocked.collectAsState()
    val zoomLocked by viewModel.zoomLocked.collectAsState()
    val gridEnabled by viewModel.gridEnabled.collectAsState()
    val cameras by viewModel.availableCameras.collectAsState()
    val useAvc by viewModel.useAvc.collectAsState()
    val encoderWidth by viewModel.encoderWidth.collectAsState()
    val encoderHeight by viewModel.encoderHeight.collectAsState()
    val avcExposureMin by viewModel.exposureMin.collectAsState()
    val avcExposureMax by viewModel.exposureMax.collectAsState()

    var showEvLabel by remember { mutableStateOf(false) }
    var focusRingVisible by remember { mutableStateOf(false) }
    var focusRingOffset by remember { mutableStateOf(Offset.Zero) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var showZoomLabel by remember { mutableStateOf(false) }
    var previewProviderToken by remember { mutableLongStateOf(0L) }

    LaunchedEffect(showZoomLabel) { if (showZoomLabel) { delay(1500); showZoomLabel = false } }

    var cameraWheelOpen by remember { mutableStateOf(false) }

    var dragStartY by remember { mutableFloatStateOf(0f) }
    var dragStartExposure by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }

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

    val selectedCameraIndex = cameras.indexOfFirst { it.id == selectedCameraId }.let { if (it >= 0) it else 0 }

    val wbLockedUi = whiteBalanceLocked
    val zoomLockedUi = zoomLocked
    val focusLockedUi = !autoFocus
    val exposureLockedUi = !autoExposure

    fun shutterLabel(valueNs: Long): String {
        if (valueNs <= 0L) return "Auto"
        val seconds = valueNs / 1_000_000_000.0
        return if (seconds >= 1.0) "${"%.1f".format(seconds)}s" else "1/${(1.0 / seconds).roundToInt().coerceAtLeast(1)}"
    }

    LaunchedEffect(showEvLabel) { if (showEvLabel) { delay(1500); showEvLabel = false } }
    LaunchedEffect(focusRingVisible) { if (focusRingVisible) { delay(700); focusRingVisible = false } }
    LaunchedEffect(isStreaming) { if (!isStreaming) onBack() }

    DisposableEffect(useAvc) {
        onDispose {
            if (useAvc) viewModel.setPreviewSurface(null)
        }
    }

    val currentProvider by rememberUpdatedState(previewViewRef?.surfaceProvider)
    val currentToken by rememberUpdatedState(previewProviderToken)
    val currentUseAvc by rememberUpdatedState(useAvc)
    DisposableEffect(Unit) {
        onDispose {
            if (!currentUseAvc) {
                viewModel.clearPreviewSurfaceProvider(currentProvider, currentToken)
            }
        }
    }

    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        if (useAvc) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val containerWidth = constraints.maxWidth.toFloat()
                val containerHeight = constraints.maxHeight.toFloat()
                val videoWidth = encoderWidth.toFloat().takeIf { it > 0f }
                val videoHeight = encoderHeight.toFloat().takeIf { it > 0f }

                val (viewWidth, viewHeight) = if (videoWidth != null && videoHeight != null
                    && containerWidth > 0f && containerHeight > 0f
                ) {
                    val videoAspect = videoWidth / videoHeight
                    val containerAspect = containerWidth / containerHeight
                    if (videoAspect > containerAspect) {
                        val h = containerHeight
                        val w = h * videoAspect
                        w to h
                    } else {
                        val w = containerWidth
                        val h = w / videoAspect
                        w to h
                    }
                } else {
                    containerWidth to containerHeight
                }

                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).also { surfaceView ->
                            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    viewModel.setPreviewSurface(holder.surface)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    viewModel.setPreviewSurface(holder.surface)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    viewModel.setPreviewSurface(null)
                                }
                            })
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(
                            with(density) { viewWidth.toDp() },
                            with(density) { viewHeight.toDp() }
                        ),
                    update = { view ->
                        if (encoderWidth > 0 && encoderHeight > 0) {
                            view.holder.setFixedSize(encoderWidth, encoderHeight)
                        }
                        val layoutParams = view.layoutParams
                        if (layoutParams != null) {
                            layoutParams.width = viewWidth.roundToInt().coerceAtLeast(1)
                            layoutParams.height = viewHeight.roundToInt().coerceAtLeast(1)
                            view.layoutParams = layoutParams
                        }
                    }
                )
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { previewView ->
                        previewViewRef = previewView
                        previewProviderToken = viewModel.setPreviewSurfaceProvider(previewView.surfaceProvider)
                    }
                },
                update = { previewView ->
                    if (!useAvc) {
                        previewProviderToken = viewModel.setPreviewSurfaceProvider(previewView.surfaceProvider)
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
                    drawLine(Color.White.copy(alpha = 0.38f), Offset(thirdWidth * i, 0f), Offset(thirdWidth * i, size.height), 1.5f)
                    drawLine(Color.White.copy(alpha = 0.38f), Offset(0f, thirdHeight * i), Offset(size.width, thirdHeight * i), 1.5f)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var lastSpan = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.filter { it.pressed }

                            if (pointers.size == 2) {
                                if (!zoomLockedUi) {
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
                                    showZoomLabel = true
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
                                            val minEv = range?.lower ?: avcExposureMin
                                            val maxEv = range?.upper ?: avcExposureMax
                                            if (minEv != 0 || maxEv != 0) {
                                                val span = maxEv - minEv
                                                val delta = ((dy / size.height) * span).toInt()
                                                val newEv = (dragStartExposure + delta).coerceIn(minEv, maxEv)
                                                viewModel.setExposure(newEv)
                                                showEvLabel = true
                                            }
                                            change.consume()
                                        }
                                    }

                                    !change.pressed && change.previousPressed -> {
                                        if (!isDragging) {
                                            if (autoFocus) {
                                                if (useAvc) {
                                                    viewModel.tapToFocus(
                                                        change.position.x / size.width.toFloat().coerceAtLeast(1f),
                                                        change.position.y / size.height.toFloat().coerceAtLeast(1f)
                                                    )
                                                } else {
                                                    val previewView = previewViewRef ?: return@awaitPointerEventScope
                                                    val point = previewView.meteringPointFactory.createPoint(change.position.x, change.position.y)
                                                    viewModel.tapToFocus(
                                                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                                            .setAutoCancelDuration(3_000, TimeUnit.MILLISECONDS)
                                                            .build()
                                                    )
                                                }
                                                focusRingOffset = change.position
                                                focusRingVisible = true
                                            }
                                        }
                                        isDragging = false
                                    }
                                }
                            }
                        }
                    }
                }
        )

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { cameraWheelOpen = !cameraWheelOpen }) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Switch camera", tint = Color.White)
                }

                IconButton(onClick = { viewModel.toggleTorch() }) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = "Toggle torch",
                        tint = if (torchEnabled) Color.White else Color(0xFFFF5A5A)
                    )
                }

                IconButton(onClick = { viewModel.setGridEnabled(!gridEnabled) }) {
                    Icon(
                        imageVector = Icons.Filled.GridOn,
                        contentDescription = if (gridEnabled) "Disable grid" else "Enable grid",
                        tint = if (gridEnabled) Color.White else Color(0xFFFF5A5A)
                    )
                }

                IconButton(onClick = { showEvLabel = true; viewModel.setAutoExposure(!autoExposure) }) {
                    Icon(
                        imageVector = Icons.Filled.Exposure,
                        contentDescription = if (exposureLockedUi) "Exposure locked" else "Exposure unlocked",
                        tint = if (exposureLockedUi) Color(0xFFFF5A5A) else Color.White
                    )
                }

                IconButton(onClick = { showZoomLabel = true; viewModel.setZoomLocked(!zoomLockedUi) }) {
                    Icon(
                        imageVector = Icons.Filled.ZoomIn,
                        contentDescription = if (zoomLockedUi) "Zoom locked" else "Zoom unlocked",
                        tint = if (zoomLockedUi) Color(0xFFFF5A5A) else Color.White
                    )
                }

                IconButton(onClick = { viewModel.setAutoFocus(!autoFocus) }) {
                    Icon(
                        imageVector = if (focusLockedUi) Icons.Filled.CenterFocusWeak else Icons.Filled.CenterFocusStrong,
                        contentDescription = if (focusLockedUi) "Focus locked" else "Focus unlocked",
                        tint = if (focusLockedUi) Color(0xFFFF5A5A) else Color.White
                    )
                }

                IconButton(onClick = { viewModel.setWhiteBalanceLocked(!wbLockedUi) }) {
                    Icon(
                        imageVector = Icons.Filled.ColorLens,
                        contentDescription = if (wbLockedUi) "White balance locked" else "White balance unlocked",
                        tint = if (wbLockedUi) Color(0xFFFF5A5A) else Color.White
                    )
                }
            }
        }

        if (cameraWheelOpen) {
            Surface(
                color = Color(0xD11A1A1A),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 12.dp)
            ) {
                WheelPickerPanel(
                    title = "Camera",
                    entries = cameras.map { it.displayName },
                    selectedIndex = selectedCameraIndex,
                    onSelected = { index -> cameras.getOrNull(index)?.let { viewModel.switchCamera(it.id) } },
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = showEvLabel && autoExposure,
            enter = fadeIn(),
            exit = fadeOut(),
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

        AnimatedVisibility(
            visible = showZoomLabel,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
        ) {
            Surface(
                color = Color(0xCC000000),
                shape = MaterialTheme.shapes.small
            ) {
                val minZoom = viewModel.cameraStateHolder.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
                val maxZoom = viewModel.cameraStateHolder.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 8f
                val zoomRatio = minZoom + (maxZoom - minZoom) * zoom
                Text(
                    text = "${"%.1f".format(zoomRatio)}x",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        if (!wbLockedUi) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("White balance", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (wbLockedUi) "Locked" else "Unlocked",
                            color = if (wbLockedUi) Color(0xFFFF5A5A) else Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    val currentWhiteBalanceIndex = whiteBalanceModes.indexOfFirst { it.first == whiteBalance }.let { if (it >= 0) it else 0 }
                    OutlinedButton(
                        onClick = {
                            val nextIndex = (currentWhiteBalanceIndex + 1) % whiteBalanceModes.size
                            viewModel.setWhiteBalance(whiteBalanceModes[nextIndex].first)
                        }
                    ) {
                        Text(whiteBalanceModes[currentWhiteBalanceIndex].second, color = Color.White)
                    }
                }
            }
        }
    }

}

@Composable
private fun WheelPickerPanel(
    title: String,
    entries: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge)
        AndroidView(
            factory = { context ->
                NumberPicker(context).apply {
                    descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                    wrapSelectorWheel = true
                    setOnValueChangedListener { _, _, newValue -> onSelected(newValue) }
                }
            },
            update = { picker ->
                val maxIndex = (entries.size - 1).coerceAtLeast(0)
                picker.minValue = 0
                picker.maxValue = maxIndex

                val newValues = entries.toTypedArray()
                val currentValues = picker.displayedValues
                if (currentValues == null || currentValues.size != newValues.size || !currentValues.contentEquals(newValues)) {
                    picker.displayedValues = null
                    picker.displayedValues = newValues
                }

                val safeIndex = selectedIndex.coerceIn(0, maxIndex)
                if (picker.value != safeIndex) {
                    picker.value = safeIndex
                }
            },
            modifier = Modifier.size(width = 160.dp, height = 220.dp)
        )
    }
}


