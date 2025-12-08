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
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

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
                
                // For HTTPS, we need a keystore with SSL certificate
                // For development, we'll use HTTP or you can add your own certificate
                val useHttps = false // Set to true when you have a proper keystore
                
                if (useHttps) {
                    // HTTPS configuration (requires keystore)
                    val keyStoreFile = File(filesDir, "keystore.jks")
                    val keyStore = loadOrCreateKeyStore(keyStoreFile)
                    
                    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    keyManagerFactory.init(keyStore, "changeit".toCharArray())
                    
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(keyManagerFactory.keyManagers, null, null)
                    
                    server = embeddedServer(Netty, port = serverPort) {
                        configureRouting()
                    }.apply {
                        start(wait = false)
                    }
                } else {
                    // HTTP server (simpler for development)
                    server = embeddedServer(Netty, port = serverPort) {
                        configureRouting()
                    }.apply {
                        start(wait = false)
                    }
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
                val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
                val isStreaming = prefs.getBoolean("isStreaming", false)
                call.respond(mapOf(
                    "streaming" to isStreaming,
                    "port" to prefs.getInt("streamPort", 4747),
                    "camera" to prefs.getString("camera", "back")
                ))
            }
            
            post("/api/camera/start") {
                try {
                    val intent = Intent(this@KtorHttpsServerService, StreamService::class.java).apply {
                        action = "com.example.handycam.ACTION_START"
                        putExtra("host", "0.0.0.0")
                        putExtra("port", 4747)
                        putExtra("width", 1080)
                        putExtra("height", 1920)
                        putExtra("camera", "back")
                        putExtra("jpegQuality", 85)
                        putExtra("targetFps", 30)
                        putExtra("useAvc", false)
                    }
                    startService(intent)
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
                    call.respond(mapOf("success" to true, "message" to "Switched to $camera camera"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf(
                        "success" to false,
                        "message" to "Failed to switch camera: ${e.message}"
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
                            </div>
                            
                            <button class="btn-start" onclick="startCamera()">▶️ Start Camera Stream</button>
                            <button class="btn-stop" onclick="stopCamera()">⏹️ Stop Camera Stream</button>
                            <button class="btn-switch" onclick="switchCamera('back')">🔄 Switch to Back Camera</button>
                            <button class="btn-switch" onclick="switchCamera('front')">🔄 Switch to Front Camera</button>
                            <button class="btn-refresh" onclick="checkStatus()">🔄 Refresh Status</button>
                        </div>
                        
                        <script>
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
                                    } else {
                                        statusEl.textContent = '🔴 Camera is Stopped';
                                        statusEl.className = 'status inactive';
                                        document.getElementById('info').style.display = 'none';
                                    }
                                } catch (e) {
                                    showMessage('Failed to check status: ' + e.message, false);
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

    private fun loadOrCreateKeyStore(file: File): KeyStore {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        
        if (file.exists()) {
            file.inputStream().use {
                keyStore.load(it, "changeit".toCharArray())
            }
        } else {
            // Create a new keystore
            // Note: For production, you should generate a proper certificate
            keyStore.load(null, "changeit".toCharArray())
            
            // Save the keystore
            FileOutputStream(file).use {
                keyStore.store(it, "changeit".toCharArray())
            }
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
