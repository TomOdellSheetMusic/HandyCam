package com.example.handycam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
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
import kotlinx.serialization.Serializable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger

private const val TAG = "KtorHttpsServerService"
private const val CHANNEL_ID = "ktor_https_server"
private const val NOTIF_ID = 2001
private const val ACTION_START_SERVER = "com.example.handycam.ACTION_START_HTTPS_SERVER"
private const val ACTION_STOP_SERVER = "com.example.handycam.ACTION_STOP_HTTPS_SERVER"

/**
 * Foreground service that runs a Ktor HTTPS server.
 * The server runs on a specified port with SSL/TLS encryption.
 */
class KtorHttpsServerService : LifecycleService() {
    
    private var server: NettyApplicationEngine? = null
    private var serverPort = 8443
    private var isRunning = false
    private lateinit var settingsManager: SettingsManager
       @Serializable
    data class ServerStatus(
        val status: String,
        val streaming: Boolean,
        val port: Int,
        val uptime: Long
    )
    
    @Serializable
    data class ServerInfo(
        val name: String,
        val version: String,
        val protocol: String
    )

    @Serializable
    data class CameraStatus(
        val streaming: Boolean,
        val port: Int,
        val camera: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val jpegQuality: Int,
        val useAvc: Boolean,
        val torchEnabled: Boolean,
        val autoFocus: Boolean,
        val exposure: Int
        , val avcBitrate: Int
    )

    @Serializable
    data class ApiResponse(
        val success: Boolean,
        val message: String
    )

    @Serializable
    data class CameraInfo(
        val id: String,
        val label: String,
        val facing: String,
        val focalDesc: String
    )
    
    private var startTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settingsManager = SettingsManager.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START_SERVER -> {
                serverPort = intent.getIntExtra("port", 8443)
                startHttpsServer()
            }
            ACTION_STOP_SERVER -> {
                stopHttpsServer()
                stopForeground(true)
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
                "HTTPS Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ktor HTTPS Server Status"
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

        val stopIntent = Intent(this, KtorHttpsServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HTTPS Server")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun startHttpsServer() {
        if (isRunning) {
            Log.w(TAG, "Server already running")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // startTime = System.currentTimeMillis()
                
                // Generate a self-signed certificate for local HTTPS
                // This is a "snakeoil" certificate for local use only
                val keyStoreFile = File(filesDir, "keystore.jks")
                val keyStore = loadOrCreateSelfSignedKeyStore(keyStoreFile)
                
                val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                keyManagerFactory.init(keyStore, "changeit".toCharArray())
                
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(keyManagerFactory.keyManagers, null, null)
                
                // Create HTTPS server with self-signed certificate
                val environment = applicationEngineEnvironment {
                    module {
                        configureRouting()
                    }
                    
                    sslConnector(
                        keyStore = keyStore,
                        keyAlias = "handycam",
                        keyStorePassword = { "changeit".toCharArray() },
                        privateKeyPassword = { "changeit".toCharArray() }
                    ) {
                        port = serverPort
                        keyStorePath = keyStoreFile
                    }
                }
                
                server = embeddedServer(Netty, environment).apply {
                    start(wait = false)
                }
                
                isRunning = true
                
                launch(Dispatchers.Main) {
                    startForeground(NOTIF_ID, buildNotification("Running on port $serverPort"))
                    broadcastServerState(true)
                }
                
                Log.i(TAG, "HTTPS Server started on port $serverPort")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTPS server", e)
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
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>HandyCam HTTPS Server</title>
                        <style>
                            body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }
                            .container { background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                            h1 { color: #333; }
                            .endpoint { background: #f8f8f8; padding: 10px; margin: 10px 0; border-left: 3px solid #4CAF50; }
                            code { background: #e8e8e8; padding: 2px 6px; border-radius: 3px; }
                        </style>
                    </head>
                    <body>
                        <div class="container">
                            <h1>🚀 HandyCam HTTPS Server</h1>
                            <p>Server is running successfully!</p>
                            <h2>Available Endpoints:</h2>
                            <div class="endpoint">
                                <strong>GET /</strong> - This welcome page
                            </div>
                            <div class="endpoint">
                                <strong>GET /status</strong> - Server status (JSON)
                            </div>
                            <div class="endpoint">
                                <strong>GET /info</strong> - Server information (JSON)
                            </div>
                            <div class="endpoint">
                                <strong>GET /health</strong> - Health check
                            </div>
                            <div class="endpoint">
                                <strong>GET /camera</strong> - Camera control web UI
                            </div>
                            <div class="endpoint">
                                <strong>POST /api/camera/start</strong> - Start camera streaming
                            </div>
                            <div class="endpoint">
                                <strong>POST /api/camera/stop</strong> - Stop camera streaming
                            </div>
                            <div class="endpoint">
                                <strong>POST /api/camera/switch</strong> - Switch camera (body: {"camera": "back|front"})
                            </div>
                        </div>
                    </body>
                    </html>
                    """.trimIndent(),
                    ContentType.Text.Html
                )
            }
            
            get("/status") {
                val uptime = System.currentTimeMillis() - startTime
                call.respond(ServerStatus(
                    status = "running",
                    streaming = (settingsManager.isStreaming.value ?: false),
                    port = serverPort,
                    uptime = uptime
                ))
            }
            
            get("/info") {
                call.respond(ServerInfo(
                    name = "HandyCam HTTPS Server",
                    version = "1.0.0",
                    protocol = "HTTP/1.1"
                ))
            }
            
            get("/health") {
                call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
            }
            
            // Add custom routes here as needed
            get("/api/camera/status") {
                val status = CameraStatus(
                    streaming = (settingsManager.isStreaming.value ?: false),
                    port = (settingsManager.port.value ?: 4747),
                    camera = (settingsManager.camera.value ?: "back"),
                    width = (settingsManager.width.value ?: 1920),
                    height = (settingsManager.height.value ?: 1080),
                    fps = (settingsManager.fps.value ?: 30),
                    jpegQuality = (settingsManager.jpegQuality.value ?: 85),
                    useAvc = (settingsManager.useAvc.value ?: false),
                    torchEnabled = (settingsManager.torchEnabled.value ?: false),
                    autoFocus = (settingsManager.autoFocus.value ?: true),
                    exposure = (settingsManager.exposure.value ?: 0),
                    avcBitrate = (settingsManager.avcBitrate.value ?: -1)
                )
                call.respond(status)
            }

            get("/api/camera/list") {
                val list = mutableListOf<CameraInfo>()
                // logical picks
                list.add(CameraInfo("back", "back", "back", ""))
                list.add(CameraInfo("front", "front", "front", ""))

                try {
                    val cm = this@KtorHttpsServerService.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    cm.cameraIdList.forEach { id ->
                        try {
                            val chars = cm.getCameraCharacteristics(id)
                            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                                CameraCharacteristics.LENS_FACING_BACK -> "back"
                                else -> "unknown"
                            }
                            val focalArr = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            val focalDesc = if (focalArr != null && focalArr.isNotEmpty()) String.format("f=%.1fmm", focalArr[0]) else ""
                            val label = if (focalDesc.isNotEmpty()) "$id ($facing, $focalDesc)" else "$id ($facing)"
                            list.add(CameraInfo(id, label, facing, focalDesc))
                        } catch (_: Exception) { }
                    }
                } catch (e: Exception) {
                    // ignore camera enumeration failures
                }

                call.respond(list)
            }
            
            post("/api/camera/start") {
                try {
                    val intent = Intent(this@KtorHttpsServerService, StreamService::class.java).apply {
                        action = "com.example.handycam.ACTION_START"
                        putExtra("host", settingsManager.host.value ?: "0.0.0.0")
                        putExtra("port", settingsManager.port.value ?: 4747)
                        putExtra("width", settingsManager.width.value ?: 1080)
                        putExtra("height", settingsManager.height.value ?: 1920)
                        putExtra("camera", settingsManager.camera.value ?: "back")
                        putExtra("jpegQuality", settingsManager.jpegQuality.value ?: 85)
                        putExtra("targetFps", settingsManager.fps.value ?: 30)
                        putExtra("avcBitrate", settingsManager.avcBitrate.value ?: -1)
                        putExtra("useAvc", settingsManager.useAvc.value ?: false)
                    }
                    startService(intent)
                    settingsManager.setStreaming(true)
                    call.respond(ApiResponse(true, "Camera streaming started"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, "Failed to start camera: ${e.message}"))
                }
            }
            
            post("/api/camera/stop") {
                try {
                    val intent = Intent(this@KtorHttpsServerService, StreamService::class.java).apply {
                        action = "com.example.handycam.ACTION_STOP"
                    }
                    startService(intent)
                    settingsManager.setStreaming(false)
                    call.respond(ApiResponse(true, "Camera streaming stopped"))
                } catch (e: Exception) { 
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, "Failed to stop camera: ${e.message}"))
                }
            }
            
            post("/api/camera/switch") {
                try {
                    val params = call.receive<Map<String, String>>()
                    val camera = params["camera"] ?: "back"
                    val intent = Intent(this@KtorHttpsServerService, StreamService::class.java).apply {
                        action = "com.example.handycam.ACTION_SET_CAMERA"
                        putExtra("camera", camera)
                    }
                    startService(intent)
                    settingsManager.setCamera(camera)
                    call.respond(ApiResponse(true, "Switched to $camera camera"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse(false, "Failed to switch camera: ${e.message}"))
                }
            }

            // New endpoints for all controls
            post("/api/settings/fps") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val fps = params["fps"] ?: 30
                    settingsManager.setFps(fps)
                    call.respond(ApiResponse(true, "FPS updated to $fps"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid FPS value: ${e.message}"))
                }
            }

            post("/api/settings/resolution") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val width = params["width"] ?: 1080
                    val height = params["height"] ?: 1920
                    settingsManager.setWidth(width)
                    settingsManager.setHeight(height)
                    call.respond(ApiResponse(true, "Resolution updated to ${width}x${height}"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid resolution: ${e.message}"))
                }
            }

            post("/api/settings/jpeg-quality") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val quality = params["quality"] ?: 85
                    settingsManager.setJpegQuality(quality)
                    call.respond(ApiResponse(true, "JPEG quality updated to $quality"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid JPEG quality: ${e.message}"))
                }
            }

            post("/api/settings/use-avc") {
                try {
                    val params = call.receive<Map<String, Boolean>>()
                    val enabled = params["enabled"] ?: false
                    settingsManager.setUseAvc(enabled)
                    call.respond(ApiResponse(true, "useAvc set to $enabled"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid use-avc value: ${e.message}"))
                }
            }

            post("/api/settings/avc-bitrate") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val bitrate = params["bitrate"] ?: -1
                    settingsManager.setAvcBitrate(bitrate)
                    call.respond(ApiResponse(true, "AVC bitrate set to $bitrate"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid avc bitrate: ${e.message}"))
                }
            }

            post("/api/settings/torch") {
                try {
                    val params = call.receive<Map<String, Boolean>>()
                    val enabled = params["enabled"] ?: false
                    settingsManager.setTorchEnabled(enabled)
                    call.respond(ApiResponse(true, "Torch ${if (enabled) "enabled" else "disabled"}"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Failed to set torch: ${e.message}"))
                }
            }

            post("/api/settings/auto-focus") {
                try {
                    val params = call.receive<Map<String, Boolean>>()
                    val enabled = params["enabled"] ?: true
                    settingsManager.setAutoFocus(enabled)
                    call.respond(ApiResponse(true, "Auto focus ${if (enabled) "enabled" else "disabled"}"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Failed to set auto focus: ${e.message}"))
                }
            }

            post("/api/settings/exposure") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val exposure = params["exposure"] ?: 0
                    settingsManager.setExposure(exposure)
                    call.respond(ApiResponse(true, "Exposure updated to $exposure"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid exposure value: ${e.message}"))
                }
            }

            post("/api/settings/focus") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val focus = params["focus"] ?: 0
                    settingsManager.setFocus(focus)
                    call.respond(ApiResponse(true, "Focus updated to $focus"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid focus value: ${e.message}"))
                }
            }

            post("/api/settings/port") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val port = params["port"] ?: 4747
                    settingsManager.setPort(port)
                    call.respond(ApiResponse(true, "Port updated to $port"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse(false, "Invalid port: ${e.message}"))
                }
            }

            get("/camera") {
                // Serve HTML from assets for easier editing and caching
                try {
                    val html = this@KtorHttpsServerService.assets.open("camera_control.html").bufferedReader().use { it.readText() }
                    call.respondText(html, ContentType.Text.Html)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to load camera UI: ${e.message}")
                }
            }
        }
    }

    private fun loadOrCreateSelfSignedKeyStore(file: File): KeyStore {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        val password = "changeit".toCharArray()
        
        if (file.exists()) {
            try {
                file.inputStream().use {
                    keyStore.load(it, password)
                }
                // Verify the certificate exists
                if (keyStore.containsAlias("handycam") && keyStore.getCertificate("handycam") != null) {
                    Log.i(TAG, "Existing self-signed certificate loaded successfully")
                    return keyStore
                } else {
                    Log.w(TAG, "Keystore exists but certificate is invalid, regenerating...")
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load existing keystore, regenerating...", e)
                file.delete()
            }
        }
        
        // Create a new keystore with a self-signed certificate
        keyStore.load(null, password)
        
        try {
            // Generate RSA key pair
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.generateKeyPair()
            
            // Create self-signed certificate using BouncyCastle
            val now = Date()
            val notBefore = Date(now.time - 86400000L) // 1 day before
            val notAfter = Date(now.time + 315360000000L) // 10 years after
            
            val issuer = X500Name("CN=localhost, OU=HandyCam, O=HandyCam, L=Local, ST=Local, C=US")
            val subject = issuer // Self-signed
            
            val publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
            val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
            
            val certBuilder = X509v3CertificateBuilder(
                issuer,
                serialNumber,
                notBefore,
                notAfter,
                subject,
                publicKeyInfo
            )
            
            val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.private)
            val certificateHolder = certBuilder.build(signer)
            val certificate = JcaX509CertificateConverter().getCertificate(certificateHolder)
            
            // Store the key and certificate in the keystore
            keyStore.setKeyEntry(
                "handycam",
                keyPair.private,
                password,
                arrayOf<Certificate>(certificate)
            )
            
            // Save the keystore to file
            FileOutputStream(file).use {
                keyStore.store(it, password)
            }
            
            Log.i(TAG, "Self-signed certificate created and stored successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create self-signed certificate", e)
            throw RuntimeException("Failed to create SSL certificate: ${e.message}", e)
        }
        
        return keyStore
    }

    private fun stopHttpsServer() {
        try {
            server?.stop(1000, 2000)
            server = null
            isRunning = false
            Log.i(TAG, "HTTPS Server stopped")
            broadcastServerState(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    private fun broadcastServerState(running: Boolean) {
        val intent = Intent("com.example.handycam.HTTPS_SERVER_STATE").apply {
                setPackage(packageName)
            putExtra("isRunning", running)
            putExtra("port", serverPort)
        }
        sendBroadcast(intent)
        
        // Also save to preferences
        getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("httpsServerRunning", running)
            .putInt("httpsServerPort", serverPort)
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHttpsServer()
    }
}
