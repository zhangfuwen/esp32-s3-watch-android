package com.xjbcode.espwatch.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents the current connection state
 */
sealed class ConnectionState : Parcelable {
    @Parcelize
    data object Disconnected : ConnectionState()

    @Parcelize
    data object Scanning : ConnectionState()

    @Parcelize
    data class Connecting(val deviceAddress: String) : ConnectionState()

    @Parcelize
    data class Connected(
        val deviceAddress: String,
        val deviceName: String?,
        val services: List<String> = emptyList()
    ) : ConnectionState()

    @Parcelize
    data class Error(val message: String, val canRetry: Boolean = true) : ConnectionState()

    val isConnected: Boolean
        get() = this is Connected

    val displayText: String
        get() = when (this) {
            is Disconnected -> "未连接"
            is Scanning -> "扫描中..."
            is Connecting -> "连接中..."
            is Connected -> "已连接"
            is Error -> "错误: $message"
        }
}
