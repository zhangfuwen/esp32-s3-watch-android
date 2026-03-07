package com.xjbcode.espwatch

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xjbcode.espwatch.bluetooth.BluetoothLeService
import com.xjbcode.espwatch.databinding.ActivityMainBinding
import com.xjbcode.espwatch.service.ProxyService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var bluetoothLeService: BluetoothLeService? = null
    private var proxyService: ProxyService? = null
    private var isBound = false

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            initializeBluetooth()
        } else {
            Toast.makeText(this, "Permissions required for Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothLeService.LocalBinder
            bluetoothLeService = binder.getService()
            isBound = true
            Log.d(TAG, "BluetoothLeService connected")
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothLeService = null
            isBound = false
            Log.d(TAG, "BluetoothLeService disconnected")
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Bind to Bluetooth service
        Intent(this, BluetoothLeService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun setupUI() {
        binding.btnScan.setOnClickListener {
            scanForDevices()
        }

        binding.btnConnect.setOnClickListener {
            connectToDevice()
        }

        binding.btnStartProxy.setOnClickListener {
            startProxyService()
        }

        binding.btnStopProxy.setOnClickListener {
            stopProxyService()
        }
    }

    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initializeBluetooth()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun initializeBluetooth() {
        // Start Bluetooth service
        Intent(this, BluetoothLeService::class.java).also { intent ->
            startService(intent)
        }
        Log.d(TAG, "Bluetooth initialized")
    }

    private fun scanForDevices() {
        bluetoothLeService?.scanForDevices()
        binding.tvStatus.text = "Scanning for devices..."
        Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
    }

    private fun connectToDevice() {
        val deviceAddress = binding.etDeviceAddress.text.toString().trim()
        if (deviceAddress.isEmpty()) {
            Toast.makeText(this, "Please enter device address", Toast.LENGTH_SHORT).show()
            return
        }

        bluetoothLeService?.connect(deviceAddress)
        binding.tvStatus.text = "Connecting to $deviceAddress..."
    }

    private fun startProxyService() {
        val proxyPort = binding.etProxyPort.text.toString().toIntOrNull() ?: 8080
        
        lifecycleScope.launch {
            val intent = Intent(this@MainActivity, ProxyService::class.java).apply {
                putExtra(ProxyService.EXTRA_PROXY_PORT, proxyPort)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            binding.tvProxyStatus.text = "Proxy running on port $proxyPort"
            binding.btnStartProxy.isEnabled = false
            binding.btnStopProxy.isEnabled = true
            Toast.makeText(this@MainActivity, "Proxy started on port $proxyPort", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopProxyService() {
        Intent(this, ProxyService::class.java).also { intent ->
            stopService(intent)
        }
        
        binding.tvProxyStatus.text = "Proxy stopped"
        binding.btnStartProxy.isEnabled = true
        binding.btnStopProxy.isEnabled = false
        Toast.makeText(this, "Proxy stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        val isConnected = bluetoothLeService?.isConnected ?: false
        binding.btnConnect.isEnabled = !isConnected
        binding.tvConnectionStatus.text = if (isConnected) "Connected" else "Disconnected"
    }
}
