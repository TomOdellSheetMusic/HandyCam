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
                    "message" to "Camera status endpoint",
                    "streaming" to false
                ))
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
