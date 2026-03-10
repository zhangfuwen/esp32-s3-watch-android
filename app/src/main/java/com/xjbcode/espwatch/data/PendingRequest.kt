package com.xjbcode.espwatch.data

import java.util.UUID

/**
 * Represents an HTTP request from the watch waiting for manual approval
 */
data class PendingRequest(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,  // Base64 encoded or plain text
    val clientAddress: String
) {
    val summary: String
        get() = "$method $url"

    fun formatForDisplay(): String {
        val sb = StringBuilder()
        sb.appendLine("请求 ID: $id")
        sb.appendLine("时间: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}")
        sb.appendLine("方法: $method")
        sb.appendLine("URL: $url")
        sb.appendLine("客户端: $clientAddress")
        sb.appendLine()
        sb.appendLine("Headers:")
        headers.forEach { (k, v) ->
            sb.appendLine("  $k: $v")
        }
        body?.let {
            sb.appendLine()
            sb.appendLine("Body:")
            sb.appendLine(it.take(500))  // Limit body display
        }
        return sb.toString()
    }
}

/**
 * Response to be sent back to the watch
 */
data class PendingResponse(
    val requestId: String,
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String?  // Base64 encoded
)
