package org.motogps.android.ble

internal data class DeviceStatus(
    val role: Int,
    val state: Int,
    val minimumVersion: Int,
    val maximumVersion: Int,
    val capabilities: Int,
    val session: Int,
    val maxFrameSize: Int,
    val heartbeatMs: Int,
) {
    companion object {
        fun fromWire(message: IntArray): DeviceStatus {
            require(message.size == 9 && message[0] == 1) { "无效的设备状态" }
            return DeviceStatus(message[1], message[2], message[3], message[4],
                message[5], message[6], message[7], message[8])
        }
    }
}

internal enum class HandshakeStep { SEND_PHONE_READY, READY }

/** The second matching Device Ready, rather than GATT success, opens the data gate. */
internal class HandshakeGate(private val session: Int, private val localFrameSize: Int) {
    private var initial: DeviceStatus? = null
    var ready = false
        private set
    var negotiatedFrameSize = 20
        private set

    init {
        require(session != 0 && localFrameSize in 20..512)
    }

    fun accept(status: DeviceStatus): HandshakeStep {
        require(!ready) { "握手已经完成" }
        require(status.role == 2 && status.state == 1) { "设备未进入 Ready" }
        require(status.minimumVersion <= 1 && status.maximumVersion >= 1) { "协议版本不兼容" }
        require(status.session == session) { "设备会话编号不匹配" }
        val required = (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 4) or (1 shl 6)
        require(status.capabilities and required == required) { "设备缺少必要能力" }
        require(status.maxFrameSize in 13..512 && status.heartbeatMs >= 250) {
            "设备协商参数无效"
        }
        val first = initial
        return if (first == null) {
            initial = status
            negotiatedFrameSize = minOf(localFrameSize, status.maxFrameSize)
            HandshakeStep.SEND_PHONE_READY
        } else {
            require(status == first) { "设备两次 Ready 参数不一致" }
            ready = true
            HandshakeStep.READY
        }
    }
}
