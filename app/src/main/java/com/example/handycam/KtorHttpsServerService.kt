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
        val port: Int,
        val uptime: Long
    )
    
    @Serializable
    data class ServerInfo(
        val name: String,
        val version: String,
        val protocol: String
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
                startTime = System.currentTimeMillis()
                
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
                call.respond(mapOf(
                    "streaming" to (settingsManager.isStreaming.value ?: false),
                    "port" to (settingsManager.port.value ?: 4747),
                    "camera" to (settingsManager.camera.value ?: "back"),
                    "width" to (settingsManager.width.value ?: 1080),
                    "height" to (settingsManager.height.value ?: 1920),
                    "fps" to (settingsManager.fps.value ?: 30),
                    "jpegQuality" to (settingsManager.jpegQuality.value ?: 85),
                    "useAvc" to (settingsManager.useAvc.value ?: false),
                    "torchEnabled" to (settingsManager.torchEnabled.value ?: false),
                    "autoFocus" to (settingsManager.autoFocus.value ?: true),
                    "exposure" to (settingsManager.exposure.value ?: 0)
                ))
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
                        putExtra("useAvc", settingsManager.useAvc.value ?: false)
                    }
                    startService(intent)
                    settingsManager.setStreaming(true)
                    call.respond(mapOf("success" to true, "message" to "Camera streaming started"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "success" to false,
                        "message" to "Failed to start camera: ${e.message}"
                    ))
                }
            }
            
            post("/api/camera/stop") {
                try {
                    val intent = Intent(this@KtorHttpsServerService, StreamService::class.java).apply {
                        action = "com.example.handycam.ACTION_STOP"
                    }
                    startService(intent)
                    settingsManager.setStreaming(false)
                    call.respond(mapOf("success" to true, "message" to "Camera streaming stopped"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "success" to false,
                        "message" to "Failed to stop camera: ${e.message}"
                    ))
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
                    call.respond(mapOf("success" to true, "message" to "Switched to $camera camera"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "success" to false,
                        "message" to "Failed to switch camera: ${e.message}"
                    ))
                }
            }

            // New endpoints for all controls
            post("/api/settings/fps") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val fps = params["fps"] ?: 30
                    settingsManager.setFps(fps)
                    call.respond(mapOf("success" to true, "message" to "FPS updated to $fps"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "message" to "Invalid FPS value: ${e.message}"
                    ))
                }
            }

            post("/api/settings/resolution") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val width = params["width"] ?: 1080
                    val height = params["height"] ?: 1920
                    settingsManager.setWidth(width)
                    settingsManager.setHeight(height)
                    call.respond(mapOf("success" to true, "message" to "Resolution updated to ${width}x${height}"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "message" to "Invalid resolution: ${e.message}"
                    ))
                }
            }

            post("/api/settings/jpeg-quality") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val quality = params["quality"] ?: 85
                    settingsManager.setJpegQuality(quality)
                    call.respond(mapOf("success" to true, "message" to "JPEG quality updated to $quality"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "message" to "Invalid JPEG quality: ${e.message}"
                    ))
                }
            }

            post("/api/settings/torch") {
                try {
                    val params = call.receive<Map<String, Boolean>>()
                    val enabled = params["enabled"] ?: false
                    settingsManager.setTorchEnabled(enabled)
                    call.respond(mapOf("success" to true, "message" to "Torch ${if (enabled) "enabled" else "disabled"}"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "message" to "Failed to set torch: ${e.message}"
                    ))
                }
            }

            post("/api/settings/auto-focus") {
                try {
                    val params = call.receive<Map<String, Boolean>>()
                    val enabled = params["enabled"] ?: true
                    settingsManager.setAutoFocus(enabled)
                    call.respond(mapOf("success" to true, "message" to "Auto focus ${if (enabled) "enabled" else "disabled"}"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "message" to "Failed to set auto focus: ${e.message}"
                    ))
                }
            }

            post("/api/settings/exposure") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val exposure = params["exposure"] ?: 0
                    settingsManager.setExposure(exposure)
                    call.respond(mapOf("success" to true, "message" to "Exposure updated to $exposure"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "message" to "Invalid exposure value: ${e.message}"
                    ))
                }
            }

            post("/api/settings/port") {
                try {
                    val params = call.receive<Map<String, Int>>()
                    val port = params["port"] ?: 4747
                    settingsManager.setPort(port)
                    call.respond(mapOf("success" to true, "message" to "Port updated to $port"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf(
                        "success" to false,
                        "message" to "Invalid port: ${e.message}"
                    ))
                }
            }

            get("/camera") {
                call.respondText(
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>HandyCam Camera Control</title>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>
                            body { 
                                font-family: Arial, sans-serif; 
                                margin: 0;
                                padding: 20px;
                                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                min-height: 100vh;
                            }
                            .container { 
                                max-width: 600px;
                                margin: 0 auto;
                                background: white; 
                                padding: 30px; 
                                border-radius: 12px; 
                                box-shadow: 0 10px 40px rgba(0,0,0,0.2);
                            }
                            h1 { 
                                color: #333;
                                margin-top: 0;
                                text-align: center;
                            }
                            .status {
                                padding: 15px;
                                border-radius: 8px;
                                margin: 20px 0;
                                text-align: center;
                                font-weight: bold;
                            }
                            .status.active { background: #d4edda; color: #155724; }
                            .status.inactive { background: #f8d7da; color: #721c24; }
                            button {
                                width: 100%;
                                padding: 15px;
                                margin: 10px 0;
                                font-size: 16px;
                                border: none;
                                border-radius: 8px;
                                cursor: pointer;
                                font-weight: bold;
                                transition: all 0.3s;
                            }
                            button:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.2); }
                            button:active { transform: translateY(0); }
                            .btn-start { background: #28a745; color: white; }
                            .btn-stop { background: #dc3545; color: white; }
                            .btn-switch { background: #007bff; color: white; }
                            .btn-refresh { background: #6c757d; color: white; }
                            .info {
                                background: #f8f9fa;
                                padding: 15px;
                                border-radius: 8px;
                                margin: 20px 0;
                            }
                            .info-item {
                                display: flex;
                                justify-content: space-between;
                                margin: 8px 0;
                                padding: 8px 0;
                                border-bottom: 1px solid #dee2e6;
                            }
                            .info-item:last-child { border-bottom: none; }
                            .label { font-weight: bold; color: #495057; }
                            .value { color: #007bff; }
                            .message {
                                padding: 12px;
                                border-radius: 8px;
                                margin: 10px 0;
                                display: none;
                            }
                            .message.success { background: #d4edda; color: #155724; display: block; }
                            .message.error { background: #f8d7da; color: #721c24; display: block; }
                            .control-group {
                                background: #f8f9fa;
                                padding: 15px;
                                border-radius: 8px;
                                margin: 15px 0;
                            }
                            .control-label {
                                font-weight: bold;
                                color: #495057;
                                margin-bottom: 8px;
                                display: flex;
                                justify-content: space-between;
                            }
                            input[type="range"] {
                                width: 100%;
                                height: 6px;
                                border-radius: 3px;
                                cursor: pointer;
                            }
                            input[type="number"] {
                                width: 100%;
                                padding: 8px;
                                border: 1px solid #dee2e6;
                                border-radius: 4px;
                                font-size: 14px;
                            }
                            .toggle-btn {
                                width: 100%;
                                padding: 10px;
                                margin: 8px 0;
                                border: 2px solid #007bff;
                                background: white;
                                color: #007bff;
                                border-radius: 6px;
                                cursor: pointer;
                                font-weight: bold;
                                transition: all 0.3s;
                            }
                            .toggle-btn.active {
                                background: #007bff;
                                color: white;
                            }
                        </style>
                    </head>
                    <body>
                        <div class="container">
                            <h1>📹 Camera Control</h1>
                            
                            <div id="status" class="status inactive">Status: Checking...</div>
                            
                            <div id="message" class="message"></div>
                            
                            <div class="info" id="info" style="display:none;">
                                <div class="info-item">
                                    <span class="label">Stream Port:</span>
                                    <span class="value" id="port">-</span>
                                </div>
                                <div class="info-item">
                                    <span class="label">Current Camera:</span>
                                    <span class="value" id="camera">-</span>
                                </div>
                                <div class="info-item">
                                    <span class="label">Resolution:</span>
                                    <span class="value" id="resolution">-</span>
                                </div>
                                <div class="info-item">
                                    <span class="label">FPS:</span>
                                    <span class="value" id="fps">-</span>
                                </div>
                                <div class="info-item">
                                    <span class="label">JPEG Quality:</span>
                                    <span class="value" id="jpegQuality">-</span>
                                </div>
                            </div>
                            
                            <button class="btn-start" onclick="startCamera()">▶️ Start Camera Stream</button>
                            <button class="btn-stop" onclick="stopCamera()">⏹️ Stop Camera Stream</button>
                            
                            <div class="control-group">
                                <div class="control-label">Camera Selection</div>
                                <button class="toggle-btn" onclick="switchCamera('back')">🔄 Back Camera</button>
                                <button class="toggle-btn" onclick="switchCamera('front')">🔄 Front Camera</button>
                            </div>
                            
                            <div class="control-group">
                                <div class="control-label">
                                    <span>FPS: <span id="fpsValue">30</span></span>
                                </div>
                                <input type="range" id="fpsSlider" min="15" max="60" value="30" onchange="setFps(this.value)">
                            </div>
                            
                            <div class="control-group">
                                <div class="control-label">
                                    <span>JPEG Quality: <span id="jpegValue">85</span>%</span>
                                </div>
                                <input type="range" id="jpegSlider" min="50" max="100" value="85" onchange="setJpegQuality(this.value)">
                            </div>
                            
                            <div class="control-group">
                                <div class="control-label">
                                    <span>Exposure: <span id="exposureValue">0</span></span>
                                </div>
                                <input type="range" id="exposureSlider" min="-50" max="50" value="0" onchange="setExposure(this.value)">
                            </div>
                            
                            <div class="control-group">
                                <button class="toggle-btn" id="torchBtn" onclick="toggleTorch()">💡 Torch: OFF</button>
                                <button class="toggle-btn" id="autoFocusBtn" onclick="toggleAutoFocus()">🎯 Auto Focus: ON</button>
                            </div>
                            
                            <button class="btn-refresh" onclick="checkStatus()">🔄 Refresh Status</button>
                        </div>
                        
                        <script>
                            let torchEnabled = false;
                            let autoFocusEnabled = true;
                            
                            function showMessage(text, isSuccess) {
                                const msg = document.getElementById('message');
                                msg.textContent = text;
                                msg.className = 'message ' + (isSuccess ? 'success' : 'error');
                                setTimeout(() => msg.className = 'message', 3000);
                            }
                            
                            async function checkStatus() {
                                try {
                                    const response = await fetch('/api/camera/status');
                                    const data = await response.json();
                                    
                                    const statusEl = document.getElementById('status');
                                    if (data.streaming) {
                                        statusEl.textContent = '🟢 Camera is Streaming';
                                        statusEl.className = 'status active';
                                        document.getElementById('info').style.display = 'block';
                                        document.getElementById('port').textContent = data.port;
                                        document.getElementById('camera').textContent = data.camera;
                                        document.getElementById('resolution').textContent = data.width + 'x' + data.height;
                                        document.getElementById('fps').textContent = data.fps;
                                        document.getElementById('jpegQuality').textContent = data.jpegQuality + '%';
                                        
                                        // Update sliders
                                        document.getElementById('fpsSlider').value = data.fps;
                                        document.getElementById('fpsValue').textContent = data.fps;
                                        document.getElementById('jpegSlider').value = data.jpegQuality;
                                        document.getElementById('jpegValue').textContent = data.jpegQuality;
                                        document.getElementById('exposureSlider').value = data.exposure;
                                        document.getElementById('exposureValue').textContent = data.exposure;
                                        
                                        // Update toggle buttons
                                        torchEnabled = data.torchEnabled || false;
                                        autoFocusEnabled = data.autoFocus !== false;
                                        updateToggleButtons();
                                    } else {
                                        statusEl.textContent = '🔴 Camera is Stopped';
                                        statusEl.className = 'status inactive';
                                        document.getElementById('info').style.display = 'none';
                                    }
                                } catch (e) {
                                    showMessage('Failed to check status: ' + e.message, false);
                                }
                            }
                            
                            function updateToggleButtons() {
                                const torchBtn = document.getElementById('torchBtn');
                                const autoFocusBtn = document.getElementById('autoFocusBtn');
                                
                                if (torchEnabled) {
                                    torchBtn.textContent = '💡 Torch: ON';
                                    torchBtn.classList.add('active');
                                } else {
                                    torchBtn.textContent = '💡 Torch: OFF';
                                    torchBtn.classList.remove('active');
                                }
                                
                                if (autoFocusEnabled) {
                                    autoFocusBtn.textContent = '🎯 Auto Focus: ON';
                                    autoFocusBtn.classList.add('active');
                                } else {
                                    autoFocusBtn.textContent = '🎯 Auto Focus: OFF';
                                    autoFocusBtn.classList.remove('active');
                                }
                            }
                            
                            async function startCamera() {
                                try {
                                    const response = await fetch('/api/camera/start', { method: 'POST' });
                                    const data = await response.json();
                                    showMessage(data.message, data.success);
                                    if (data.success) setTimeout(checkStatus, 1000);
                                } catch (e) {
                                    showMessage('Failed to start camera: ' + e.message, false);
                                }
                            }
                            
                            async function stopCamera() {
                                try {
                                    const response = await fetch('/api/camera/stop', { method: 'POST' });
                                    const data = await response.json();
                                    showMessage(data.message, data.success);
                                    if (data.success) setTimeout(checkStatus, 1000);
                                } catch (e) {
                                    showMessage('Failed to stop camera: ' + e.message, false);
                                }
                            }
                            
                            async function switchCamera(camera) {
                                try {
                                    const response = await fetch('/api/camera/switch', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({ camera })
                                    });
                                    const data = await response.json();
                                    showMessage(data.message, data.success);
                                    if (data.success) setTimeout(checkStatus, 500);
                                } catch (e) {
                                    showMessage('Failed to switch camera: ' + e.message, false);
                                }
                            }
                            
                            async function setFps(fps) {
                                document.getElementById('fpsValue').textContent = fps;
                                try {
                                    const response = await fetch('/api/settings/fps', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({ fps: parseInt(fps) })
                                    });
                                    const data = await response.json();
                                    if (!data.success) showMessage(data.message, false);
                                } catch (e) {
                                    showMessage('Failed to set FPS: ' + e.message, false);
                                }
                            }
                            
                            async function setJpegQuality(quality) {
                                document.getElementById('jpegValue').textContent = quality;
                                try {
                                    const response = await fetch('/api/settings/jpeg-quality', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({ quality: parseInt(quality) })
                                    });
                                    const data = await response.json();
                                    if (!data.success) showMessage(data.message, false);
                                } catch (e) {
                                    showMessage('Failed to set JPEG quality: ' + e.message, false);
                                }
                            }
                            
                            async function setExposure(exposure) {
                                document.getElementById('exposureValue').textContent = exposure;
                                try {
                                    const response = await fetch('/api/settings/exposure', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({ exposure: parseInt(exposure) })
                                    });
                                    const data = await response.json();
                                    if (!data.success) showMessage(data.message, false);
                                } catch (e) {
                                    showMessage('Failed to set exposure: ' + e.message, false);
                                }
                            }
                            
                            async function toggleTorch() {
                                torchEnabled = !torchEnabled;
                                try {
                                    const response = await fetch('/api/settings/torch', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({ enabled: torchEnabled })
                                    });
                                    const data = await response.json();
                                    if (data.success) {
                                        updateToggleButtons();
                                    } else {
                                        torchEnabled = !torchEnabled;
                                        updateToggleButtons();
                                        showMessage(data.message, false);
                                    }
                                } catch (e) {
                                    torchEnabled = !torchEnabled;
                                    updateToggleButtons();
                                    showMessage('Failed to toggle torch: ' + e.message, false);
                                }
                            }
                            
                            async function toggleAutoFocus() {
                                autoFocusEnabled = !autoFocusEnabled;
                                try {
                                    const response = await fetch('/api/settings/auto-focus', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/json' },
                                        body: JSON.stringify({ enabled: autoFocusEnabled })
                                    });
                                    const data = await response.json();
                                    if (data.success) {
                                        updateToggleButtons();
                                    } else {
                                        autoFocusEnabled = !autoFocusEnabled;
                                        updateToggleButtons();
                                        showMessage(data.message, false);
                                    }
                                } catch (e) {
                                    autoFocusEnabled = !autoFocusEnabled;
                                    updateToggleButtons();
                                    showMessage('Failed to toggle auto focus: ' + e.message, false);
                                }
                            }
                            
                            // Check status on load
                            checkStatus();
                            // Auto-refresh every 5 seconds
                            setInterval(checkStatus, 5000);
                        </script>
                    </body>
                    </html>
                    """.trimIndent(),
                    ContentType.Text.Html
                )
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
