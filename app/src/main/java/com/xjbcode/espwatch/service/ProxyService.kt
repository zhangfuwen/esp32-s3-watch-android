package com.xjbcode.espwatch.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xjbcode.espwatch.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class ProxyService : Service() {

    companion object {
        private const val TAG = "ProxyService"
        private const val NOTIFICATION_ID = 1235
        private const val CHANNEL_ID = "esp32_proxy_service"
        
        const val EXTRA_PROXY_PORT = "com.xjbcode.espwatch.PROXY_PORT"
        private const val DEFAULT_PROXY_PORT = 8080
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var proxyServerJob: Job? = null
    private var proxyPort = DEFAULT_PROXY_PORT
    private var isRunning = false
    
    // Statistics
    var requestCount = 0
        private set
    var errorCount = 0
        private set
    var lastRequest: ProxyRequestInfo? = null
        private set
    
    // Callback for UI updates
    var onRequestLogged: ((ProxyRequestInfo) -> Unit)? = null
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val httpChannel = Channel<ProxyRequest>(Channel.BUFFERED)

    data class ProxyRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val responseChannel: Channel<ProxyResponse>
    )

    data class ProxyResponse(
        val code: Int,
        val message: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    )
    
    // Info for logging/display
    data class ProxyRequestInfo(
        val timestamp: Long = System.currentTimeMillis(),
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val bodyPreview: String?,  // First 500 chars
        val responseCode: Int? = null,
        val responsePreview: String? = null,
        val error: String? = null
    ) {
        fun formatTime(): String {
            return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp))
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "ProxyService created")
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        proxyPort = intent?.getIntExtra(EXTRA_PROXY_PORT, DEFAULT_PROXY_PORT) ?: DEFAULT_PROXY_PORT
        
        Log.d(TAG, "Starting proxy service on port $proxyPort")
        
        startForeground(NOTIFICATION_ID, createNotification())
        startProxyServer()
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxyServer()
        serviceScope.cancel()
        httpChannel.close()
        okHttpClient.dispatcher.executorService.shutdown()
        Log.d(TAG, "ProxyService destroyed")
    }
    
    fun isRunning(): Boolean = isRunning

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ESP32-S3 Watch Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HTTP proxy service for ESP32-S3 Watch"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = android.content.Intent(this, com.xjbcode.espwatch.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ESP32-S3 Watch Proxy")
            .setContentText("Proxy running on port $proxyPort")
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startProxyServer() {
        if (isRunning) {
            Log.w(TAG, "Proxy server already running")
            return
        }

        isRunning = true
        
        proxyServerJob = serviceScope.launch {
            try {
                val serverSocket = ServerSocket(proxyPort, 50, InetAddress.getByName("0.0.0.0"))
                Log.d(TAG, "Proxy server started on port $proxyPort")

                while (isRunning && isActive) {
                    try {
                        val clientSocket = withContext(Dispatchers.IO) {
                            serverSocket.accept()
                        }
                        
                        Log.d(TAG, "Client connected: ${clientSocket.inetAddress}")
                        
                        // Handle client request in a new coroutine
                        launch {
                            handleClientRequest(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                    }
                }

                serverSocket.close()
                Log.d(TAG, "Proxy server stopped")
            } catch (e: IOException) {
                if (isRunning) {
                    Log.e(TAG, "Failed to start proxy server", e)
                }
            }
        }
    }

    private fun stopProxyServer() {
        isRunning = false
        proxyServerJob?.cancel()
        proxyServerJob = null
        Log.d(TAG, "Proxy server stop requested")
    }

    private suspend fun handleClientRequest(clientSocket: java.net.Socket) {
        val inputStream = clientSocket.getInputStream()
        val outputStream = clientSocket.getOutputStream()
        
        // Request info for logging (defined outside try for catch access)
        var requestInfo: ProxyRequestInfo? = null
        
        try {
            // Read HTTP request
            val requestBuilder = Request.Builder()
            val headers = mutableMapOf<String, String>()
            var method = "GET"
            var url = ""
            var body: ByteArray? = null
            
            // Parse request line
            val requestLine = inputStream.bufferedReader().readLine() ?: return
            Log.d(TAG, "Request: $requestLine")
            
            val parts = requestLine.split(" ")
            if (parts.size >= 2) {
                method = parts[0]
                url = parts[1]
            }
            
            // Parse headers
            while (true) {
                val line = inputStream.bufferedReader().readLine() ?: break
                if (line.isBlank()) break
                
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val headerName = line.substring(0, colonIndex).trim()
                    val headerValue = line.substring(colonIndex + 1).trim()
                    headers[headerName] = headerValue
                }
            }
            
            // Read body if present
            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                body = ByteArray(contentLength)
                inputStream.read(body)
            }
            
            // Build URL (add http:// if missing)
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            
            Log.d(TAG, "Proxying request: $method $url")
            
            // Create request info for logging
            requestInfo = ProxyRequestInfo(
                method = method,
                url = url,
                headers = headers.toMap(),
                bodyPreview = body?.let { String(it).take(500) }
            )
            lastRequest = requestInfo
            requestCount++
            
            // Notify UI
            withContext(Dispatchers.Main) {
                onRequestLogged?.invoke(requestInfo)
            }
            
            // Execute request through OkHttp
            val requestBody = if (body != null) okhttp3.RequestBody.create(body) else null
            val request = when (method.uppercase()) {
                "GET" -> requestBuilder.url(url).get()
                "POST" -> requestBuilder.url(url).post(requestBody!!)
                "PUT" -> requestBuilder.url(url).put(requestBody!!)
                "DELETE" -> requestBuilder.url(url).delete()
                "PATCH" -> requestBuilder.url(url).patch(requestBody!!)
                else -> requestBuilder.url(url).method(method, requestBody!!)
            }.apply {
                headers.forEach { (name, value) ->
                    // Skip hop-by-hop headers
                    if (!name.equals("Host", ignoreCase = true) &&
                        !name.equals("Connection", ignoreCase = true) &&
                        !name.equals("Proxy-Connection", ignoreCase = true)) {
                        addHeader(name, value)
                    }
                }
            }.build()
            
            val response = okHttpClient.newCall(request).execute()
            
            // Update request info with response
            val responseBody = response.body?.bytes()
            val updatedInfo = requestInfo.copy(
                responseCode = response.code,
                responsePreview = responseBody?.let { String(it).take(500) }
            )
            lastRequest = updatedInfo
            
            // Notify UI again with response
            withContext(Dispatchers.Main) {
                onRequestLogged?.invoke(updatedInfo)
            }
            
            // Send response back to client
            val statusLine = "HTTP/1.1 ${response.code} ${response.message}"
            outputStream.write(statusLine.toByteArray())
            outputStream.write("\r\n".toByteArray())
            
            // Write headers
            response.headers.forEach { (name, value) ->
                outputStream.write("$name: $value\r\n".toByteArray())
            }
            outputStream.write("\r\n".toByteArray())
            
            // Write body
            responseBody?.let { bodyBytes ->
                outputStream.write(bodyBytes)
            }
            
            outputStream.flush()
            
            Log.d(TAG, "Response sent: ${response.code}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request", e)
            errorCount++
            
            // Update request info with error
            requestInfo?.let {
                val errorInfo = it.copy(error = e.message)
                lastRequest = errorInfo
                withContext(Dispatchers.Main) {
                    onRequestLogged?.invoke(errorInfo)
                }
            }
            
            // Send error response
            try {
                val errorResponse = "HTTP/1.1 502 Bad Gateway\r\n\r\nProxy Error: ${e.message}"
                outputStream.write(errorResponse.toByteArray())
                outputStream.flush()
            } catch (e2: Exception) {
                Log.e(TAG, "Error sending error response", e2)
            }
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket", e)
            }
        }
    }

    // Removed - using OkHttp's built-in extension
}
