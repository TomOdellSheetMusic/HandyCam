package com.example.handycam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.net.BindException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaCodec.BufferInfo
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraAccessException
import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
private const val TAG = "StreamService"
private const val CHANNEL_ID = "handycam_stream"
private const val NOTIF_ID = 1001
private const val ACTION_START = "com.example.handycam.ACTION_START"
private const val ACTION_STOP = "com.example.handycam.ACTION_STOP"
private const val ACTION_SET_CAMERA = "com.example.handycam.ACTION_SET_CAMERA"
private const val ACTION_SET_PREVIEW_SURFACE = "com.example.handycam.ACTION_SET_PREVIEW_SURFACE"

@dagger.hilt.android.AndroidEntryPoint
class StreamService : LifecycleService() {
    // keep only the latest frame to minimize latency
    // keep a tiny buffer to reduce tearing when encoder/consumer timing is slightly off
    private val frameQueue = LinkedBlockingQueue<ByteArray>(2)
    private var encoderDroppedFrames = 0
    private var serverThread: Thread? = null
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var channelNeedsUserEnable: Boolean = false

    // runtime options filled from Intent extras
    private var selectedCamera: String = "back"
    private var jpegQuality: Int = 85
    private var targetFps: Int = 25
    private var useAvc: Boolean = false
    private var requestedWidth: Int = 1280
    private var requestedHeight: Int = 720

    private var encoder: MediaCodec? = null
    private var avcConfig: ByteArray? = null
    private var avcBitrateUser: Int? = null
    // Camera2 + encoder surfaces
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraHandlerThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var encoderInputSurface: Surface? = null
    private var encoderOutputThread: Thread? = null
    private val encoderOutputLock = Object()
    @Volatile
    private var encoderOutputRunning = false
    private var imageReader: android.media.ImageReader? = null
    private var encoderWidth: Int = 0
    private var encoderHeight: Int = 0
    // CameraX fields for MJPEG path so we can rebind while running
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var cameraExecutor: java.util.concurrent.ExecutorService? = null
    
    // Screen capture fields
    private var useScreenCapture: Boolean = false
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var screenImageReader: android.media.ImageReader? = null
    private var screenCaptureHandlerThread: HandlerThread? = null
    private var screenCaptureHandler: Handler? = null

    // Audio capture + AAC encode fields
    private val audioFrameQueue = LinkedBlockingQueue<ByteArray>(8)
    private var audioConfig: ByteArray? = null
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioEncoderInputThread: Thread? = null
    private var audioEncoderOutputThread: Thread? = null
    @Volatile private var audioRunning = false

    private companion object {
        const val AUDIO_SAMPLE_RATE = 44100
        const val AUDIO_CHANNELS = 1
        const val AUDIO_BITRATE = 96_000
        // Marks an AAC config packet (all-ones PTS = NO_PTS from DroidCam protocol)
        const val AUDIO_NO_PTS = -1L
    }

    // Preview surface management
    @Volatile
    private var previewSurface: Surface? = null
    private var previewUseCase: androidx.camera.core.Preview? = null

    // mDNS / NSD for DroidCam OBS plugin discovery
    private var mdnsResponder: MdnsResponder? = null

    @javax.inject.Inject lateinit var streamStateHolder: com.example.handycam.service.StreamStateHolder
    @javax.inject.Inject lateinit var cameraStateHolder: com.example.handycam.service.CameraStateHolder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Observe relevant settings to apply runtime changes
        lifecycleScope.launch {
            streamStateHolder.torchEnabled.collect { enabled ->
                try { applyTorch(enabled) } catch (e: Exception) { Log.e(TAG, "applyTorch error", e) }
            }
        }
        lifecycleScope.launch {
            streamStateHolder.exposure.collect { v ->
                try { applyExposure(v) } catch (e: Exception) { Log.e(TAG, "applyExposure error", e) }
            }
        }
        lifecycleScope.launch {
            streamStateHolder.focus.collect { v ->
                try { applyFocus(v) } catch (e: Exception) { Log.e(TAG, "applyFocus error", e) }
            }
        }
        lifecycleScope.launch {
            streamStateHolder.autoFocus.collect { af ->
                try { applyAutoFocus(af) } catch (e: Exception) { Log.e(TAG, "applyAutoFocus error", e) }
            }
        }
        lifecycleScope.launch {
            streamStateHolder.zoom.collect { v ->
                try { applyZoom(v) } catch (e: Exception) { Log.e(TAG, "applyZoom error", e) }
            }
        }
        lifecycleScope.launch {
            streamStateHolder.whiteBalance.collect { v ->
                try { applyWhiteBalance(v) } catch (e: Exception) { Log.e(TAG, "applyWhiteBalance error", e) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra("host") ?: "0.0.0.0"
                val port = intent.getIntExtra("port", 4747)
                val width = intent.getIntExtra("width", 1080)
                val height = intent.getIntExtra("height", 1920)
                selectedCamera = intent.getStringExtra("camera") ?: "back"
                jpegQuality = intent.getIntExtra("jpegQuality", 85)
                targetFps = intent.getIntExtra("targetFps", 60)
                useAvc = intent.getBooleanExtra("useAvc", false)
                val ab = intent.getIntExtra("avcBitrate", -1)
                avcBitrateUser = if (ab > 0) ab else null
                useScreenCapture = intent.getBooleanExtra("useScreenCapture", false)
                val mpResultCode = intent.getIntExtra("mediaProjectionResultCode", 0)
                @Suppress("DEPRECATION")
                val mpData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("mediaProjectionData", Intent::class.java)
                } else {
                    intent.getParcelableExtra("mediaProjectionData")
                }

                // Update state holder
                streamStateHolder.setStreaming(true)
                streamStateHolder.setCamera(selectedCamera)
                streamStateHolder.setPort(port)
                streamStateHolder.setWidth(width)
                streamStateHolder.setHeight(height)
                streamStateHolder.setJpegQuality(jpegQuality)
                streamStateHolder.setFps(targetFps)
                streamStateHolder.setUseAvc(useAvc)
                streamStateHolder.setHost(host)
                streamStateHolder.setUseScreenCapture(useScreenCapture)

                // Save streaming state to preferences
                getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("streamPort", port)
                    .putString("camera", selectedCamera)
                    .apply()
                
                val notifText = if (useScreenCapture)
                    "Streaming screen on $port — fps=$targetFps"
                else
                    "Streaming on $port — $selectedCamera — q=$jpegQuality fps=$targetFps"
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val videoType = if (useScreenCapture)
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        else
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            videoType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        } else {
                            videoType
                        }
                        startForeground(NOTIF_ID, buildNotification(notifText), serviceType)
                    } else {
                        startForeground(NOTIF_ID, buildNotification(notifText))
                    }
                } catch (se: SecurityException) {
                    Log.e(TAG, "Unable to start foreground service: missing permission", se)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startStreaming(host, port, width, height, selectedCamera, jpegQuality, targetFps, useAvc,
                    useScreenCapture, mpResultCode, mpData)
                registerMdns(port)
                // notify UI and persist state that streaming started
                try {
                    notifyStreamingState(true)
                    val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("isStreaming", true).apply()
                } catch (_: Exception) {}
            }
            ACTION_SET_CAMERA -> {
                val newCam = intent.getStringExtra("camera") ?: ""
                if (newCam.isNotBlank()) {
                    Log.i(TAG, "Received camera switch request -> $newCam")
                    streamStateHolder.setCamera(newCam)
                    // perform camera switch while streaming
                    try {
                        handleCameraSwitchRequest(newCam)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error switching camera", e)
                    }
                }
            }
            ACTION_SET_PREVIEW_SURFACE -> {
                val surfaceToken = intent.getStringExtra("surfaceToken")
                Log.i(TAG, "Received preview surface update: $surfaceToken")
                handlePreviewSurfaceUpdate(surfaceToken)
            }
            ACTION_STOP -> {
                streamStateHolder.setStreaming(false)
                unregisterMdns()
                stopStreaming()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // ignore
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        unregisterMdns()
        super.onDestroy()
    }

    private fun buildNotification(text: String) = run {
        // content intent: opens the main activity when the notification is tapped
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // stop action: sends ACTION_STOP to the service
        val stopIntent = Intent(this, StreamService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HandyCam — Camera in use")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)

        if (channelNeedsUserEnable) {
            val settingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            }
            val settingsPending = PendingIntent.getActivity(
                this,
                2,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_manage, "Enable", settingsPending)
        }

        builder.build()
    }
    private fun notifyStreamingState(isStreaming: Boolean) {
        streamStateHolder.setStreaming(isStreaming)
    }

    // Apply torch state to camera (CameraX or Camera2)
    private fun applyTorch(enabled: Boolean) {
        try {
            if (!useAvc) {
                cameraStateHolder.cameraControl?.enableTorch(enabled)
                Log.i(TAG, "Applied torch via CameraControl: $enabled")
            } else {
                // For Camera2 path, update repeating request to set FLASH_MODE
                cameraHandler?.post {
                    try {
                        val cam = cameraDevice ?: return@post
                        val session = captureSession ?: return@post
                        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        encoderInputSurface?.let { builder.addTarget(it) }
                        imageReader?.surface?.let { builder.addTarget(it) }
                        cameraStateHolder.previewSurface?.let { builder.addTarget(it) }
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        if (enabled) {
                            try { builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH) } catch (_: Exception) {}
                        } else {
                            try { builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF) } catch (_: Exception) {}
                        }
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        Log.i(TAG, "Applied torch via Camera2: $enabled")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply torch on Camera2", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyTorch error", e)
        }
    }

    // Apply exposure compensation
    private fun applyExposure(value: Int) {
        try {
            if (!useAvc) {
                val cc = cameraStateHolder.cameraControl
                val info = cameraStateHolder.cameraInfo
                try {
                    // clamp to camera's exposure range if available
                    val range = info?.exposureState?.exposureCompensationRange
                    val v = if (range != null) value.coerceIn(range.lower, range.upper) else value
                    cc?.setExposureCompensationIndex(v)
                    Log.i(TAG, "Applied exposure via CameraControl: $v")
                } catch (e: Exception) {
                    Log.w(TAG, "CameraControl exposure apply failed", e)
                }
            } else {
                cameraHandler?.post {
                    try {
                        val cam = cameraDevice ?: return@post
                        val session = captureSession ?: return@post
                        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        encoderInputSurface?.let { builder.addTarget(it) }
                        imageReader?.surface?.let { builder.addTarget(it) }
                        cameraStateHolder.previewSurface?.let { builder.addTarget(it) }
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        try { builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value) } catch (_: Exception) {}
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        Log.i(TAG, "Applied exposure via Camera2: $value")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply exposure on Camera2", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyExposure error", e)
        }
    }

    // Apply manual focus (best-effort: Camera2 LENS_FOCUS_DISTANCE)
    private fun applyFocus(value: Int) {
        try {
            if (!useAvc) {
                // Manual focus not directly supported in CameraX generic API; no-op
                Log.i(TAG, "Manual focus requested ($value) but CameraX manual focus not supported; ignoring")
            } else {
                cameraHandler?.post {
                    try {
                        val cam = cameraDevice ?: return@post
                        val session = captureSession ?: return@post
                        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        encoderInputSurface?.let { builder.addTarget(it) }
                        imageReader?.surface?.let { builder.addTarget(it) }
                        cameraStateHolder.previewSurface?.let { builder.addTarget(it) }
                        // Map 0..100 slider to a focus distance value (inverse meters). 0 -> infinity (0.0f), 100 -> 1.0f
                        val fd = if (value <= 0) 0.0f else (1.0f.coerceAtMost(value / 100.0f))
                        try { builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF) } catch (_: Exception) {}
                        try { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd) } catch (_: Exception) {}
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        Log.i(TAG, "Applied focus via Camera2: slider=$value -> distance=$fd")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply focus on Camera2", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyFocus error", e)
        }
    }

    private fun applyAutoFocus(enabled: Boolean) {
        try {
            if (!useAvc) {
                // CameraX: toggling AF mode programmatically requires a PreviewView to build a MeteringPoint.
                // We'll no-op here and rely on Camera2 path for manual AF control.
                Log.i(TAG, "AutoFocus change requested for CameraX: $enabled (no-op)")
            } else {
                cameraHandler?.post {
                    try {
                        val cam = cameraDevice ?: return@post
                        val session = captureSession ?: return@post
                        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        encoderInputSurface?.let { builder.addTarget(it) }
                        imageReader?.surface?.let { builder.addTarget(it) }
                        cameraStateHolder.previewSurface?.let { builder.addTarget(it) }
                        if (enabled) builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) else builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply AF mode on Camera2", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyAutoFocus error", e)
        }
    }

    private fun applyZoom(linearZoom: Float) {
        try {
            if (!useAvc) {
                cameraStateHolder.cameraControl?.setLinearZoom(linearZoom)
                Log.i(TAG, "Applied zoom via CameraControl: $linearZoom")
            } else {
                cameraHandler?.post {
                    try {
                        val cam = cameraDevice ?: return@post
                        val session = captureSession ?: return@post
                        val camId = findCameraId(selectedCamera) ?: return@post
                        val chars = cameraManager?.getCameraCharacteristics(camId) ?: return@post
                        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
                        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                        val zoomRatio = 1f + (maxZoom - 1f) * linearZoom
                        val cropW = (sensorSize.width() / zoomRatio).toInt()
                        val cropH = (sensorSize.height() / zoomRatio).toInt()
                        val cropX = (sensorSize.width() - cropW) / 2
                        val cropY = (sensorSize.height() - cropH) / 2
                        val cropRegion = android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        encoderInputSurface?.let { builder.addTarget(it) }
                        imageReader?.surface?.let { builder.addTarget(it) }
                        cameraStateHolder.previewSurface?.let { builder.addTarget(it) }
                        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        Log.i(TAG, "Applied zoom via Camera2: ratio=$zoomRatio")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply zoom on Camera2", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyZoom error", e)
        }
    }

    private fun applyWhiteBalance(mode: Int) {
        try {
            if (!useAvc) {
                Log.i(TAG, "WB change requested for CameraX path (no-op without Camera2Interop)")
            } else {
                cameraHandler?.post {
                    try {
                        val cam = cameraDevice ?: return@post
                        val session = captureSession ?: return@post
                        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        encoderInputSurface?.let { builder.addTarget(it) }
                        imageReader?.surface?.let { builder.addTarget(it) }
                        cameraStateHolder.previewSurface?.let { builder.addTarget(it) }
                        builder.set(CaptureRequest.CONTROL_AWB_MODE, mode)
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        Log.i(TAG, "Applied WB via Camera2: mode=$mode")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply WB on Camera2", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyWhiteBalance error", e)
        }
    }

    private fun registerMdns(port: Int) {
        try {
            mdnsResponder = MdnsResponder(this, port).also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "mDNS start error", e)
        }
    }

    private fun unregisterMdns() {
        mdnsResponder?.stop()
        mdnsResponder = null
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is not in the Support Library.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = CHANNEL_ID
            val descriptionText = "HandyCam is active"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system.
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startStreaming(bindHost: String, port: Int, width: Int, height: Int, camera: String, jpegQ: Int, fps: Int, useAvcFlag: Boolean, screenCapture: Boolean = false, mpResultCode: Int = 0, mpData: Intent? = null) {
        if (running) return
        running = true
        // Always stream in landscape — swap if portrait dimensions were passed
        val w = maxOf(width, height)
        val h = minOf(width, height)
        // store runtime options
        selectedCamera = camera
        jpegQuality = jpegQ
        targetFps = fps
        useAvc = useAvcFlag
        useScreenCapture = screenCapture
        requestedWidth = w
        requestedHeight = h

        if (useScreenCapture) {
            if (useAvc) {
                try {
                    setupEncoder(w, h, targetFps)
                    startEncoderOutputReader()
                    startScreenCaptureAvc(mpResultCode, mpData!!)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start screen capture AVC pipeline", e)
                }
            } else {
                try {
                    startScreenCaptureMjpeg(mpResultCode, mpData!!, w, h)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start screen capture MJPEG pipeline", e)
                }
            }
        } else if (useAvc) {
            // Start Camera2 -> encoder pipeline. The helper will pick a supported
            // camera output size and call setupEncoder(...) with a compatible size.
            try {
                startCamera2ToEncoder(w, h, targetFps)
                startEncoderOutputReader()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize encoder or camera2 pipeline", e)
                // fallback to MJPEG if AVC init fails
                useAvc = false
                stopCamera2()
            }
        } else {
            // Start CameraX analysis to capture frames (MJPEG path)
            // Initialize camera manager early so findCameraId and buildCameraSelectorForId can work
            try {
                if (cameraManager == null) {
                    cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get CameraManager for MJPEG path", e)
            }
            
            lifecycleScope.launch(Dispatchers.Main) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this@StreamService)
                val cameraProvider = cameraProviderFuture.get()
                // keep reference so we can rebind while streaming
                this@StreamService.cameraProvider = cameraProvider

                val analysisUseCase = ImageAnalysis.Builder()
                    // Lock to ROTATION_90 so CameraX interprets dimensions in sensor/landscape
                    // space regardless of phone orientation — ensures consistent landscape output
                    .setTargetRotation(android.view.Surface.ROTATION_90)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(w, h),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                cameraExecutor = Executors.newSingleThreadExecutor()
                analysisUseCase.setAnalyzer(cameraExecutor!!) { image ->
                    // compress on the camera executor to avoid blocking UI
                    handleImageProxy(image)
                }
                // keep reference so switch can rebind
                this@StreamService.analysisUseCase = analysisUseCase
                
                // Create preview use case if surface provider is available
                if (cameraStateHolder.previewSurfaceProvider != null) {
                    previewUseCase = androidx.camera.core.Preview.Builder().build().apply {
                        setSurfaceProvider(cameraStateHolder.previewSurfaceProvider)
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    
                    // Try to use the physical camera ID if available (for focal lengths)
                    val physicalCameraId = try { findCameraId(selectedCamera) } catch (e: Exception) { null }
                    
                    val selector = if (physicalCameraId != null) {
                        // Use custom selector for specific physical camera (includes focal length variants)
                        buildCameraSelectorForId(physicalCameraId) ?: 
                        (if (selectedCamera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA)
                    } else {
                        // Fallback to front/back if no physical ID found
                        if (selectedCamera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    
                    val useCases = mutableListOf<androidx.camera.core.UseCase>(analysisUseCase)
                    previewUseCase?.let { useCases.add(it) }
                    
                    val camera = cameraProvider.bindToLifecycle(this@StreamService, selector, *useCases.toTypedArray())
                    
                    // Store camera control for preview activity
                    cameraStateHolder.cameraControl = camera.cameraControl
                    cameraStateHolder.cameraInfo = camera.cameraInfo
                    
                    Log.i(TAG, "Camera bound in service (MJPEG): $selectedCamera (physical: ${physicalCameraId ?: "auto"}) with ${useCases.size} use cases")
                    
                    // Notify that camera is ready
                        sendBroadcast(Intent("com.example.handycam.CAMERA_READY").apply { setPackage(packageName) })
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind camera use cases in service", e)
                }
            }
        }

        startAudio()

        // Start server thread
        serverThread = Thread {
            try {
                // create socket unbound so we can set reuseAddress before bind
                val server = ServerSocket()
                server.reuseAddress = true
                try {
                    server.bind(InetSocketAddress(bindHost, port))
                } catch (be: BindException) {
                    Log.e(TAG, "Bind failed on $bindHost:$port - address already in use", be)
                    server.close()
                    serverSocket = null
                    running = false
                    return@Thread
                }

                serverSocket = server
                Log.i(TAG, "Server listening on $bindHost:$port")

                while (running) {
                    val client = try { server.accept() } catch (ie: Exception) {
                        Log.i(TAG, "Server accept interrupted or socket closed: ${ie.message}")
                        break
                    }
                    Log.i(TAG, "Client connected: ${client.inetAddress}")
                    Thread { handleClient(client) }.start()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
                Log.i(TAG, "Server thread exiting")
            }
        }
        serverThread?.start()
    }

    private fun stopStreaming() {
        running = false
        // stop screen capture if active
        stopScreenCapture()
        stopAudio()
        // stop camera/capture session first to ensure producers stop feeding surfaces
        stopEncoderOutputReader()
        stopCamera2()
        // If CameraX was active, unbind and shutdown its executor
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        try {
            cameraExecutor?.shutdownNow()
        } catch (_: Exception) {}
        cameraProvider = null
        analysisUseCase = null
        previewUseCase = null
        cameraExecutor = null
        
        // Clear shared surface references
        cameraStateHolder.previewSurface = null
        cameraStateHolder.previewSurfaceProvider = null
        cameraStateHolder.cameraControl = null
        cameraStateHolder.cameraInfo = null

        try {
            // stop and release encoder after camera surfaces are torn down
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping/releasing encoder", e)
        }
        encoder = null
        avcConfig = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverThread?.interrupt()
        serverThread = null
        frameQueue.clear()
        try {
            notifyStreamingState(false)
            val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("isStreaming", false).apply()
        } catch (_: Exception) {}
    }

    // ── Screen capture helpers ──────────────────────────────────────────────

    private fun startScreenCaptureMjpeg(resultCode: Int, data: Intent, w: Int, h: Int) {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        val density = resources.displayMetrics.densityDpi

        screenCaptureHandlerThread = HandlerThread("ScreenCaptureThread").also { it.start() }
        screenCaptureHandler = Handler(screenCaptureHandlerThread!!.looper)

        // Android 14+ requires a callback to be registered before createVirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    if (running) stopStreaming()
                }
            }, screenCaptureHandler)
        }

        screenImageReader = android.media.ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        screenImageReader!!.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { return@setOnImageAvailableListener }
            if (image == null) return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w

                val bitmapWidth = w + rowPadding / pixelStride
                val bitmap = Bitmap.createBitmap(bitmapWidth, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                val finalBitmap = if (bitmapWidth > w) {
                    Bitmap.createBitmap(bitmap, 0, 0, w, h).also { bitmap.recycle() }
                } else bitmap

                val baos = java.io.ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(10, 100), baos)
                finalBitmap.recycle()

                val jpeg = baos.toByteArray()
                if (!frameQueue.offer(jpeg)) {
                    frameQueue.poll()
                    frameQueue.offer(jpeg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screen capture frame error", e)
            } finally {
                image.close()
            }
        }, screenCaptureHandler)

        val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HandyCamScreen", w, h, density,
            displayFlags,
            screenImageReader!!.surface, null, screenCaptureHandler
        )
        Log.i(TAG, "Screen capture MJPEG started: ${w}x${h} density=$density")
    }

    private fun startScreenCaptureAvc(resultCode: Int, data: Intent) {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        val density = resources.displayMetrics.densityDpi
        val surface = encoderInputSurface ?: run {
            Log.e(TAG, "Encoder input surface is null for screen capture AVC")
            return
        }

        // Android 14+ requires a callback to be registered before createVirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (screenCaptureHandlerThread == null) {
                screenCaptureHandlerThread = HandlerThread("ScreenCaptureThread").also { it.start() }
                screenCaptureHandler = Handler(screenCaptureHandlerThread!!.looper)
            }
            mediaProjection!!.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    if (running) stopStreaming()
                }
            }, screenCaptureHandler!!)
        }

        val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HandyCamScreen", encoderWidth, encoderHeight, density,
            displayFlags,
            surface, null, null
        )
        Log.i(TAG, "Screen capture AVC started: ${encoderWidth}x${encoderHeight} density=$density")
    }

    private fun stopScreenCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { screenImageReader?.close() } catch (_: Exception) {}
        screenImageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        try { screenCaptureHandlerThread?.quitSafely() } catch (_: Exception) {}
        screenCaptureHandlerThread = null
        screenCaptureHandler = null
        useScreenCapture = false
        streamStateHolder.setUseScreenCapture(false)
    }

      private fun setupEncoder(width: Int, height: Int, fps: Int) {
        val mime = "video/avc"
        // choose a higher-quality bitrate: allow a user-specified bitrate override,
        // otherwise derive a reasonable default (pixels * fps / 10)
        // use a more generous default bitrate (pixels * fps / 6) to reduce blocking artifacts
        val defaultBitrate = ((width.toLong() * height.toLong() * fps) / 6).toInt().coerceAtLeast(800_000)
        val bitrate = avcBitrateUser ?: defaultBitrate
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            // use input surface path
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            try {
                // prefer VBR for better visual quality; if not supported fall back gracefully
                setInteger(MediaFormat.KEY_BITRATE_MODE, android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            } catch (_: Exception) {
                // ignore if not supported
            }
        }

        encoder = MediaCodec.createEncoderByType(mime)
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // create an input surface for Camera2 to write into
        encoderInputSurface = encoder?.createInputSurface()
        encoder?.start()
        avcConfig = null
        encoderWidth = width
        encoderHeight = height
        Log.i(TAG, "Encoder initialized (surface): ${width}x${height} @ ${fps}fps bitrate=$bitrate")
    }

    private fun startEncoderOutputReader() {
        synchronized(encoderOutputLock) {
            if (encoder == null) return
            // avoid starting multiple concurrent reader threads
            val existing = encoderOutputThread
            if (existing != null && existing.isAlive) return

            encoderOutputThread = Thread {
                encoderOutputRunning = true
                try {
                    val codec = encoder ?: return@Thread
                    val info = BufferInfo()
                    while (running && encoderOutputRunning) {
                        // short timeout so we drain quickly and avoid encoder internal backlog
                        val outIndex = try { codec.dequeueOutputBuffer(info, 200) } catch (e: Exception) { -1 }
                        if (outIndex >= 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            val outBytes = ByteArray(info.size)
                            outBuf?.get(outBytes)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                avcConfig = outBytes
                                Log.i(TAG, "Saved AVC config size=${outBytes.size}")
                            } else {
                                if (!frameQueue.offer(outBytes)) {
                                    // queue is full: drop the oldest frame to keep latest
                                    frameQueue.poll()
                                    if (!frameQueue.offer(outBytes)) {
                                        // if still failing, count drops for diagnostics
                                        encoderDroppedFrames++
                                        if (encoderDroppedFrames % 50 == 0) {
                                            Log.w(TAG, "Encoder dropping frames; dropped total=$encoderDroppedFrames")
                                        }
                                    }
                                }
                            }
                            try { codec.releaseOutputBuffer(outIndex, false) } catch (e: Exception) { Log.w(TAG, "releaseOutputBuffer failed", e) }
                        } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // no output currently
                            continue
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            try {
                                val outFmt = codec.outputFormat
                                Log.i(TAG, "Encoder output format changed: $outFmt")
                            } catch (e: Exception) {
                                Log.i(TAG, "Encoder output format changed")
                            }
                        } else {
                            // unexpected
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Encoder output reader error", t)
                } finally {
                    encoderOutputRunning = false
                }
                Log.i(TAG, "Encoder output reader exiting")
            }
            encoderOutputThread?.start()
        }
    }

    private fun stopEncoderOutputReader() {
        synchronized(encoderOutputLock) {
            try {
                encoderOutputRunning = false
                encoderOutputThread?.interrupt()
                try {
                    encoderOutputThread?.join(500)
                } catch (_: Exception) {}
            } catch (_: Exception) {}
            encoderOutputThread = null
        }
    }

    private fun handleImageProxy(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) {
                image.close()
                return
            }

            val width = image.width
            val height = image.height

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = ByteArray(yPlane.buffer.remaining())
            yPlane.buffer.get(yBuffer)
            val uBuffer = ByteArray(uPlane.buffer.remaining())
            uPlane.buffer.get(uBuffer)
            val vBuffer = ByteArray(vPlane.buffer.remaining())
            vPlane.buffer.get(vBuffer)

            val nv21 = ByteArray(width * height * 3 / 2)
            var pos = 0
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            if (yPixelStride == 1 && yRowStride == width) {
                System.arraycopy(yBuffer, 0, nv21, 0, width * height)
                pos = width * height
            } else {
                for (row in 0 until height) {
                    val yRowStart = row * yRowStride
                    for (col in 0 until width) {
                        nv21[pos++] = yBuffer[yRowStart + col * yPixelStride]
                    }
                }
            }

            val chromaHeight = height / 2
            val chromaWidth = width / 2
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride

            for (row in 0 until chromaHeight) {
                val uRowStart = row * uRowStride
                val vRowStart = row * vRowStride
                for (col in 0 until chromaWidth) {
                    val vIndex = vRowStart + col * vPixelStride
                    val uIndex = uRowStart + col * uPixelStride
                    nv21[pos++] = vBuffer[vIndex]
                    nv21[pos++] = uBuffer[uIndex]
                }
            }

            if (encoder != null) {
                // convert NV21 -> NV12 (swap U/V pairs)
                val nv12 = ByteArray(nv21.size)
                // copy Y
                System.arraycopy(nv21, 0, nv12, 0, width * height)
                var p = width * height
                while (p < nv21.size) {
                    // NV21 is VU VU..., NV12 is UV UV...
                    val v = nv21[p]
                    val u = nv21[p + 1]
                    nv12[p] = u
                    nv12[p + 1] = v
                    p += 2
                }
                // When using Surface input, we don't queue input buffers here. The encoder
                // will be fed by Camera2. For devices that still use buffer input, the
                // code above handled it; but with the new Camera2 surface approach we
                // keep this branch as no-op because CameraX path won't be active when
                // using AVC with surface input.
            } else {
                val baos = ByteArrayOutputStream()
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                // use configured jpegQuality, clamp to reasonable range
                yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality.coerceIn(10, 100), baos)
                val jpeg = baos.toByteArray()

                if (!frameQueue.offer(jpeg)) {
                    frameQueue.poll()
                    frameQueue.offer(jpeg)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Image processing failed in service", e)
        } finally {
            image.close()
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 5000
        try {
            val input = client.getInputStream()
            val buf = ByteArray(1024)
            val read = try { input.read(buf) } catch (e: Exception) { -1 }
            val req = if (read > 0) String(buf, 0, read) else ""
            Log.i(TAG, "Request (service): ${req.replace("\r","\\r").replace("\n","\\n").trim()}")

            // parse requested format from the path
            var requestedFormat = "jpg"
            try {
                val firstLine = req.replace("\r", "").split("\n").firstOrNull() ?: ""
                val parts = firstLine.split(" ")
                if (parts.size >= 2) {
                    val path = parts[1]
                    val segs = path.split('/').filter { it.isNotEmpty() }
                    if (segs.size >= 3 && segs[0].startsWith("v")) {
                        requestedFormat = segs[2]
                    }
                    Log.i(TAG, "Parsed request path=$path format=$requestedFormat")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse request line", e)
            }

            if (req.contains("/v2/audio")) {
                handleAudioClient(client)
                return
            } else if (req.contains("/v5/video/") || req.contains("/v2/video/") || req.contains("/v1/video/")) {
                val out = client.getOutputStream()
                val intervalMs = if (targetFps > 0) (1000L / targetFps) else 40L
                val pollTimeoutMs = intervalMs
                try {
                    // if client requested AVC and we have codec config, send config packet first
                    if (requestedFormat.startsWith("avc") && avcConfig != null) {
                        val cfgHeader = ByteBuffer.allocate(12)
                        cfgHeader.putLong(-1L)
                        cfgHeader.putInt(avcConfig!!.size)
                        out.write(cfgHeader.array())
                        out.write(avcConfig)
                        out.flush()
                        Log.i(TAG, "Sent AVC config to client ${client.inetAddress}")
                    }

                    var lastSent = 0L
                    while (running && !client.isClosed && client.isConnected) {
                        val frame = try {
                            frameQueue.poll(pollTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } catch (ie: InterruptedException) {
                            null
                        }

                        if (frame == null) {
                            continue
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastSent < intervalMs) {
                            continue
                        }

                        val pts = now
                        val header = ByteBuffer.allocate(12)
                        header.putLong(pts)
                        header.putInt(frame.size)

                        try {
                            out.write(header.array())
                            out.write(frame)
                            out.flush()
                            lastSent = now
                        } catch (se: java.net.SocketException) {
                            Log.i(TAG, "Socket closed by peer while writing: ${se.message}")
                            break
                        } catch (ioe: java.io.IOException) {
                            Log.i(TAG, "IO error while writing to client: ${ioe.message}")
                            break
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Stream loop error in service", t)
                }
            } else if (req.startsWith("GET /ping")) {
                client.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
            } else if (req.contains("PUT /v1/tally/")) {
                val resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                client.getOutputStream().write(resp.toByteArray())
                client.getOutputStream().flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error in service", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
            Log.i(TAG, "Client disconnected (service)")
        }
    }

    // ----- Audio capture + AAC encode helpers -----

    @android.annotation.SuppressLint("MissingPermission")
    private fun startAudio() {
        if (audioRunning) return
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 4096)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize * 2)
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder!!.start()

            audioRunning = true
            audioRecord!!.startRecording()
            Log.i(TAG, "Audio capture started: ${AUDIO_SAMPLE_RATE}Hz mono ${AUDIO_BITRATE}bps AAC-LC")

            val encoder = audioEncoder!!
            val record = audioRecord!!

            // Input thread: feed PCM from AudioRecord into the encoder
            audioEncoderInputThread = Thread {
                val pcm = ByteArray(bufSize)
                while (audioRunning) {
                    val read = record.read(pcm, 0, pcm.size)
                    if (read <= 0) continue
                    val idx = encoder.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val inBuf = encoder.getInputBuffer(idx) ?: continue
                        inBuf.clear()
                        inBuf.put(pcm, 0, read)
                        encoder.queueInputBuffer(idx, 0, read, System.nanoTime() / 1000, 0)
                    }
                }
            }.also { it.start() }

            // Output thread: drain encoder → audioFrameQueue
            audioEncoderOutputThread = Thread {
                val info = BufferInfo()
                while (audioRunning) {
                    val outIdx = try {
                        encoder.dequeueOutputBuffer(info, 10_000)
                    } catch (e: Exception) { break }
                    if (outIdx >= 0) {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        val bytes = ByteArray(info.size)
                        outBuf?.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            audioConfig = bytes
                            Log.i(TAG, "Got AAC config: ${bytes.size} bytes")
                        } else if (bytes.isNotEmpty()) {
                            if (!audioFrameQueue.offer(bytes)) {
                                audioFrameQueue.poll()
                                audioFrameQueue.offer(bytes)
                            }
                        }
                        try { encoder.releaseOutputBuffer(outIdx, false) } catch (_: Exception) {}
                    }
                }
            }.also { it.start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stopAudio()
        }
    }

    private fun stopAudio() {
        audioRunning = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioEncoderInputThread?.interrupt() } catch (_: Exception) {}
        try { audioEncoderInputThread?.join(300) } catch (_: Exception) {}
        audioEncoderInputThread = null
        try { audioEncoderOutputThread?.interrupt() } catch (_: Exception) {}
        try { audioEncoderOutputThread?.join(300) } catch (_: Exception) {}
        audioEncoderOutputThread = null
        try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
        audioConfig = null
        audioFrameQueue.clear()
    }

    private fun handleAudioClient(client: Socket) {
        // No soTimeout on audio: we want to block in the frame loop
        client.soTimeout = 0
        val out = client.getOutputStream()
        try {
            // Wait up to 3s for the AAC config to arrive (encoder starts asynchronously)
            val deadline = System.currentTimeMillis() + 3000
            while (audioConfig == null && System.currentTimeMillis() < deadline && running) {
                Thread.sleep(20)
            }
            val config = audioConfig
            if (config == null) {
                Log.w(TAG, "Audio: no AAC config available, dropping client")
                return
            }

            // Send config packet: PTS=NO_PTS (-1L) + config bytes
            val cfgHeader = ByteBuffer.allocate(12)
            cfgHeader.putLong(AUDIO_NO_PTS)
            cfgHeader.putInt(config.size)
            out.write(cfgHeader.array())
            out.write(config)
            out.flush()
            Log.i(TAG, "Sent AAC config (${config.size}B) to audio client ${client.inetAddress}")

            // Stream audio frames
            while (running && !client.isClosed && client.isConnected) {
                val frame = try {
                    audioFrameQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { null }
                if (frame == null) continue

                val pts = System.currentTimeMillis()
                val header = ByteBuffer.allocate(12)
                header.putLong(pts)
                header.putInt(frame.size)
                try {
                    out.write(header.array())
                    out.write(frame)
                    out.flush()
                } catch (_: java.io.IOException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio client handler error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
            Log.i(TAG, "Audio client disconnected")
        }
    }

    // ----- Camera2 helper methods for encoder surface path -----
    private fun startCamera2ToEncoder(reqWidth: Int, reqHeight: Int, fps: Int) {
        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        } catch (e: Exception) {
            Log.e(TAG, "CameraManager not available", e)
            return
        }

        cameraHandlerThread = HandlerThread("Camera2Thread").also { it.start() }
        cameraHandler = Handler(cameraHandlerThread!!.looper)

        // pick camera id
        val camId = try { findCameraId(selectedCamera) } catch (e: Exception) { null }
        if (camId == null) {
            Log.e(TAG, "No camera id found for selector $selectedCamera")
            return
        }

        // determine a supported output size for the camera for encoder surface
        var chosenWidth = reqWidth
        var chosenHeight = reqHeight
        try {
            val chars = cameraManager?.getCameraCharacteristics(camId)
            // Camera2 output sizes are always in sensor (landscape) orientation.
            // Swap the requested dimensions so we find the right match.
            val sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val swapDimensions = sensorOrientation == 90 || sensorOrientation == 270
            val nativeReqWidth  = if (swapDimensions && reqWidth < reqHeight) reqHeight else reqWidth
            val nativeReqHeight = if (swapDimensions && reqWidth < reqHeight) reqWidth  else reqHeight
            val map = chars?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val sizes = map.getOutputSizes(SurfaceTexture::class.java)
                if (sizes != null && sizes.isNotEmpty()) {
                    // find exact match or choose closest by area preserving aspect ratio
                    var chosen = sizes[0]
                    var bestDiff = Math.abs(chosen.width * chosen.height - nativeReqWidth * nativeReqHeight)
                    for (s in sizes) {
                        if (s.width == nativeReqWidth && s.height == nativeReqHeight) {
                            chosen = s
                            bestDiff = 0
                            break
                        }
                        val diff = Math.abs(s.width * s.height - nativeReqWidth * nativeReqHeight)
                        if (diff < bestDiff) {
                            bestDiff = diff
                            chosen = s
                        }
                    }
                    chosenWidth = chosen.width
                    chosenHeight = chosen.height
                    Log.i(TAG, "Selected camera-compatible size for encoder: ${chosenWidth}x${chosenHeight}")
                    // create encoder with chosen size (reuse if possible)
                    if (encoder != null && encoderWidth == chosenWidth && encoderHeight == chosenHeight) {
                        Log.i(TAG, "Reusing existing encoder for ${chosenWidth}x${chosenHeight}")
                    } else {
                        // if there's an existing encoder with different size, tear it down first
                        if (encoder != null) {
                            stopEncoderOutputReader()
                            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
                            encoder = null
                        }
                        setupEncoder(chosenWidth, chosenHeight, fps)
                        if (encoderOutputThread == null) startEncoderOutputReader()
                    }
                } else {
                    Log.w(TAG, "No supported sizes returned, proceeding with requested size")
                    if (encoder != null && encoderWidth == reqWidth && encoderHeight == reqHeight) {
                        Log.i(TAG, "Reusing existing encoder for ${reqWidth}x${reqHeight}")
                    } else {
                        if (encoder != null) {
                            stopEncoderOutputReader()
                            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
                            encoder = null
                        }
                        setupEncoder(reqWidth, reqHeight, fps)
                        if (encoderOutputThread == null) startEncoderOutputReader()
                    }
                }
            } else {
                Log.w(TAG, "No StreamConfigurationMap available, using requested size")
                setupEncoder(reqWidth, reqHeight, fps)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query supported sizes, using requested size", e)
            setupEncoder(reqWidth, reqHeight, fps)
        }

        try {
            cameraManager?.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            val surface = encoderInputSurface
                        if (surface == null) {
                            Log.e(TAG, "Encoder input surface is null")
                            return
                        }
                        // create an ImageReader as an additional consumer so some devices
                        // will happily start streaming into the encoder surface
                        val ir = imageReader ?: android.media.ImageReader.newInstance(
                            chosenWidth.takeIf { it>0 } ?: 1280,
                            chosenHeight.takeIf { it>0 } ?: 720,
                            ImageFormat.YUV_420_888,
                            2
                        ).also { imageReader = it }

                        ir.setOnImageAvailableListener({ reader ->
                            // immediately close images; we only need this to be a valid consumer
                            val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                            try { img?.close() } catch (_: Exception) {}
                        }, cameraHandler)

                        val targets = mutableListOf(surface, ir.surface)
                        cameraStateHolder.previewSurface?.let { targets.add(it) }

                        val sessionCb = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    // Add all surfaces to the capture request
                                    val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    targets.forEach { reqBuilder.addTarget(it) }
                                    reqBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    reqBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    
                                    session.setRepeatingRequest(reqBuilder.build(), null, cameraHandler)
                                    Log.i(TAG, "Camera2 session configured with ${targets.size} targets")
                                    
                                    // Notify that camera is ready
                                        sendBroadcast(Intent("com.example.handycam.CAMERA_READY").apply { setPackage(packageName) })
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start repeating request", e)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Camera2 session configuration failed")
                            }
                        }
                        val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                            android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                            targets.map { android.hardware.camera2.params.OutputConfiguration(it) },
                            java.util.concurrent.Executor { cmd -> cameraHandler?.post(cmd) },
                            sessionCb
                        )
                        camera.createCaptureSession(sessionConfig)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create capture request/session", e)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.i(TAG, "Camera disconnected")
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission missing", e)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error", e)
        }
    }

    private fun stopCamera2(releaseEncoderSurface: Boolean = true) {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        if (releaseEncoderSurface) {
            try { encoderInputSurface?.release() } catch (_: Exception) {}
            encoderInputSurface = null
        }
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { cameraHandlerThread?.quitSafely() } catch (_: Exception) {}
        cameraHandlerThread = null
        cameraHandler = null
    }

    private fun findCameraId(sel: String): String? {
        val mgr = cameraManager ?: return null
        try {
            for (id in mgr.cameraIdList) {
                try {
                    val chars = mgr.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if ((sel == "back" && facing == CameraCharacteristics.LENS_FACING_BACK) ||
                        (sel == "front" && facing == CameraCharacteristics.LENS_FACING_FRONT) ||
                        id == sel) {
                        return id
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    private fun buildCameraSelectorForId(cameraId: String): CameraSelector? {
        // For CameraX 1.1+, we can use addCameraFilter to select specific physical cameras
        // by checking against Camera2 characteristics
        return try {
            val mgr = cameraManager ?: return null
            val targetChars = mgr.getCameraCharacteristics(cameraId)
            val targetFacing = targetChars.get(CameraCharacteristics.LENS_FACING)
            val targetFocalLengths = targetChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.take(1)  // Just check the first focal length
                ?.toList() ?: emptyList()
            
            Log.i(TAG, "Building selector for camera $cameraId with facing=$targetFacing, focal lengths=$targetFocalLengths")
            
            val selectorBuilder = CameraSelector.Builder()
            
            // First, require the correct facing direction
            when (targetFacing) {
                CameraCharacteristics.LENS_FACING_FRONT -> selectorBuilder.requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                CameraCharacteristics.LENS_FACING_BACK -> selectorBuilder.requireLensFacing(CameraSelector.LENS_FACING_BACK)
                else -> {} // Unknown facing
            }
            
            // If we have focal length info, try to filter for matching focal length
            if (targetFocalLengths.isNotEmpty()) {
                selectorBuilder.addCameraFilter { availableCameras ->
                    availableCameras.filter { cameraInfo ->
                        try {
                            // Try to match by inspecting if this camera has matching focal lengths
                            // CameraX provides CameraInfo; we need to check its characteristics
                            // In CameraX 1.5.1, we can use introspection to get Camera2 info
                            
                            // Get the Camera2 CameraCharacteristics through reflection if possible
                            val cameraId2 = try {
                                // Try to get camera ID from CameraInfo using reflection
                                val getCameraIdMethod = cameraInfo.javaClass.getMethod("getCameraId")
                                getCameraIdMethod.invoke(cameraInfo) as? String
                            } catch (e: Exception) {
                                null
                            }
                            
                            if (cameraId2 != null) {
                                // We got the camera ID - check if focal lengths match
                                val chars2 = mgr.getCameraCharacteristics(cameraId2)
                                val focal2 = chars2.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                    ?.take(1)
                                    ?.toList() ?: emptyList()
                                
                                // Match if we have similar focal lengths (with small tolerance for floating point)
                                focal2.isNotEmpty() && targetFocalLengths.any { targetFocal ->
                                    focal2.any { f2 ->
                                        kotlin.math.abs(f2 - targetFocal) < 0.1f
                                    }
                                }
                            } else {
                                // Can't get camera ID, include this camera (will match first)
                                true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error matching focal length in camera filter", e)
                            true  // Include if we can't determine
                        }
                    }
                }
            }
            
            selectorBuilder.build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build custom CameraSelector for camera $cameraId, will use facing direction", e)
            // Fallback to just using facing direction
            try {
                val mgr = cameraManager ?: return null
                val chars = mgr.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                
                val selectorBuilder = CameraSelector.Builder()
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> selectorBuilder.requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    CameraCharacteristics.LENS_FACING_BACK -> selectorBuilder.requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    else -> {} // Unknown
                }
                selectorBuilder.build()
            } catch (e2: Exception) {
                Log.w(TAG, "Failed all attempts to build CameraSelector for $cameraId", e2)
                null
            }
        }
    }

    private fun handleCameraSwitchRequest(newCam: String) {
        // update stored selection
        if (newCam == selectedCamera) {
            Log.i(TAG, "Camera already set to $newCam")
            return
        }
        selectedCamera = newCam

        if (useAvc) {
            // For AVC path, stop camera session but keep encoder surface, then reopen camera
            try {
                stopCamera2(releaseEncoderSurface = false)
                // restart Camera2 session using existing encoder surface
                startCamera2ToEncoder(requestedWidth, requestedHeight, targetFps)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch Camera2 camera", e)
            }
        } else {
            // For CameraX MJPEG path, rebind the analysis use case to the new selector
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    val provider = cameraProvider ?: run {
                        Log.w(TAG, "CameraProvider not available when switching camera")
                        return@launch
                    }
                    provider.unbindAll()
                    
                    // Try to use the physical camera ID if available (for focal lengths)
                    val physicalCameraId = try { findCameraId(selectedCamera) } catch (e: Exception) { null }
                    
                    val selector = if (physicalCameraId != null) {
                        // Use custom selector for specific physical camera (includes focal length variants)
                        buildCameraSelectorForId(physicalCameraId) ?: 
                        (if (selectedCamera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA)
                    } else {
                        // Fallback to front/back if no physical ID found
                        if (selectedCamera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    
                    val useCases = mutableListOf<androidx.camera.core.UseCase>()
                    analysisUseCase?.let { useCases.add(it) }
                    previewUseCase?.let { useCases.add(it) }
                    
                    if (useCases.isNotEmpty()) {
                        provider.bindToLifecycle(this@StreamService, selector, *useCases.toTypedArray())
                        Log.i(TAG, "Switched CameraX binding to $selectedCamera (physical: ${physicalCameraId ?: "auto"}) with ${useCases.size} use cases")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebind CameraX for new camera", e)
                }
            }
        }
    }
    
    private fun handlePreviewSurfaceUpdate(surfaceToken: String?) {
        if (surfaceToken == null) {
            // Remove preview
            previewUseCase = null
            cameraStateHolder.previewSurfaceProvider = null
            Log.i(TAG, "Preview surface removed")
            
            // For both modes, stop the preview use case
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    cameraProvider?.unbindAll()
                    // Rebind without preview for MJPEG mode
                    if (!useAvc) {
                        rebindCameraXUseCases()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing preview", e)
                }
            }
        } else {
            // Surface is being set by the preview activity via SharedSurfaceProvider
            Log.i(TAG, "Preview surface registered: $surfaceToken")
            
            // For both modes, use CameraX Preview for the preview UI
            // This works alongside Camera2 encoder for AVC mode
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    val provider = cameraStateHolder.previewSurfaceProvider
                    if (provider != null) {
                        previewUseCase = androidx.camera.core.Preview.Builder().build().apply {
                            setSurfaceProvider(provider)
                        }
                        
                        // Only rebind for MJPEG mode (CameraX controls camera)
                        // For AVC mode (Camera2 controls camera), just create the preview use case
                        if (!useAvc) {
                            rebindCameraXUseCases()
                        } else {
                            // For AVC mode, bind preview to a separate CameraX session for preview only
                            bindPreviewOnly()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up preview", e)
                }
            }
        }
    }
    
    private suspend fun bindPreviewOnly() {
        try {
            if (cameraProvider == null) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this@StreamService)
                cameraProvider = cameraProviderFuture.get()
            }
            
            val provider = cameraProvider ?: return
            val preview = previewUseCase ?: return
            
            provider.unbindAll()
            val selector = if (selectedCamera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val camera = provider.bindToLifecycle(this@StreamService, selector, preview)
            
            // Store camera control for preview activity
            cameraStateHolder.cameraControl = camera.cameraControl
            cameraStateHolder.cameraInfo = camera.cameraInfo
            
            Log.i(TAG, "Bound CameraX preview for Camera2/AVC mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind preview-only CameraX", e)
        }
    }
    
    private fun reconfigureCamera2Session() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        
        try {
            session.close()
            
            val surfaces = mutableListOf<Surface>()
            encoderInputSurface?.let { surfaces.add(it) }
            imageReader?.surface?.let { surfaces.add(it) }
            cameraStateHolder.previewSurface?.let { surfaces.add(it) }
            
            if (surfaces.isEmpty()) {
                Log.w(TAG, "No surfaces for Camera2 reconfigure")
                return
            }
            
            val sessionCb2 = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(newSession: CameraCaptureSession) {
                    captureSession = newSession
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        surfaces.forEach { builder.addTarget(it) }
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        
                        newSession.setRepeatingRequest(builder.build(), null, cameraHandler)
                        Log.i(TAG, "Camera2 session reconfigured with ${surfaces.size} surfaces")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start repeating request after reconfigure", e)
                    }
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera2 session reconfigure failed")
                }
            }
            val reconfigSessionConfig = android.hardware.camera2.params.SessionConfiguration(
                android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                surfaces.map { android.hardware.camera2.params.OutputConfiguration(it) },
                java.util.concurrent.Executor { cmd -> cameraHandler?.post(cmd) },
                sessionCb2
            )
            camera.createCaptureSession(reconfigSessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Error reconfiguring Camera2 session", e)
        }
    }
    
    private suspend fun rebindCameraXUseCases() {
        try {
            val provider = cameraProvider ?: return
            provider.unbindAll()
            
            val selector = if (selectedCamera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val useCases = mutableListOf<androidx.camera.core.UseCase>()
            
            analysisUseCase?.let { useCases.add(it) }
            previewUseCase?.let { useCases.add(it) }
            
            if (useCases.isNotEmpty()) {
                provider.bindToLifecycle(this@StreamService, selector, *useCases.toTypedArray())
                Log.i(TAG, "CameraX rebound with ${useCases.size} use cases")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebind CameraX", e)
        }
    }
}
