package com.xjbcode.espwatch.data

import android.bluetooth.BluetoothDevice
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a discovered BLE device
 */
@Parcelize
data class DeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
) : Parcelable {
    val displayName: String
        get() = name ?: "Unknown Device"

    val signalStrength: SignalStrength
        get() = when (rssi) {
            in -50..0 -> SignalStrength.EXCELLENT
            in -70..-51 -> SignalStrength.GOOD
            in -85..-71 -> SignalStrength.FAIR
            else -> SignalStrength.WEAK
        }

    enum class SignalStrength {
        EXCELLENT, GOOD, FAIR, WEAK
    }

    companion object {
        fun fromScanResult(device: BluetoothDevice, rssi: Int): DeviceInfo {
            return DeviceInfo(
                address = device.address,
                name = device.name,
                rssi = rssi
            )
        }
    }
}
