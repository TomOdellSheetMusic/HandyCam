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

private const val TAG = "StreamService"
private const val CHANNEL_ID = "handycam_stream"
private const val NOTIF_ID = 1001
private const val ACTION_START = "com.example.handycam.ACTION_START"
private const val ACTION_STOP = "com.example.handycam.ACTION_STOP"
private const val ACTION_SET_CAMERA = "com.example.handycam.ACTION_SET_CAMERA"

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
    private var imageReader: android.media.ImageReader? = null
    private var encoderWidth: Int = 0
    private var encoderHeight: Int = 0
    // CameraX fields for MJPEG path so we can rebind while running
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var cameraExecutor: java.util.concurrent.ExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra("host") ?: "0.0.0.0"
                val port = intent.getIntExtra("port", 4747)
                val width = intent.getIntExtra("width", 1080 ) // phone is upright so width and height are swapped
                val height = intent.getIntExtra("height", 1920)
                selectedCamera = intent.getStringExtra("camera") ?: "back"
                jpegQuality = intent.getIntExtra("jpegQuality", 85)
                targetFps = intent.getIntExtra("targetFps", 60)
                useAvc = intent.getBooleanExtra("useAvc", false)
                val ab = intent.getIntExtra("avcBitrate", -1)
                avcBitrateUser = if (ab > 0) ab else null

                try {
                    startForeground(NOTIF_ID, buildNotification("Streaming on $port — $selectedCamera — q=$jpegQuality fps=$targetFps"))
                } catch (se: SecurityException) {
                    Log.e(TAG, "Unable to start foreground service with camera type: missing permission", se)
                    // Stop service to avoid repeatedly failing; caller should request the permission.
                    stopSelf()
                    return START_NOT_STICKY
                }
                startStreaming(host, port, width, height, selectedCamera, jpegQuality, targetFps, useAvc)
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
                    // perform camera switch while streaming
                    try {
                        handleCameraSwitchRequest(newCam)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error switching camera", e)
                    }
                }
            }
            ACTION_STOP -> {
                stopStreaming()
                stopForeground(true)
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
        val intent = Intent("com.example.handycam.STREAM_STATE").apply {
            putExtra("isStreaming", isStreaming)
        }
        sendBroadcast(intent)
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

    private fun startStreaming(bindHost: String, port: Int, width: Int, height: Int, camera: String, jpegQ: Int, fps: Int, useAvcFlag: Boolean) {
        if (running) return
        running = true
        // store runtime options
        selectedCamera = camera
        jpegQuality = jpegQ
        targetFps = fps
        useAvc = useAvcFlag
        requestedWidth = width
        requestedHeight = height

        if (useAvc) {
            // Start Camera2 -> encoder pipeline. The helper will pick a supported
            // camera output size and call setupEncoder(...) with a compatible size.
            try {
                startCamera2ToEncoder(width, height, targetFps)
                startEncoderOutputReader()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize encoder or camera2 pipeline", e)
                // fallback to MJPEG if AVC init fails
                useAvc = false
                stopCamera2()
            }
        } else {
            // Start CameraX analysis to capture frames (MJPEG path)
            lifecycleScope.launch(Dispatchers.Main) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this@StreamService)
                val cameraProvider = cameraProviderFuture.get()
                // keep reference so we can rebind while streaming
                this@StreamService.cameraProvider = cameraProvider

                val analysisUseCase = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(width, height))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                cameraExecutor = Executors.newSingleThreadExecutor()
                analysisUseCase.setAnalyzer(cameraExecutor) { image ->
                    // compress on the camera executor to avoid blocking UI
                    handleImageProxy(image)
                }
                // keep reference so switch can rebind
                this@StreamService.analysisUseCase = analysisUseCase

                try {
                    cameraProvider.unbindAll()
                    val selector = if (selectedCamera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.bindToLifecycle(this@StreamService, selector, analysisUseCase)
                    Log.i(TAG, "Camera bound in service (MJPEG): $selectedCamera")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind camera use cases in service", e)
                }
            }
        }

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
        cameraExecutor = null

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
        if (encoder == null) return
        encoderOutputThread = Thread {
            try {
                val codec = encoder ?: return@Thread
                val info = BufferInfo()
                while (running && codec != null) {
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
                        codec.releaseOutputBuffer(outIndex, false)
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
            }
            Log.i(TAG, "Encoder output reader exiting")
        }
        encoderOutputThread?.start()
    }

    private fun stopEncoderOutputReader() {
        try {
            encoderOutputThread?.interrupt()
        } catch (_: Exception) {}
        encoderOutputThread = null
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

            if (req.contains("/v5/video/") || req.contains("/v2/video/") || req.contains("/v1/video/")) {
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
            val map = chars?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val sizes = map.getOutputSizes(SurfaceTexture::class.java)
                if (sizes != null && sizes.isNotEmpty()) {
                    // find exact match or choose closest by area
                    var chosen = sizes[0]
                    var bestDiff = Math.abs(chosen.width * chosen.height - reqWidth * reqHeight)
                    for (s in sizes) {
                        if (s.width == reqWidth && s.height == reqHeight) {
                            chosen = s
                            bestDiff = 0
                            break
                        }
                        val diff = Math.abs(s.width * s.height - reqWidth * reqHeight)
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

                        builder.addTarget(surface)
                        val targets = listOf(surface, ir.surface)
                        camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    session.setRepeatingRequest(builder.build(), null, cameraHandler)
                                    Log.i(TAG, "Camera2 session configured and repeating request started")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start repeating request", e)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Camera2 session configuration failed")
                            }
                        }, cameraHandler)
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
                    val selector = if (selectedCamera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val useCase = analysisUseCase ?: run {
                        Log.w(TAG, "Analysis use case not available when switching camera")
                        return@launch
                    }
                    provider.bindToLifecycle(this@StreamService, selector, useCase)
                    Log.i(TAG, "Switched CameraX binding to $selectedCamera")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebind CameraX for new camera", e)
                }
            }
        }
    }
}
