package org.motogps.android.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeGateTest {
    private val status = DeviceStatus(2, 1, 1, 1, 0x7f, 0x12345678, 185, 1000)

    @Test fun waitsForSecondMatchingReady() {
        val gate = HandshakeGate(status.session, 244)
        assertEquals(HandshakeStep.SEND_PHONE_READY, gate.accept(status))
        assertFalse(gate.ready)
        assertEquals(185, gate.negotiatedFrameSize)
        assertEquals(HandshakeStep.READY, gate.accept(status))
        assertTrue(gate.ready)
    }

    @Test fun rejectsChangedNegotiationAndWrongSession() {
        val gate = HandshakeGate(status.session, 244)
        assertThrows(IllegalArgumentException::class.java) {
            gate.accept(status.copy(session = 123))
        }
        gate.accept(status)
        assertThrows(IllegalArgumentException::class.java) {
            gate.accept(status.copy(maxFrameSize = 100))
        }
        assertFalse(gate.ready)
    }

    @Test fun rejectsMissingRequiredCapability() {
        val gate = HandshakeGate(status.session, 244)
        assertThrows(IllegalArgumentException::class.java) {
            gate.accept(status.copy(capabilities = status.capabilities and (1 shl 6).inv()))
        }
    }
}
