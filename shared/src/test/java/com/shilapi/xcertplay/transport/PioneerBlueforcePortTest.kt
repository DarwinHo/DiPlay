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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerBlueforcePortTest {
    private class Service(var busy: Boolean = false) : Binder() {
        var onConnect: () -> Unit = {}
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
                    onConnect()
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
        service.notify(16) { writeInt(111); writeInt(4); writeByteArray(byteArrayOf(1, 2, 3, 4)) }
        service.notify(16) { writeInt(110); writeInt(5); writeByteArray(byteArrayOf(1, 2, 3, 4)) }
        assertNull(stream.recv(20, 0))
        service.notify(16) { writeInt(110); writeInt(4); writeByteArray(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), stream.recv(20, 0))
        stream.close(); assertEquals(listOf(0x4e, 1, 0x86, 0x85, 0x87, 2), service.calls)
    }

    @Test fun invalidInlineLengthFailsSessionWithoutAllocating() {
        val service = Service()
        val stream = PioneerSppDuplexStream(PioneerBlueforcePort(service, 110, { false }, {}))
        service.notify(16) { writeInt(110); writeInt(4); writeInt(Int.MAX_VALUE) }
        assertThrows(IOException::class.java) { stream.recv(1, 0) }; stream.close()
    }

    @Test fun inlinePayloadPreservesBytesPaddingAndFollowingField() {
        for (size in listOf(0, 1, 2, 3, 4, 5, 4096, 65536)) {
            val parcel = Parcel.obtain()
            try {
                val bytes = ByteArray(size) { (it * 37 + 128).toByte() }
                parcel.writeInt(110); parcel.writeInt(4)
                parcel.writeByteArray(bytes); parcel.writeInt(0x12345678)
                parcel.setDataPosition(8)
                assertArrayEquals(bytes, PioneerParcel.readRaw(parcel, 65536))
                assertEquals(0x12345678, parcel.readInt())
                assertEquals(0, parcel.dataAvail())
            } finally { parcel.recycle() }
        }
    }

    @Test fun malformedInlinePayloadFailsSession() {
        val malformed: List<Parcel.() -> Unit> = listOf(
            {}, // missing length
            { writeInt(-1) },
            { writeInt(65537) },
            { writeInt(8); writeInt(0) }, // shorter than declared length
            { writeByteArray(byteArrayOf(1, 2, 3)); setDataSize(dataSize() - 1) }, // missing padding
        )
        for (write in malformed) {
            val service = Service()
            val stream = PioneerSppDuplexStream(PioneerBlueforcePort(service, 110, { false }, {}))
            try {
                service.notify(16) { writeInt(110); writeInt(4); write() }
                assertThrows(IOException::class.java) { stream.recv(1, 0) }
            } finally { stream.close() }
        }
    }

    @Test fun cancelledConnectNeverRequestsAConnection() {
        val service = Service()
        val stream = PioneerSppDuplexStream(PioneerBlueforcePort(service, 110, { true }, {}))
        assertThrows(IOException::class.java) { stream.connect() }
        assertFalse(service.calls.contains(0x85)); assertFalse(service.calls.contains(0x87))
    }

    @Test fun closeWaitsForInFlightStartBeforeDisconnecting() {
        val service = Service()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1); val closed = CountDownLatch(1)
        service.onConnect = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val stream = PioneerSppDuplexStream(PioneerBlueforcePort(service, 110, { false }, {}))
        val connecting = Thread { runCatching { stream.connect() } }
        val closing = Thread { closeAttempted.countDown(); stream.close(); closed.countDown() }
        try {
            connecting.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            closing.start(); assertTrue(closeAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(closed.await(200, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown(); connecting.join(5000); closing.join(5000)
            stream.close()
        }
        assertFalse(connecting.isAlive); assertFalse(closing.isAlive)
        assertEquals(listOf(0x4e, 1, 0x86, 0x85, 0x87, 2), service.calls)
    }
}
