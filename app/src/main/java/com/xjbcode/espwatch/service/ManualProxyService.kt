package com.xjbcode.espwatch.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xjbcode.espwatch.R
import com.xjbcode.espwatch.data.PendingRequest
import com.xjbcode.espwatch.data.PendingResponse
import kotlinx.coroutines.*
import okhttp3.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class ManualProxyService : Service() {

    companion object {
        private const val TAG = "ManualProxyService"
        private const val NOTIFICATION_ID = 1236
        private const val CHANNEL_ID = "esp32_manual_proxy"
        
        const val EXTRA_PROXY_PORT = "com.xjbcode.espwatch.PROXY_PORT"
        private const val DEFAULT_PROXY_PORT = 8080
        
        // Actions for broadcast
        const val ACTION_NEW_REQUEST = "com.xjbcode.espwatch.NEW_REQUEST"
        const val ACTION_REQUEST_APPROVED = "com.xjbcode.espwatch.REQUEST_APPROVED"
        const val ACTION_REQUEST_REJECTED = "com.xjbcode.espwatch.REQUEST_REJECTED"
        const val EXTRA_REQUEST_ID = "request_id"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var proxyServerJob: Job? = null
    private var proxyPort = DEFAULT_PROXY_PORT
    private var isRunning = false
    
    // Store pending requests
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    // Store response continuations
    private val responseContinuations = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<PendingResponse>>()
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    inner class LocalBinder : Binder() {
        fun getService(): ManualProxyService = this@ManualProxyService
    }

    // Callbacks for UI
    var onNewRequest: ((PendingRequest) -> Unit)? = null
    var onRequestCompleted: ((String, Boolean) -> Unit)? = null  // requestId, success

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "ManualProxyService created")
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        proxyPort = intent?.getIntExtra(EXTRA_PROXY_PORT, DEFAULT_PROXY_PORT) ?: DEFAULT_PROXY_PORT
        
        Log.d(TAG, "Starting manual proxy service on port $proxyPort")
        
        startForeground(NOTIFICATION_ID, createNotification())
        startProxyServer()
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxyServer()
        serviceScope.cancel()
        Log.d(TAG, "ManualProxyService destroyed")
    }

    fun isRunning(): Boolean = isRunning

    fun getPendingRequests(): List<PendingRequest> = pendingRequests.values.sortedByDescending { it.timestamp }

    fun getPendingRequest(id: String): PendingRequest? = pendingRequests[id]

    /**
     * Approve and forward the request to the real server
     */
    fun approveRequest(requestId: String, modifiedUrl: String? = null, modifiedHeaders: Map<String, String>? = null, modifiedBody: String? = null) {
        val request = pendingRequests[requestId] ?: return
        
        serviceScope.launch {
            try {
                // Use modified values or original
                val url = modifiedUrl ?: request.url
                val headers = modifiedHeaders ?: request.headers
                val body = modifiedBody ?: request.body

                // Build OkHttp request
                val requestBuilder = Request.Builder().url(url)
                
                headers.forEach { (name, value) ->
                    if (!name.equals("Host", ignoreCase = true) &&
                        !name.equals("Connection", ignoreCase = true)) {
                        requestBuilder.addHeader(name, value)
                    }
                }

                // Add body for POST/PUT/PATCH
                when (request.method.uppercase()) {
                    "GET" -> requestBuilder.get()
                    "POST" -> {
                        val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
                        requestBuilder.post(requestBody ?: "".toRequestBody())
                    }
                    "PUT" -> {
                        val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
                        requestBuilder.put(requestBody ?: "".toRequestBody())
                    }
                    "DELETE" -> requestBuilder.delete()
                    else -> requestBuilder.method(request.method, body?.toRequestBody())
                }

                // Execute request
                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                
                val responseBody = response.body?.string()
                val responseHeaders = response.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                
                val pendingResponse = PendingResponse(
                    requestId = requestId,
                    statusCode = response.code,
                    headers = responseHeaders,
                    body = responseBody
                )
                
                // Complete the continuation
                responseContinuations[requestId]?.complete(pendingResponse)
                
                onRequestCompleted?.invoke(requestId, true)
                Log.d(TAG, "Request $requestId approved and forwarded, status: ${response.code}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward request $requestId", e)
                responseContinuations[requestId]?.complete(
                    PendingResponse(requestId, 502, emptyMap(), "Proxy Error: ${e.message}")
                )
                onRequestCompleted?.invoke(requestId, false)
            } finally {
                pendingRequests.remove(requestId)
                responseContinuations.remove(requestId)
            }
        }
    }

    /**
     * Reject the request and return error to watch
     */
    fun rejectRequest(requestId: String, reason: String = "Request rejected by user") {
        responseContinuations[requestId]?.complete(
            PendingResponse(requestId, 403, mapOf("Content-Type" to "text/plain"), reason)
        )
        pendingRequests.remove(requestId)
        responseContinuations.remove(requestId)
        onRequestCompleted?.invoke(requestId, false)
        Log.d(TAG, "Request $requestId rejected: $reason")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "手动 HTTP 代理",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "等待手动批准的 HTTP 代理服务"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.xjbcode.espwatch.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手动 HTTP 代理")
            .setContentText("等待请求... (${pendingRequests.size} 个待处理)")
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startProxyServer() {
        if (isRunning) return
        
        isRunning = true
        
        proxyServerJob = serviceScope.launch {
            try {
                val serverSocket = ServerSocket(proxyPort, 50, InetAddress.getByName("0.0.0.0"))
                Log.d(TAG, "Manual proxy server started on port $proxyPort")

                while (isRunning && isActive) {
                    try {
                        val clientSocket = withContext(Dispatchers.IO) {
                            serverSocket.accept()
                        }
                        
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
                Log.d(TAG, "Manual proxy server stopped")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start proxy server", e)
            }
        }
    }

    private fun stopProxyServer() {
        isRunning = false
        proxyServerJob?.cancel()
        proxyServerJob = null
    }

    private suspend fun handleClientRequest(clientSocket: java.net.Socket) {
        val clientAddress = clientSocket.inetAddress.hostAddress
        
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val outputStream = clientSocket.getOutputStream()
            
            // Read request line
            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "Request: $requestLine from $clientAddress")
            
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            
            val method = parts[0]
            var url = parts[1]
            
            // Parse headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val name = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[name] = value
                }
            }
            
            // Read body if present
            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val bodyBytes = CharArray(contentLength)
                reader.read(bodyBytes)
                String(bodyBytes)
            } else null
            
            // Fix URL if needed
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                val host = headers["Host"] ?: "localhost"
                url = "http://$host$url"
            }
            
            // Create pending request
            val pendingRequest = PendingRequest(
                method = method,
                url = url,
                headers = headers,
                body = body,
                clientAddress = clientAddress
            )
            
            // Store request
            pendingRequests[pendingRequest.id] = pendingRequest
            
            // Create continuation for async response
            val responseDeferred = CompletableDeferred<PendingResponse>()
            responseContinuations[pendingRequest.id] = responseDeferred
            
            // Notify UI
            withContext(Dispatchers.Main) {
                onNewRequest?.invoke(pendingRequest)
            }
            
            // Send broadcast
            sendBroadcast(Intent(ACTION_NEW_REQUEST).apply {
                putExtra(EXTRA_REQUEST_ID, pendingRequest.id)
            })
            
            // Wait for user approval/rejection (with timeout)
            val response = withTimeoutOrNull(300000) {  // 5 minute timeout
                responseDeferred.await()
            } ?: PendingResponse(
                pendingRequest.id,
                408,
                emptyMap(),
                "Request timeout - no user response within 5 minutes"
            )
            
            // Send HTTP response back to client
            val statusLine = "HTTP/1.1 ${response.statusCode} ${getStatusText(response.statusCode)}"
            outputStream.write("$statusLine\r\n".toByteArray())
            
            response.headers.forEach { (name, value) ->
                outputStream.write("$name: $value\r\n".toByteArray())
            }
            
            response.body?.let {
                outputStream.write("Content-Length: ${it.toByteArray().size}\r\n".toByteArray())
            }
            
            outputStream.write("\r\n".toByteArray())
            response.body?.let {
                outputStream.write(it.toByteArray())
            }
            
            outputStream.flush()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket", e)
            }
        }
    }

    private fun getStatusText(code: Int): String {
        return when (code) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            408 -> "Request Timeout"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "Unknown"
        }
    }
}

private fun String.toRequestBody(): RequestBody {
    return this.toRequestBody("application/octet-stream".toMediaTypeOrNull())
}
