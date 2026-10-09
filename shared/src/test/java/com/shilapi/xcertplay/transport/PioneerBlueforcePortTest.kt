package com.shilapi.xcertplay.transport

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerBlueforcePortTest {
    private class Service(var busy: Boolean = false) : Binder() {
        val calls = mutableListOf<Int>()
        var callback: IBinder? = null
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(PioneerBlueforcePort.SERVICE)
            calls.add(code)
            when (code) {
                0x4e -> { assertEquals(110, data.readInt()); assertEquals(0, data.readInt()); reply!!.writeInt(0); reply.writeInt(4) }
                1, 2 -> { data.readInt(); callback = data.readStrongBinder(); assertEquals(0, data.readInt()); reply!!.writeInt(0) }
                0x86 -> { assertEquals(4, data.readInt()); reply!!.writeInt(0); reply.writeInt(if (busy) 1 else 0) }
                0x85 -> {
                    assertEquals(4, data.readInt()); reply!!.writeInt(0)
                    notify(17) { writeInt(4); writeInt(1) }
                }
                0x87 -> { assertEquals(4, data.readInt()); reply!!.writeInt(0) }
                else -> fail("Unexpected transaction $code")
            }
            return true
        }
        fun notify(code: Int, write: Parcel.() -> Unit) {
            val p = Parcel.obtain()
            try { p.writeInterfaceToken(PioneerBlueforcePort.NOTIFY); p.write(); callback!!.transact(code, p, null, IBinder.FLAG_ONEWAY) }
            finally { p.recycle() }
        }
    }

    @Test fun existingSppIsPreservedWithoutConnectOrDisconnect() {
        val service = Service(busy = true)
        val stream = PioneerSppDuplexStream(PioneerBlueforcePort(service, 110, { false }, {}))
        assertThrows(IOException::class.java) { stream.connect() }
        assertEquals(listOf(0x4e, 1, 0x86, 2), service.calls)
    }

    @Test fun inlineCallbackOnlyAcceptsSelectedDeviceAndPort() {
        val service = Service()
        val stream = PioneerSppDuplexStream(PioneerBlueforcePort(service, 110, { false }, {}))
        stream.connect()
        service.notify(16) { writeInt(111); writeInt(4); writeInt(4); writeInt(0x04030201) }
        service.notify(16) { writeInt(110); writeInt(5); writeInt(4); writeInt(0x04030201) }
        assertNull(stream.recv(20, 0))
        service.notify(16) { writeInt(110); writeInt(4); writeInt(3); writeInt(0x00030201) }
        assertArrayEquals(byteArrayOf(1, 2, 3), stream.recv(20, 0))
        stream.close(); assertEquals(listOf(0x4e, 1, 0x86, 0x85, 0x87, 2), service.calls)
    }

    @Test fun invalidInlineLengthFailsSessionWithoutAllocating() {
        val service = Service()
        val stream = PioneerSppDuplexStream(PioneerBlueforcePort(service, 110, { false }, {}))
        service.notify(16) { writeInt(110); writeInt(4); writeInt(Int.MAX_VALUE) }
        assertThrows(IOException::class.java) { stream.recv(1, 0) }; stream.close()
    }

    @Test fun cancelledConnectNeverRequestsAConnection() {
        val service = Service()
        val stream = PioneerSppDuplexStream(PioneerBlueforcePort(service, 110, { true }, {}))
        assertThrows(IOException::class.java) { stream.connect() }
        assertFalse(service.calls.contains(0x85)); assertFalse(service.calls.contains(0x87))
    }
}
