package org.motogps.android.ble

/** JNI adapter around the repository's shared BLE v1 encoder and reassembler. */
class NativeBleCodec(maxFrameSize: Int) : AutoCloseable {
    private var handle: Long = create(maxFrameSize)

    fun connection(session: Int, ready: Boolean): Array<ByteArray> =
        connection(checkedHandle(), session, if (ready) 1 else 0)

    fun heartbeat(session: Int, elapsedMs: Int): Array<ByteArray> =
        heartbeat(checkedHandle(), session, elapsedMs)

    /** Complete messages only. ConnectionStatus fields follow the shared C++ struct order. */
    fun receive(frame: ByteArray, nowMs: Long): IntArray? = receive(checkedHandle(), frame, nowMs)

    override fun close() {
        if (handle != 0L) {
            destroy(handle)
            handle = 0
        }
    }

    private fun checkedHandle(): Long = handle.takeIf { it != 0L }
        ?: throw IllegalStateException("BLE codec is closed")

    private external fun create(maximum: Int): Long
    private external fun destroy(handle: Long)
    private external fun connection(handle: Long, session: Int, state: Int): Array<ByteArray>
    private external fun heartbeat(handle: Long, session: Int, elapsed: Int): Array<ByteArray>
    private external fun receive(handle: Long, frame: ByteArray, nowMs: Long): IntArray?

    companion object { init { System.loadLibrary("moto_android_ble") } }
}
