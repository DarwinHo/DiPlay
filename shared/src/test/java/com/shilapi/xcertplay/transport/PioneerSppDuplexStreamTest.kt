package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PioneerSppDuplexStreamTest {
    private class Port : PioneerSppPort {
        var bytes: (ByteArray) -> Unit = {}
        var failure: (IOException) -> Unit = {}
        val writes = mutableListOf<ByteArray>()
        var closes = 0
        var reject = false
        override fun subscribe(onBytes: (ByteArray) -> Unit, onFailure: (IOException) -> Unit) { bytes = onBytes; failure = onFailure }
        override fun connect() { if (reject) throw IOException("unavailable") }
        override fun write(bytes: ByteArray) { writes.add(bytes) }
        override fun close() { closes++ }
    }

    @Test fun callbackFragmentsPreserveByteOrderAcrossIap2Reads() {
        val port = Port(); val stream = PioneerSppDuplexStream(port)
        val original = byteArrayOf(1, 2, 3)
        port.bytes(original); original[0] = 99; port.bytes(byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(1, 2), stream.recv(2, 0))
        assertArrayEquals(byteArrayOf(3), stream.recv(8, 0))
        assertArrayEquals(byteArrayOf(4, 5), stream.recv(8, 0))
        assertNull(stream.recv(8, 0)); stream.close()
    }

    @Test fun sendsLargeIap2FrameAsOrderedNativeSizedChunks() {
        val port = Port(); val stream = PioneerSppDuplexStream(port)
        val frame = ByteArray(9001) { it.toByte() }; stream.send(frame)
        assertEquals(listOf(4096, 4096, 809), port.writes.map { it.size })
        assertArrayEquals(frame, port.writes.flatMap { it.toList() }.toByteArray()); stream.close()
    }

    @Test fun callbackOverflowFailsRatherThanSilentlyDroppingIap2Bytes() {
        val port = Port(); val stream = PioneerSppDuplexStream(port)
        port.bytes(ByteArray(65536)); port.bytes(byteArrayOf(1))
        assertThrows(IOException::class.java) { stream.recv(10, 0) }
        assertThrows(IOException::class.java) { stream.send(byteArrayOf(1)) }
        stream.close()
    }

    @Test fun serviceDeathFailsBlockedReceiveAndCloseIsIdempotent() {
        val port = Port(); val stream = PioneerSppDuplexStream(port)
        val ready = CountDownLatch(1); val finished = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()
        val reader = Thread { ready.countDown(); try { stream.recv(128, 10000) } catch (e: Throwable) { result.set(e) } finally { finished.countDown() } }
        reader.start(); assertTrue(ready.await(2, TimeUnit.SECONDS))
        port.failure(IOException("service died"))
        assertTrue(finished.await(2, TimeUnit.SECONDS)); assertTrue(result.get() is IOException)
        stream.close(); stream.close(); assertEquals(1, port.closes)
        port.bytes(byteArrayOf(1)); assertThrows(IOException::class.java) { stream.send(byteArrayOf(1)) }
    }

    @Test fun failedConnectReleasesRegistration() {
        val port = Port().apply { reject = true }; val stream = PioneerSppDuplexStream(port)
        assertThrows(IOException::class.java) { stream.connect() }
        stream.close(); assertEquals(1, port.closes)
    }
}
