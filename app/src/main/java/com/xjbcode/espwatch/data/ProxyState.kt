package com.xjbcode.espwatch.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents the current proxy service state
 */
@Parcelize
data class ProxyState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val requestCount: Int = 0,
    val errorCount: Int = 0,
    val lastError: String? = null,
    val connectedClients: Int = 0
) : Parcelable {
    val displayText: String
        get() = when {
            isRunning -> "代理运行中 (端口: $port)"
            else -> "代理已停止"
        }

    val statusColor: String
        get() = when {
            isRunning -> "green"
            else -> "gray"
        }
}
