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

private const val TAG = "StreamService"
private const val CHANNEL_ID = "handycam_stream"
private const val NOTIF_ID = 1001
private const val ACTION_START = "com.example.handycam.ACTION_START"
private const val ACTION_STOP = "com.example.handycam.ACTION_STOP"

class StreamService : LifecycleService() {
    private val frameQueue = LinkedBlockingQueue<ByteArray>(10)
    private var serverThread: Thread? = null
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var channelNeedsUserEnable: Boolean = false

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
                val width = intent.getIntExtra("width", 1280)
                val height = intent.getIntExtra("height", 720)
                try {
                    startForeground(NOTIF_ID, buildNotification("Streaming on $port"))
                } catch (se: SecurityException) {
                    Log.e(TAG, "Unable to start foreground service with camera type: missing permission", se)
                    // Stop service to avoid repeatedly failing; caller should request the permission.
                    stopSelf()
                    return START_NOT_STICKY
                }
                startStreaming(host, port, width, height)
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

    private fun createNotificationChannel() {
        val name = "HandyCam Stream"
        val desc = "Foreground service for streaming camera"
        // use DEFAULT importance so the notification is visible in the shade when expanded
        val chan = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
        chan.description = desc
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(chan)
    }

    private fun startStreaming(bindHost: String, port: Int, width: Int, height: Int) {
        if (running) return
        running = true

        // Start CameraX analysis to capture frames
        lifecycleScope.launch(Dispatchers.Main) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this@StreamService)
            val cameraProvider = cameraProviderFuture.get()

            val analysisUseCase = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(width, height))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val cameraExecutor = Executors.newSingleThreadExecutor()
            analysisUseCase.setAnalyzer(cameraExecutor) { image ->
                handleImageProxy(image)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this@StreamService, CameraSelector.DEFAULT_BACK_CAMERA, analysisUseCase)
                Log.i(TAG, "Camera bound in service")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera use cases in service", e)
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
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverThread?.interrupt()
        serverThread = null
        frameQueue.clear()
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

            val baos = ByteArrayOutputStream()
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, baos)
            val jpeg = baos.toByteArray()

            if (!frameQueue.offer(jpeg)) {
                frameQueue.poll()
                frameQueue.offer(jpeg)
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

            if (req.contains("/v5/video/") || req.contains("/v2/video/") || req.contains("/v1/video/")) {
                Log.i(TAG, "Starting MJPEG stream to client (service)")
                val out = client.getOutputStream()
                val pollTimeoutMs = 1000L
                try {
                    while (running && !client.isClosed && client.isConnected) {
                        val frame = try {
                            frameQueue.poll(pollTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } catch (ie: InterruptedException) {
                            null
                        }

                        if (frame == null) {
                            continue
                        }

                        val pts = System.currentTimeMillis()
                        val header = ByteBuffer.allocate(12)
                        header.putLong(pts)
                        header.putInt(frame.size)

                        try {
                            out.write(header.array())
                            out.write(frame)
                            out.flush()
                        } catch (se: java.net.SocketException) {
                            Log.i(TAG, "Socket closed by peer while writing: ${se.message}")
                            break
                        } catch (ioe: java.io.IOException) {
                            Log.i(TAG, "IO error while writing to client: ${ioe.message}")
                            break
                        }

                        try { Thread.sleep(40) } catch (_: InterruptedException) { break }
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
}
