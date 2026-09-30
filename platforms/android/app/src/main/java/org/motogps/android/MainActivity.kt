package org.motogps.android

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.motogps.android.ble.MotoBleConnection

private data class FoundDevice(val device: BluetoothDevice, val address: String, val rssi: Int)

class MainActivity : ComponentActivity() {
    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val connection by lazy { MotoBleConnection(this) { status = it } }
    private val main = Handler(Looper.getMainLooper())
    private val discovered = mutableStateListOf<FoundDevice>()
    private var status by mutableStateOf("尚未扫描")
    private var scanning = false
    private val stopScanTask = Runnable { stopScan() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning) return@post
                val address = try { result.device.address } catch (_: SecurityException) { return@post }
                val index = discovered.indexOfFirst { it.address == address }
                val found = FoundDevice(result.device, address, result.rssi)
                if (index >= 0) discovered[index] = found else discovered.add(found)
                status = "发现 ${discovered.size} 台圆屏，请选择要连接的设备"
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                stopScan()
                status = "扫描失败：错误码 $errorCode"
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startScan() else status = "需要授权附近设备扫描，才能查找圆屏"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("MOTO GPS", style = MaterialTheme.typography.headlineMedium)
                    Text("Android 开发版：可扫描、选择圆屏并尝试完成 BLE 协议握手。")
                    Text(status)
                    Button(onClick = ::requestScan) { Text("扫描圆屏（10 秒）") }
                    discovered.forEach { found ->
                        Button(onClick = {
                            stopScan()
                            connection.connect(found.device)
                        }) {
                            Text("连接 ${found.address} · ${found.rssi} dBm")
                        }
                    }
                }
            }
        }
    }

    private fun requestScan() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startScan() {
        val adapter = bluetoothManager.adapter
        if (adapter?.isEnabled != true) { status = "请先开启手机蓝牙"; return }
        stopScan()
        val scanner = adapter.bluetoothLeScanner ?: run {
            status = "此手机无法启动 BLE 扫描"
            return
        }
        discovered.clear()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MotoBleConnection.SERVICE)).build()
        try {
            scanner.startScan(listOf(filter), ScanSettings.Builder().build(), callback)
            scanning = true
            status = "正在查找 MOTO GPS 圆屏…"
            main.postDelayed(stopScanTask, 10_000)
        } catch (_: SecurityException) {
            status = "蓝牙扫描权限不足"
        }
    }

    private fun stopScan() {
        main.removeCallbacks(stopScanTask)
        if (!scanning) return
        scanning = false
        try { bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(callback) }
        catch (_: SecurityException) { }
        if (discovered.isEmpty()) status = "没有发现圆屏，请检查设备已开机并靠近手机"
    }

    override fun onStart() {
        super.onStart()
        connection.setForeground(true)
    }

    override fun onStop() {
        stopScan()
        connection.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        connection.close()
        super.onDestroy()
    }
}
