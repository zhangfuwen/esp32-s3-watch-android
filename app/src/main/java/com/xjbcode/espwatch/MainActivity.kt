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
import androidx.recyclerview.widget.LinearLayoutManager
import com.xjbcode.espwatch.bluetooth.BluetoothLeService
import com.xjbcode.espwatch.data.DeviceInfo
import com.xjbcode.espwatch.databinding.ActivityMainBinding
import com.xjbcode.espwatch.service.ProxyService
import com.xjbcode.espwatch.ui.DeviceAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var bluetoothLeService: BluetoothLeService? = null
    private var proxyService: ProxyService? = null
    private var isBound = false

    private lateinit var deviceAdapter: DeviceAdapter
    private val discoveredDevices = mutableListOf<DeviceInfo>()

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
            Toast.makeText(this, "需要蓝牙权限", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothLeService.LocalBinder
            bluetoothLeService = binder.getService()
            isBound = true
            Log.d(TAG, "BluetoothLeService connected")
            
            // Set up callbacks
            setupServiceCallbacks()
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

        setupRecyclerView()
        setupUI()
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
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

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { device ->
            // On device click, fill the address and connect
            binding.etDeviceAddress.setText(device.address)
            connectToDevice()
        }
        
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupServiceCallbacks() {
        bluetoothLeService?.apply {
            onDeviceDiscovered = { device ->
                runOnUiThread {
                    // Update or add device
                    val existingIndex = discoveredDevices.indexOfFirst { it.address == device.address }
                    if (existingIndex >= 0) {
                        discoveredDevices[existingIndex] = device
                    } else {
                        discoveredDevices.add(device)
                    }
                    // Sort by signal strength
                    discoveredDevices.sortByDescending { it.rssi }
                    deviceAdapter.submitList(discoveredDevices.toList())
                    binding.tvDeviceCount.text = "发现 ${discoveredDevices.size} 个设备"
                }
            }
            
            onScanStateChanged = { isScanning ->
                runOnUiThread {
                    binding.btnScan.text = if (isScanning) "停止扫描" else "扫描设备"
                    binding.tvStatus.text = if (isScanning) "正在扫描..." else "就绪"
                }
            }
            
            onConnectionChanged = { isConnected ->
                runOnUiThread {
                    updateUI()
                }
            }
        }
    }

    private fun setupUI() {
        binding.btnScan.setOnClickListener {
            if (bluetoothLeService?.isScanning() == true) {
                stopScan()
            } else {
                scanForDevices()
            }
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
        Intent(this, BluetoothLeService::class.java).also { intent ->
            startService(intent)
        }
        Log.d(TAG, "Bluetooth initialized")
    }

    private fun scanForDevices() {
        // Clear previous results
        discoveredDevices.clear()
        deviceAdapter.submitList(emptyList())
        
        bluetoothLeService?.scanForDevices()
        binding.tvStatus.text = "正在扫描设备..."
        Toast.makeText(this, "开始扫描", Toast.LENGTH_SHORT).show()
    }

    private fun stopScan() {
        bluetoothLeService?.stopScan()
        binding.tvStatus.text = "扫描已停止"
    }

    private fun connectToDevice() {
        val deviceAddress = binding.etDeviceAddress.text.toString().trim()
        if (deviceAddress.isEmpty()) {
            Toast.makeText(this, "请输入设备地址", Toast.LENGTH_SHORT).show()
            return
        }

        bluetoothLeService?.connect(deviceAddress)
        binding.tvStatus.text = "正在连接 $deviceAddress..."
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
            
            binding.tvProxyStatus.text = "代理运行中 (端口: $proxyPort)"
            binding.btnStartProxy.isEnabled = false
            binding.btnStopProxy.isEnabled = true
            Toast.makeText(this@MainActivity, "代理已启动", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopProxyService() {
        Intent(this, ProxyService::class.java).also { intent ->
            stopService(intent)
        }
        
        binding.tvProxyStatus.text = "代理已停止"
        binding.btnStartProxy.isEnabled = true
        binding.btnStopProxy.isEnabled = false
        Toast.makeText(this, "代理已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        val isConnected = bluetoothLeService?.isConnected ?: false
        binding.btnConnect.isEnabled = !isConnected
        binding.tvConnectionStatus.text = if (isConnected) "已连接" else "未连接"
        binding.tvConnectionStatus.setTextColor(
            if (isConnected) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.darker_gray)
        )
    }
}
