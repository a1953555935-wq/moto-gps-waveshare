package org.motogps.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

/** Owns one foreground BLE link. Every GATT operation and codec call runs on the main thread. */
@SuppressLint("MissingPermission") // connect() checks runtime permission; revocation is handled below.
class MotoBleConnection(
    private val context: Context,
    private val report: (String) -> Unit,
) : AutoCloseable {
    private enum class Phase { CLOSED, CONNECTING, MTU, SERVICES, SUBSCRIBING, STARTING, FINAL, READY }

    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.CLOSED
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var codec: NativeBleCodec? = null
    private var gate: HandshakeGate? = null
    private var session = 0
    private var frameSize = 20
    private var lastInboundMs = 0L
    private var sessionStartMs = 0L
    private var heartbeatIntervalMs = 1000L
    private var writePending = false
    private var foreground = true
    private val writes = ArrayDeque<ByteArray>()
    private var handshakeAttempts = 0
    private val backgroundClose = Runnable {
        if (!foreground && phase != Phase.CLOSED) {
            close()
            report("已进入后台，圆屏连接已关闭")
        }
    }

    private val timeout = Runnable {
        when (phase) {
            Phase.STARTING, Phase.FINAL -> {
                if (handshakeAttempts >= 5) {
                    fail("圆屏协议握手超时")
                } else {
                    handshakeAttempts++
                    sendHandshake(phase == Phase.FINAL)
                    armTimeout(1200)
                }
            }
            Phase.CONNECTING, Phase.MTU, Phase.SERVICES, Phase.SUBSCRIBING -> fail("蓝牙连接步骤超时")
            else -> Unit
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (phase != Phase.READY) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastInboundMs > maxOf(5000L, heartbeatIntervalMs * 3)) {
                fail("圆屏通信超时")
                return
            }
            try {
                enqueue(codec!!.heartbeat(session, (now - sessionStartMs).toInt()))
            } catch (error: RuntimeException) {
                fail("心跳编码失败：${error.message}")
                return
            }
            main.postDelayed(this, heartbeatIntervalMs)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (link !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("圆屏已断开（状态 $status）")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    phase = Phase.MTU
                    report("蓝牙已连接，正在协商传输大小")
                    armTimeout()
                    try {
                        if (!link.requestMtu(517)) discover(link, 23)
                    } catch (_: SecurityException) { fail("蓝牙连接权限已被撤销") }
                }
            }
        }

        override fun onMtuChanged(link: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (link === gatt && phase == Phase.MTU) {
                    discover(link, if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
                }
            }
        }

        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
            main.post {
                if (link !== gatt || phase != Phase.SERVICES) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("发现圆屏服务失败：$status")
                    return@post
                }
                val service = link.getService(SERVICE)
                val inbound = service?.getCharacteristic(TX)
                val outbound = service?.getCharacteristic(RX)
                val descriptor = inbound?.getDescriptor(CCCD)
                if (inbound == null || outbound == null || descriptor == null ||
                    inbound.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 ||
                    outbound.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                    fail("圆屏固件的 BLE 服务与项目协议不匹配")
                    return@post
                }
                rx = outbound
                try {
                    if (!link.setCharacteristicNotification(inbound, true)) {
                        fail("无法订阅圆屏通知")
                        return@post
                    }
                    phase = Phase.SUBSCRIBING
                    report("正在订阅加密通知；如出现系统配对提示，请完成配对")
                    armTimeout(20_000)
                    val accepted = if (Build.VERSION.SDK_INT >= 33) {
                        link.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        link.writeDescriptor(descriptor)
                    }
                    if (!accepted) fail("无法写入圆屏通知订阅")
                } catch (_: SecurityException) { fail("蓝牙连接权限已被撤销") }
            }
        }

        override fun onDescriptorWrite(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (link !== gatt || phase != Phase.SUBSCRIBING || descriptor.uuid != CCCD) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("订阅加密通知失败（$status），请检查手机和圆屏配对")
                    return@post
                }
                codec = NativeBleCodec(frameSize)
                session = SecureRandom().nextInt().let { if (it == 0) 1 else it }
                gate = HandshakeGate(session, frameSize)
                sessionStartMs = SystemClock.elapsedRealtime()
                phase = Phase.STARTING
                handshakeAttempts = 0
                report("已订阅圆屏，正在进行协议握手")
                sendHandshake(false)
                armTimeout(1200)
            }
        }

        override fun onCharacteristicWrite(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (link !== gatt || characteristic.uuid != RX) return@post
                writePending = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("向圆屏发送数据失败（$status）")
                } else {
                    pumpWrites()
                }
            }
        }

        override fun onCharacteristicChanged(
            link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            main.post { if (link === gatt && characteristic.uuid == TX) acceptFrame(value) }
        }

        @Deprecated("Android 13 uses the value argument")
        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val value = characteristic.value?.clone() ?: return
                main.post { if (link === gatt && characteristic.uuid == TX) acceptFrame(value) }
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        close()
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            report("需要蓝牙连接权限")
            return
        }
        phase = Phase.CONNECTING
        report("正在连接圆屏…")
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("无法启动蓝牙连接") else armTimeout()
        } catch (error: SecurityException) {
            fail("缺少蓝牙连接权限：${error.message}")
        }
    }

    private fun discover(link: BluetoothGatt, mtu: Int) {
        frameSize = minOf(512, mtu - 3)
        if (frameSize < 20) { fail("蓝牙 MTU 过小"); return }
        phase = Phase.SERVICES
        report("正在查找圆屏导航服务")
        armTimeout()
        try {
            if (!link.discoverServices()) fail("无法查找圆屏服务")
        } catch (_: SecurityException) { fail("蓝牙连接权限已被撤销") }
    }

    private fun sendHandshake(ready: Boolean) {
        try {
            enqueue(codec!!.connection(session, ready))
        } catch (error: RuntimeException) {
            fail("协议握手编码失败：${error.message}")
        }
    }

    private fun acceptFrame(frame: ByteArray) {
        val message = try {
            codec?.receive(frame, SystemClock.elapsedRealtime())
        } catch (error: RuntimeException) {
            fail("圆屏协议帧无效：${error.message}")
            return
        } ?: return
        lastInboundMs = SystemClock.elapsedRealtime()
        when (message[0]) {
            1 -> {
                if (phase != Phase.STARTING && phase != Phase.FINAL) return
                val status = try { DeviceStatus.fromWire(message) }
                    catch (error: IllegalArgumentException) { fail(error.message ?: "状态无效"); return }
                val step = try { gate!!.accept(status) }
                    catch (error: IllegalArgumentException) { fail(error.message ?: "握手失败"); return }
                if (step == HandshakeStep.SEND_PHONE_READY) {
                    phase = Phase.FINAL
                    handshakeAttempts = 0
                    sendHandshake(true)
                    armTimeout(1200)
                } else {
                    phase = Phase.READY
                    main.removeCallbacks(timeout)
                    heartbeatIntervalMs = status.heartbeatMs.toLong()
                    report("协议已就绪：圆屏连接成功")
                    main.postDelayed(heartbeat, heartbeatIntervalMs)
                }
            }
            2 -> if (phase == Phase.READY && message[1] != session) fail("圆屏会话编号不匹配")
        }
    }

    private fun enqueue(frames: Array<ByteArray>) {
        frames.forEach(writes::addLast)
        pumpWrites()
    }

    private fun pumpWrites() {
        if (writePending || writes.isEmpty()) return
        val link = gatt ?: return
        val characteristic = rx ?: return
        val frame = writes.removeFirst()
        writePending = true
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val accepted = try {
            if (Build.VERSION.SDK_INT >= 33) {
                link.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                link.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) { false }
        if (!accepted) fail("蓝牙写入队列启动失败")
    }

    private fun armTimeout(delayMs: Long = 10_000) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, delayMs)
    }

    private fun fail(reason: String) {
        close()
        report(reason)
    }

    /** Allow the system pairing dialog to cover the Activity briefly. */
    fun setForeground(active: Boolean) {
        foreground = active
        main.removeCallbacks(backgroundClose)
        if (!active) main.postDelayed(backgroundClose, 30_000)
    }

    override fun close() {
        phase = Phase.CLOSED
        main.removeCallbacks(timeout)
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(backgroundClose)
        writes.clear()
        writePending = false
        rx = null
        gate = null
        codec?.close()
        codec = null
        val old = gatt
        gatt = null
        try { old?.disconnect() } catch (_: SecurityException) { }
        old?.close()
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")
        private val RX = UUID.fromString("7e57a001-b50c-4b6a-9c57-40a54e8e1000")
        private val TX = UUID.fromString("7e57a002-b50c-4b6a-9c57-40a54e8e1000")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
