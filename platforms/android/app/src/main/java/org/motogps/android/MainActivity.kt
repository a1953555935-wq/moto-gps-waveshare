package org.motogps.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

private val navigationService = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")

class MainActivity : ComponentActivity() {
    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private var status by mutableStateOf("尚未扫描")
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            status = "发现设备：${result.device.address}（信号 ${result.rssi} dBm）"
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status = "扫描失败：错误码 $errorCode"
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startScan() else status = "需要蓝牙和定位权限才能查找圆屏"
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
                    Text("Android 开发版：目前可发现附近的圆屏，连接与导航功能正在开发。")
                    Text(status)
                    Button(onClick = ::requestScan) { Text("扫描圆屏") }
                }
            }
        }
    }

    private fun requestScan() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startScan() {
        val adapter: BluetoothAdapter? = bluetoothManager.adapter
        if (adapter?.isEnabled != true) {
            status = "请先开启手机蓝牙"
            return
        }
        if (scanning) adapter.bluetoothLeScanner?.stopScan(callback)
        val scanner = adapter.bluetoothLeScanner ?: run {
            status = "此手机无法启动 BLE 扫描"
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(navigationService)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().build(), callback)
        scanning = true
        status = "正在查找 MOTO GPS 圆屏…"
    }

    override fun onStop() {
        val scanAllowed = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        if (scanning && scanAllowed) {
            bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(callback)
            scanning = false
        }
        super.onStop()
    }
}
