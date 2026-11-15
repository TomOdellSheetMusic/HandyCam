package com.example.handycam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.handycam.data.model.api.ApiResponse
import com.example.handycam.data.model.api.CameraListItem
import com.example.handycam.data.model.api.CameraStatusResponse
import com.example.handycam.data.model.api.ServerInfo
import com.example.handycam.data.model.api.ServerStatus
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.app.ActivityManager
import android.content.ComponentName

private const val TAG = "KtorHttpsServerService"
private const val CHANNEL_ID = "ktor_https_server"
private const val NOTIF_ID = 2001

/**
 * Foreground service that runs a Ktor HTTP server.
 * The server runs on a specified port.
 */
@dagger.hilt.android.AndroidEntryPoint
class KtorHttpServerService : LifecycleService() {
    
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var httpPort = 8080
    private var isRunning = false
    @javax.inject.Inject lateinit var streamStateHolder: com.example.handycam.service.StreamStateHolder
    
    @javax.inject.Inject lateinit var cameraStateHolder: com.example.handycam.service.CameraStateHolder
    @javax.inject.Inject lateinit var preferences: com.example.handycam.data.preferences.PreferencesManager
    @javax.inject.Inject lateinit var cameraRepository: com.example.handycam.data.repository.CameraRepository

    private var startTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            HttpServerActions.START -> {
                httpPort = intent.getIntExtra("httpPort", 8443)
                startHttpServer()
            }
            HttpServerActions.STOP -> {
                stopHttpServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HTTP Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ktor HTTP Server Status"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) = run {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, KtorHttpServerService::class.java).apply {
            action = HttpServerActions.STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HTTP Server")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun startHttpServer() {
        if (isRunning) {
            Log.w(TAG, "Server already running")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // startTime = System.currentTimeMillis()

                // Run a plain HTTP server (no TLS/keystore) for local control
                server = embeddedServer(Netty, port = httpPort) {
                    configureRouting()
                }.apply { start(wait = false) }

                isRunning = true

                launch(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            NOTIF_ID,
                            buildNotification("Running on port $httpPort"),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        startForeground(NOTIF_ID, buildNotification("Running on port $httpPort"))
                    }
                    broadcastServerState(true)
                }

                Log.i(TAG, "HTTP Server started on port $httpPort")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
                isRunning = false
                launch(Dispatchers.Main) {
                    broadcastServerState(false)
                }
            }
        }
    }

    private fun Application.configureRouting() {
        install(ContentNegotiation) {
            json()
        }
        
        routing {
            get("/") {
                call.respondText(
                    homePageHtml,
                    ContentType.Text.Html
                )
            }
            
            get("/status") {
                val uptime = System.currentTimeMillis() - startTime
                    call.respond(ServerStatus(
                        status = "running",
                        streaming = (streamStateHolder.isStreaming.value),
                        port = (streamStateHolder.streamingPort.value),
                        uptime = uptime
                    ))
            }
            
            get("/info") {
                call.respond(ServerInfo(
                    name = "HandyCam HTTP Server",
                    version = "1.0.0",
                    protocol = "HTTP/1.1"
                ))
            }
            
            get("/health") {
                call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
            }
            
            // Add custom routes here as needed
            get("/api/camera/status") {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val selection = streamStateHolder.camera.value
                val id = try {
                    PhysicalLens.parse(selection)?.second ?: cm.cameraIdList.firstOrNull { id ->
                        id == selection || (selection == "back" && cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) ||
                            (selection == "front" && cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                    }?.let { VirtualLens.resolve(cm, it).cameraId }
                } catch (_: Exception) { null }
                val chars = try { id?.let { cm.getCameraCharacteristics(it) } } catch (_: Exception) { null }
                val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
                val cameraZoom = if (!streamStateHolder.useAvc.value) cameraStateHolder.cameraInfo?.zoomState?.value else null
                val cameraExposure = if (!streamStateHolder.useAvc.value) cameraStateHolder.cameraInfo?.exposureState else null
                val exposureRange = cameraExposure?.exposureCompensationRange
                    ?: chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                val exposureStep = cameraExposure?.exposureCompensationStep?.toFloat()
                    ?: chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
                val minZoom = cameraZoom?.minZoomRatio ?: range?.lower ?: 1f
                val maxZoom = cameraZoom?.maxZoomRatio ?: range?.upper ?: chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                val linear = streamStateHolder.zoom.value
                val ratio = cameraZoom?.zoomRatio ?: if (streamStateHolder.useAvc.value) minZoom + linear * (maxZoom - minZoom)
                    else 1f / ((1f - linear) / minZoom + linear / maxZoom)
                val status = CameraStatusResponse(
                    streaming = (streamStateHolder.isStreaming.value),
                    port = (streamStateHolder.streamingPort.value),
                    camera = (streamStateHolder.camera.value),
                    useScreenCapture = streamStateHolder.useScreenCapture.value,
                    cameraSwitchStatus = streamStateHolder.cameraSwitchStatus.value,
                    requestedCamera = streamStateHolder.requestedCamera.value,
                    width = if (streamStateHolder.useScreenCapture.value && streamStateHolder.isStreaming.value) streamStateHolder.screenSize.value?.first ?: streamStateHolder.width.value else streamStateHolder.width.value,
                    height = if (streamStateHolder.useScreenCapture.value && streamStateHolder.isStreaming.value) streamStateHolder.screenSize.value?.second ?: streamStateHolder.height.value else streamStateHolder.height.value,
                    fps = (streamStateHolder.fps.value),
                    maxFps = if (streamStateHolder.useScreenCapture.value) 60 else
                        FrameRateCapabilities.maxStreamingFps(cm, selection, streamStateHolder.width.value, streamStateHolder.height.value, streamStateHolder.useAvc.value),
                    jpegQuality = (streamStateHolder.jpegQuality.value),
                    useAvc = (streamStateHolder.useAvc.value),
                    useHevc = streamStateHolder.useHevc.value,
                    zoom = (streamStateHolder.zoom.value),
                    zoomRatio = ratio,
                    minZoomRatio = minZoom,
                    maxZoomRatio = maxZoom,
                    zoomLocked = (streamStateHolder.zoomLocked.value),
                    torchEnabled = (streamStateHolder.torchEnabled.value),
                    autoFocus = (streamStateHolder.autoFocus.value),
                    autoExposure = (streamStateHolder.autoExposure.value),
                    exposure = (streamStateHolder.exposure.value),
                    exposureMin = exposureRange?.lower ?: 0,
                    exposureMax = exposureRange?.upper ?: 0,
                    exposureStep = exposureStep,
                    avcBitrate = (streamStateHolder.avcBitrate.value)
                )
                call.respond(status)
            }

            get("/api/camera/list") {
                call.respond(cameraRepository.getAvailableCameras().map { camera ->
                    CameraListItem(camera.id, camera.displayName, camera.facing, camera.focalLength ?: "", camera.id)
                })
            }
            
            post("/api/camera/start") {

                try {
                    if (streamStateHolder.useScreenCapture.value) {
                        call.respond(HttpStatusCode.Conflict, ApiResponse(false, "Start screen capture from the app"))
                        return@post
                    }
                    if (!streamStateHolder.isStreaming.value && !isAppInForeground()) {
                        getSharedPreferences(RemoteStartKeys.PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(RemoteStartKeys.KEY_PENDING_REMOTE_START, true)
                            .apply()

                        // Post a high-priority notification with a PendingIntent to open MainActivity
                        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val notifChannelId = "remote_start_action"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val channel = NotificationChannel(
                                notifChannelId,
                                "Remote Start Required",
                                NotificationManager.IMPORTANCE_HIGH
                            ).apply {
                                description = "Tap to bring HandyCam to foreground and start streaming"
                            }
                            notifManager.createNotificationChannel(channel)
                        }
                        val openIntent = Intent(this@KtorHttpServerService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        // Ensure the Intent is explicit about the target component and package
                        openIntent.component = ComponentName(packageName, MainActivity::class.java.name)
                        openIntent.setPackage(packageName)
                        // include streaming port so MainActivity can handle a deferred start
                        openIntent.putExtra("streamingPort", streamStateHolder.streamingPort.value)
                        val openPending = PendingIntent.getActivity(
                            this@KtorHttpServerService,
                            100,
                            openIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val notif = NotificationCompat.Builder(this@KtorHttpServerService, notifChannelId)
                            .setContentTitle("HandyCam: Action Required")
                            .setContentText("Tap to open app and start streaming")
                            .setSmallIcon(R.drawable.ic_launcher_foreground)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setCategory(NotificationCompat.CATEGORY_CALL)
                            .setContentIntent(openPending)
                            .setAutoCancel(true)
                            .build()
                        notifManager.notify(1337, notif)

                        call.respond(
                            HttpStatusCode.Conflict,
                            ApiResponse(
                                false,
                                "Start deferred: tap notification to open HandyCam and start streaming"
                            )
                        )
                        return@post
                    }

                    val intent = Intent(this@KtorHttpServerService, StreamService::class.java).apply {
                        action = StreamActions.START
                        putExtra("host", streamStateHolder.host.value)
                        putExtra("streamingPort", streamStateHolder.streamingPort.value)
                        putExtra("width", streamStateHolder.width.value)
                        putExtra("height", streamStateHolder.height.value)
                        putExtra("camera", streamStateHolder.camera.value)
                        putExtra("jpegQuality", streamStateHolder.jpegQuality.value)
                        putExtra("targetFps", streamStateHolder.fps.value)
                        putExtra("avcBitrate", streamStateHolder.avcBitrate.value)
                        putExtra("useAvc", streamStateHolder.useAvc.value)
                        putExtra("useHevc", streamStateHolder.useHevc.value)
                        putExtra("useHdr", streamStateHolder.useHdr.value)
                    }
                    startService(intent)
                    streamStateHolder.setStreaming(true)
                    call.respond(ApiResponse(true, "Camera streaming started"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, "Failed to start camera: ${e.message}"))
                }
            }
            
            post("/api/camera/stop") {
                try {
                    val intent = Intent(this@KtorHttpServerService, StreamService::class.java).apply {
                        action = StreamActions.STOP
                    }
                    startService(intent)
                    streamStateHolder.setStreaming(false)
                    call.respond(ApiResponse(true, "Camera streaming stopped"))
                } catch (e: Exception) { 
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, "Failed to stop camera: ${e.message}"))
                }
            }
            
            post("/api/camera/switch") {
                try {
                    if (streamStateHolder.useScreenCapture.value && streamStateHolder.isStreaming.value) {
                        call.respond(HttpStatusCode.Conflict, ApiResponse(false, "Stop screen capture before switching cameras"))
                        return@post
                    }
                    val params = call.receive<Map<String, String>>()
                    val camera = params["camera"] ?: throw IllegalArgumentException("Missing camera")
                    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    if (camera !in manager.cameraIdList && camera != "back" && camera != "front" && !PhysicalLens.valid(manager, camera)) {
                        call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Camera is not available"))
                        return@post
                    }
                    val maxFps = FrameRateCapabilities.maxStreamingFps(manager, camera, streamStateHolder.width.value, streamStateHolder.height.value, streamStateHolder.useAvc.value)
                    val adjustedFps = FrameRateCapabilities.clampTarget(streamStateHolder.fps.value, maxFps)
                    val reduced = adjustedFps < streamStateHolder.fps.value
                    val intent = Intent(this@KtorHttpServerService, StreamService::class.java).apply {
                        action = StreamActions.SET_CAMERA
                        putExtra("camera", camera)
                    }
                    if (streamStateHolder.isStreaming.value) {
                        if (streamStateHolder.cameraSwitchStatus.value == "switching") {
                            call.respond(HttpStatusCode.Conflict, ApiResponse(false, "Camera switch already in progress"))
                            return@post
                        }
                        streamStateHolder.setCameraSwitchStatus("switching", camera)
                        try { startService(intent) } catch (e: Exception) {
                            streamStateHolder.setCameraSwitchStatus("Could not start camera switch")
                            throw e
                        }
                        call.respond(ApiResponse(true, if (reduced) "Switching camera and reducing target to $adjustedFps fps" else "Switching camera"))
                    } else {
                        preferences.updateUseScreenCapture(false)
                        preferences.updateCamera(camera)
                        streamStateHolder.setUseScreenCapture(false)
                        streamStateHolder.setCamera(camera)
                        streamStateHolder.setFps(adjustedFps)
                        streamStateHolder.setCameraSwitchStatus("")
                        streamStateHolder.setZoom(0f)
                        call.respond(ApiResponse(true, if (reduced) "Camera updated; target reduced to $adjustedFps fps" else "Camera selection updated"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, "Failed to switch camera: ${e.message}"))
                }
            }

            // New endpoints for all controls
            post("/api/settings/fps") {
                try {
                    if (streamStateHolder.useScreenCapture.value && streamStateHolder.isStreaming.value) {
                        call.respond(HttpStatusCode.Conflict, ApiResponse(false, "Stop screen capture before changing frame rate"))
                        return@post
                    }
                    val params = call.receive<Map<String, Int>>()
                    val fps = params["fps"] ?: 30
                    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val maxFps = if (streamStateHolder.useScreenCapture.value) 60 else
                        FrameRateCapabilities.maxStreamingFps(manager, streamStateHolder.camera.value, streamStateHolder.width.value, streamStateHolder.height.value, streamStateHolder.useAvc.value)
                    require(fps in 1..maxFps) { "FPS must be between 1 and $maxFps for this configuration" }
                    streamStateHolder.setFps(fps)
                    call.respond(ApiResponse(true, "FPS updated to $fps"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid FPS value: ${e.message}"))
                }
            }

            post("/api/settings/resolution") {
                try {
                    if (streamStateHolder.useScreenCapture.value) {
                        call.respond(HttpStatusCode.Conflict, ApiResponse(false, "Screen capture uses the display resolution"))
                        return@post
                    }
                    val params = call.receive<Map<String, Int>>()
                    val width = params["width"] ?: 1080
                    val height = params["height"] ?: 1920
                    require(width > 0 && height > 0) { "Width and height must be positive" }
                    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val maxFps = if (streamStateHolder.useScreenCapture.value) 60 else
                        FrameRateCapabilities.maxStreamingFps(manager, streamStateHolder.camera.value, width, height, streamStateHolder.useAvc.value)
                    val adjustedFps = FrameRateCapabilities.clampTarget(streamStateHolder.fps.value, maxFps)
                    val reduced = adjustedFps < streamStateHolder.fps.value
                    preferences.updateResolution(width, height)
                    if (reduced) preferences.updateFps(adjustedFps)
                    streamStateHolder.setWidth(width)
                    streamStateHolder.setHeight(height)
                    streamStateHolder.setFps(adjustedFps)
                    call.respond(ApiResponse(true, "Resolution updated to ${width}x${height}" + if (reduced) "; target reduced to $adjustedFps fps" else ""))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid resolution: ${e.message}"))
                }
            }

            post("/api/settings/jpeg-quality") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val quality = params["quality"] ?: 85
                    streamStateHolder.setJpegQuality(quality)
                    call.respond(ApiResponse(true, "JPEG quality updated to $quality"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid JPEG quality: ${e.message}"))
                }
            }

            post("/api/settings/use-avc") {
                try {
                    val params = call.receive<Map<String, Boolean>>()
                    val enabled = params["enabled"] ?: false
                    if (streamStateHolder.isStreaming.value) {
                        call.respond(HttpStatusCode.Conflict, ApiResponse(false, "Stop the stream before changing codec"))
                        return@post
                    }
                    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val maxFps = if (streamStateHolder.useScreenCapture.value) 60 else
                        FrameRateCapabilities.maxStreamingFps(manager, streamStateHolder.camera.value, streamStateHolder.width.value, streamStateHolder.height.value, enabled)
                    val adjustedFps = FrameRateCapabilities.clampTarget(streamStateHolder.fps.value, maxFps)
                    val reduced = adjustedFps < streamStateHolder.fps.value
                    preferences.updateCodec(enabled, false)
                    streamStateHolder.setUseAvc(enabled)
                    streamStateHolder.setUseHevc(false)
                    streamStateHolder.setFps(adjustedFps)
                    call.respond(ApiResponse(true, "useAvc set to $enabled" + if (reduced) "; target reduced to $adjustedFps fps" else ""))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid use-avc value: ${e.message}"))
                }
            }

            post("/api/settings/codec") {
                try {
                    val codec = call.receive<Map<String, String>>()["codec"]
                    if (codec !in setOf("mjpeg", "avc", "hevc")) {
                        call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Choose MJPEG, H.264, or HEVC"))
                        return@post
                    }
                    if (streamStateHolder.isStreaming.value) {
                        call.respond(HttpStatusCode.Conflict, ApiResponse(false, "Stop the stream before changing codec"))
                        return@post
                    }
                    val encoded = codec != "mjpeg"
                    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val maxFps = if (streamStateHolder.useScreenCapture.value) 60 else
                        FrameRateCapabilities.maxStreamingFps(manager, streamStateHolder.camera.value, streamStateHolder.width.value, streamStateHolder.height.value, encoded)
                    val adjustedFps = FrameRateCapabilities.clampTarget(streamStateHolder.fps.value, maxFps)
                    val reduced = adjustedFps < streamStateHolder.fps.value
                    preferences.updateCodec(encoded, codec == "hevc")
                    streamStateHolder.setUseHevc(codec == "hevc")
                    streamStateHolder.setUseAvc(encoded)
                    streamStateHolder.setFps(adjustedFps)
                    call.respond(ApiResponse(true, "Codec set to ${codec!!.uppercase()}" + if (reduced) "; target reduced to $adjustedFps fps" else ""))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Could not change codec: ${e.message}"))
                }
            }

            post("/api/settings/avc-bitrate") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val bitrate = params["bitrate"] ?: -1
                    require(bitrate == -1 || bitrate > 0) { "Bitrate must be positive or automatic" }
                    preferences.updateAvcBitrate(bitrate.takeIf { it > 0 })
                    streamStateHolder.setAvcBitrate(bitrate)
                    call.respond(ApiResponse(true, if (bitrate > 0) "Bitrate updated" else "Bitrate set to auto"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid avc bitrate: ${e.message}"))
                }
            }

            post("/api/settings/torch") {
                try {
                    val params = call.receive<Map<String, Boolean>>()
                    val enabled = params["enabled"] ?: false
                    streamStateHolder.setTorchEnabled(enabled)
                    call.respond(ApiResponse(true, "Torch ${if (enabled) "enabled" else "disabled"}"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Failed to set torch: ${e.message}"))
                }
            }

            post("/api/settings/auto-focus") {
                try {
                    val params = call.receive<Map<String, Boolean>>()
                    val enabled = params["enabled"] ?: true
                    streamStateHolder.setAutoFocus(enabled)
                    call.respond(ApiResponse(true, "Auto focus ${if (enabled) "enabled" else "disabled"}"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Failed to set auto focus: ${e.message}"))
                }
            }

            post("/api/settings/exposure") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val exposure = params["exposure"] ?: 0
                    streamStateHolder.setExposure(exposure)
                    call.respond(ApiResponse(true, "Exposure updated to $exposure"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid exposure value: ${e.message}"))
                }
            }

            post("/api/settings/auto-exposure") {
                try {
                    val params = call.receive<Map<String, Boolean>>()
                    val enabled = params["enabled"] ?: throw IllegalArgumentException("Missing enabled")
                    if (!streamStateHolder.useAvc.value) {
                        call.respond(HttpStatusCode.Conflict, ApiResponse(false, "Exposure lock is available in AVC mode"))
                        return@post
                    }
                    streamStateHolder.setAutoExposure(enabled)
                    call.respond(ApiResponse(true, if (enabled) "Exposure unlocked" else "Exposure locked"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid auto-exposure value: ${e.message}"))
                }
            }

            post("/api/settings/zoom") {
                try {
                    val params = call.receive<Map<String, Double>>()
                    val normalizedZoom = when {
                        params.containsKey("zoomPercent") -> ((params["zoomPercent"] ?: 0.0) / 100.0).toFloat()
                        params.containsKey("linearZoom") -> (params["linearZoom"] ?: 0.0).toFloat()
                        params.containsKey("zoom") -> (params["zoom"] ?: 0.0).toFloat()
                        else -> throw IllegalArgumentException("Missing zoom value")
                    }.coerceIn(0f, 1f)

                    streamStateHolder.setZoom(normalizedZoom)
                    call.respond(ApiResponse(true, "Zoom updated to ${"%.0f".format(normalizedZoom * 100)}%"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid zoom value: ${e.message}"))
                }
            }

            post("/api/settings/focus") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val focus = params["focus"] ?: 0
                    streamStateHolder.setFocus(focus)
                    call.respond(ApiResponse(true, "Focus updated to $focus"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid focus value: ${e.message}"))
                }
            }

            post("/api/settings/port") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val port = params["streamingPort"] ?: params["port"] ?: 4747
                    streamStateHolder.setStreamingPort(port)
                    call.respond(ApiResponse(true, "Streaming port updated to $port"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid port: ${e.message}"))
                }
            }

            get("/camera") {
                // Serve HTML from assets for easier editing and caching
                try {
                    val html = this@KtorHttpServerService.assets.open("camera_control.html").bufferedReader().use { it.readText() }
                    call.response.headers.append("Cache-Control", "no-store")
                    call.respondText(html, ContentType.Text.Html)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to load camera UI: ${e.message}")
                }
            }
        }
    }

    // Keystore/SSL helper removed: server now runs plain HTTP (no TLS) for local control.

    private fun stopHttpServer() {
        try {
            server?.stop(1000, 2000)
            server = null
            isRunning = false
            Log.i(TAG, "HTTP Server stopped")
            broadcastServerState(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    private fun broadcastServerState(running: Boolean) {
        streamStateHolder.setHttpsRunning(running)
    }

    private fun isAppInForeground(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val myPkg = packageName
            am.runningAppProcesses?.any { proc ->
                proc.processName == myPkg &&
                    proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to determine app foreground state", e)
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHttpServer()
    }
}
