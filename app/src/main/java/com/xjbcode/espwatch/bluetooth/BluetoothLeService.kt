package com.xjbcode.espwatch.bluetooth

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xjbcode.espwatch.R
import kotlinx.coroutines.*
import java.util.*

class BluetoothLeService : Service() {

    companion object {
        private const val TAG = "BluetoothLeService"
        private const val NOTIFICATION_ID = 1234
        private const val CHANNEL_ID = "esp32_watch_service"
        
        // ESP32-S3 Watch Service UUID (you'll need to define this in your watch firmware)
        val WATCH_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val WATCH_TX_CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val WATCH_RX_CHARACTERISTIC_UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
    }

    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    
    private val scanJob = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isScanning = false
    
    var isConnected = false
        private set
    
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    // Callback for device connection
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onDataReceived: ((ByteArray) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothLeService = this@BluetoothLeService
    }

    override fun onCreate() {
        super.onCreate()
        initializeBluetooth()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        scanJob.cancel()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported")
            return
        }
        
        Log.d(TAG, "Bluetooth initialized")
    }

    @SuppressLint("MissingPermission")
    fun scanForDevices() {
        if (isScanning) {
            stopScan()
        }

        isScanning = true
        Log.d(TAG, "Starting BLE scan")

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                Log.d(TAG, "Found device: ${device.name} (${device.address})")
                
                // Look for ESP32 devices
                if (device.name?.contains("ESP32", ignoreCase = true) == true) {
                    Log.d(TAG, "Found ESP32 device: ${device.address}")
                    // You can broadcast this or use a callback
                    stopScan()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error: $errorCode")
                isScanning = false
            }
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(WATCH_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothAdapter?.bluetoothLeScanner?.startScan(
            listOf(scanFilter),
            scanSettings,
            scanCallback
        )

        // Auto-stop scan after 30 seconds
        scanJob.launch {
            delay(30000)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (isScanning) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(object : ScanCallback() {})
            isScanning = false
            Log.d(TAG, "BLE scan stopped")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            Log.e(TAG, "Device not found: $address")
            return
        }

        connectedDevice = device
        Log.d(TAG, "Connecting to ${device.name} ($address)")

        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        closeGatt()
    }

    private fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDevice = null
        isConnected = false
        writeCharacteristic = null
        readCharacteristic = null
        onConnectionChanged?.invoke(false)
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "GATT closed")
    }

    @SuppressLint("MissingPermission")
    fun sendData(data: ByteArray) {
        val characteristic = writeCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "Write characteristic not available")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            characteristic.value = data
            bluetoothGatt?.writeCharacteristic(characteristic)
        }
        
        Log.d(TAG, "Data sent: ${data.size} bytes")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    isConnected = true
                    onConnectionChanged?.invoke(true)
                    
                    // Start service discovery
                    gatt.discoverServices()
                    
                    // Start foreground service
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    isConnected = false
                    onConnectionChanged?.invoke(false)
                    closeGatt()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                
                // Find watch service and characteristics
                val service = gatt.getService(WATCH_SERVICE_UUID)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(WATCH_TX_CHARACTERISTIC_UUID)
                    readCharacteristic = service.getCharacteristic(WATCH_RX_CHARACTERISTIC_UUID)
                    
                    // Enable notifications if read characteristic exists
                    readCharacteristic?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.setCharacteristicNotification(it, true)
                        } else {
                            gatt.setCharacteristicNotification(it, true)
                        }
                    }
                    
                    Log.d(TAG, "Watch service found")
                } else {
                    Log.w(TAG, "Watch service not found - using generic communication")
                }
            } else {
                Log.e(TAG, "Service discovery failed: $status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic read: ${value.size} bytes")
                onDataReceived?.invoke(value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "Characteristic changed: ${value.size} bytes")
            onDataReceived?.invoke(value)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ESP32-S3 Watch Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bluetooth connection to ESP32-S3 Watch"
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
            .setContentTitle("ESP32-S3 Watch")
            .setContentText("Connected to watch")
            .setSmallIcon(R.drawable.ic_watch)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
