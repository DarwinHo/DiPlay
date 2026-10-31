package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], manifest = Config.NONE)
class Ipv6NcmBridgeDiagnosticTest {

    private fun dummyHostMac(): ByteArray = byteArrayOf(0x02, 0, 0, 0, 0, 0x02)
    private fun dummyPeerMac(): ByteArray = byteArrayOf(0x02, 0, 0, 0, 0, 0x03)

    /**
     * RFC 2460 / RFC 4443 IPv6 pseudo-header checksum calculation for ICMPv6.
     * Computes the 16-bit 1's complement sum over the pseudo-header and ICMPv6 payload.
     */
    private fun computeIcmp6Checksum(ipv6: ByteArray, icmpOffset: Int = 40): Int {
        var sum = 0L
        for (i in 8 until 24 step 2) {
            sum += ((ipv6[i].toInt() and 0xff) shl 8) or (ipv6[i + 1].toInt() and 0xff)
        }
        for (i in 24 until 40 step 2) {
            sum += ((ipv6[i].toInt() and 0xff) shl 8) or (ipv6[i + 1].toInt() and 0xff)
        }
        val length = ipv6.size - icmpOffset
        sum += length
        sum += 58 // Next header: ICMPv6
        for (i in icmpOffset until ipv6.size step 2) {
            if (i == icmpOffset + 2) continue
            val b1 = ipv6[i].toInt() and 0xff
            val b2 = if (i + 1 < ipv6.size) ipv6[i + 1].toInt() and 0xff else 0
            sum += (b1 shl 8) or b2
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xffff) + (sum shr 16)
        }
        return (sum.inv() and 0xffff).toInt()
    }

    /** Builds an IPv6 ICMPv6 packet wrapped in Ethernet II (78 bytes total) with computed RFC 4443 checksum for forwarding observation tests. */
    private fun buildIcmp6Frame(type: Int): ByteArray {
        val payload = ByteArray(64)
        payload[0] = 0x60 // IPv6 version
        payload[4] = 0; payload[5] = 24 // Payload length = 24 bytes
        payload[6] = 58   // Next header: ICMPv6
        payload[7] = 255.toByte() // Hop limit = 255 per RFC 4861
        // Source link-local IP fe80::3 (peer iPhone)
        payload[8] = 0xfe.toByte(); payload[9] = 0x80.toByte(); payload[23] = 0x03
        // Destination link-local IP fe80::2 (host car)
        payload[24] = 0xfe.toByte(); payload[25] = 0x80.toByte(); payload[39] = 0x02
        payload[40] = type.toByte() // ICMPv6 Type (135 NS)
        payload[41] = 0 // Code = 0
        // Target Address = fe80::2 at offset 48..63
        payload[48] = 0xfe.toByte(); payload[49] = 0x80.toByte(); payload[63] = 0x02
        val checksum = computeIcmp6Checksum(payload, icmpOffset = 40)
        payload[42] = (checksum shr 8).toByte()
        payload[43] = (checksum and 0xff).toByte()
        return EthernetIpv6Codec.build(dummyPeerMac(), dummyHostMac(), payload)
    }

    /** Builds an unpatched 64-byte Neighbor Advertisement packet emitted by TUN with computed RFC 4443 checksum. */
    private fun buildUnpatchedNeighborAdvertisement(): ByteArray {
        val packet = ByteArray(64)
        packet[0] = 0x60
        packet[4] = 0; packet[5] = 24 // payload length = 24 bytes
        packet[6] = 58 // ICMPv6
        packet[7] = 255.toByte() // Hop limit = 255
        // Src IP fe80::2 (host), Dst IP fe80::3 (peer)
        packet[8] = 0xfe.toByte(); packet[9] = 0x80.toByte(); packet[23] = 0x02
        packet[24] = 0xfe.toByte(); packet[25] = 0x80.toByte(); packet[39] = 0x03
        packet[40] = 136.toByte() // NA
        packet[41] = 0 // Code
        packet[44] = 0x60 // Flags: Solicited=1, Override=1
        // Target Address = fe80::2
        packet[48] = 0xfe.toByte(); packet[49] = 0x80.toByte(); packet[63] = 0x02
        val checksum = computeIcmp6Checksum(packet, icmpOffset = 40)
        packet[42] = (checksum shr 8).toByte()
        packet[43] = (checksum and 0xff).toByte()
        return packet
    }

    private class SocketPairFixture : AutoCloseable {
        var server: ServerSocket? = null
        var client: Socket? = null
        var hostSide: Socket? = null
        var tunPfd: ParcelFileDescriptor? = null

        init {
            try {
                server = ServerSocket(0)
                client = Socket("127.0.0.1", server!!.localPort)
                hostSide = server!!.accept()
                tunPfd = ParcelFileDescriptor.fromSocket(client)
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        override fun close() {
            runCatching { tunPfd?.close() }
            runCatching { client?.close() }
            runCatching { hostSide?.close() }
            runCatching { server?.close() }
        }
    }

    private fun readFully(socket: Socket, targetLength: Int, timeoutMillis: Int = 4000): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        val input = socket.getInputStream()
        val result = ByteArray(targetLength)
        var offset = 0
        while (offset < targetLength) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                throw SocketTimeoutException("Read deadline exceeded after $offset of $targetLength bytes")
            }
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1)
            socket.soTimeout = remainingMillis.toInt().coerceAtLeast(1)
            val read = input.read(result, offset, targetLength - offset)
            if (read < 0) throw EOFException("Socket closed after $offset of $targetLength bytes")
            offset += read
        }
        return result
    }

    @Test fun diagnosticTracksBidirectionalTrafficWithExactByteComparison() {
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val mockNcm = mock(NcmUsbBridge::class.java)

        val nsFrame = buildIcmp6Frame(135) // 78-byte Ethernet frame carrying 64-byte IPv6 NS
        val ncmDeliveredLatch = CountDownLatch(1)
        val readCount = AtomicInteger(0)

        `when`(mockNcm.recv(anyLong())).thenAnswer {
            if (readCount.getAndIncrement() == 0) {
                ncmDeliveredLatch.countDown()
                nsFrame
            } else {
                Thread.sleep(10)
                null
            }
        }

        val sendLatch = CountDownLatch(1)
        var capturedSentFrame: ByteArray? = null
        `when`(mockNcm.send(any(), anyInt())).thenAnswer { invocation ->
            capturedSentFrame = (invocation.arguments[0] as ByteArray).copyOf()
            sendLatch.countDown()
            true
        }

        SocketPairFixture().use { fixture ->
            val bridge = Ipv6NcmBridge(
                ncm = mockNcm,
                tun = fixture.tunPfd!!,
                hostMac = dummyHostMac(),
                onError = { errors.add(it) },
                onDiagnostic = { diagnostics.add(it) },
            )

            try {
                bridge.start()

                // 1. Inbound NCM -> TUN forwarding verified by reading exact payload bytes from hostSide socket
                assertTrue("NS frame must be fetched by bridge", ncmDeliveredLatch.await(3, TimeUnit.SECONDS))
                val tunReadPayload = readFully(fixture.hostSide!!, 64)
                val expectedIpv6Payload = nsFrame.copyOfRange(14, nsFrame.size)
                assertArrayEquals("Forwarded IPv6 payload must match original NS bytes exactly", expectedIpv6Payload, tunReadPayload)

                // 2. Outbound TUN -> NCM forwarding verified by writing unpatched NA into hostSide socket
                val unpatchedNa = buildUnpatchedNeighborAdvertisement()
                fixture.hostSide!!.getOutputStream().write(unpatchedNa)
                fixture.hostSide!!.getOutputStream().flush()

                assertTrue("Outbound NA must be forwarded to NCM send()", sendLatch.await(3, TimeUnit.SECONDS))
                assertNotNull("Captured frame must not be null", capturedSentFrame)
                val sent = capturedSentFrame!!

                // Ethernet header (14) + Patched NA (72: 64 + 8 target link option) = 86 bytes
                assertEquals("Patched NA frame must contain Ethernet header + patched option", 86, sent.size)
                assertArrayEquals("Ethernet Destination MAC must be peerMac", dummyPeerMac(), sent.copyOfRange(0, 6))
                assertArrayEquals("Ethernet Source MAC must be hostMac", dummyHostMac(), sent.copyOfRange(6, 12))
                assertEquals("EtherType must be IPv6 (0x86dd)", 0x86, sent[12].toInt() and 0xff)
                assertEquals("EtherType must be IPv6 (0x86dd)", 0xdd, sent[13].toInt() and 0xff)
                assertEquals("Patched IPv6 payload length must be 32 bytes", 32, (sent[18].toInt() and 0xff shl 8) or (sent[19].toInt() and 0xff))
                assertEquals("Target Link-Layer Option type must be 2", 2, sent[78].toInt() and 0xff)
                assertEquals("Target Link-Layer Option length must be 1 (8 octets)", 1, sent[79].toInt() and 0xff)
                assertArrayEquals("Target Link-Layer Option MAC must be hostMac", dummyHostMac(), sent.copyOfRange(80, 86))
            } finally {
                bridge.close()
            }

            assertTrue("No I/O errors expected in bidirectional forwarding: $errors", errors.isEmpty())

            // Assert diagnostic outputs
            assertTrue("Must record NCM inbound NS", diagnostics.any { it.contains("ncm rx first icmp6 NS (type=135)") })
            assertTrue("Must record TUN outbound NA", diagnostics.any { it.contains("tun rx first icmp6 NA (type=136)") })
            assertTrue("Must record target-link-layer option patch", diagnostics.any { it.contains("tun added target-link-layer option to first NA") })
            assertTrue("Must emit final summary on close after threads join", diagnostics.any { it.startsWith("ncm io final:") })

            val finalSummary = diagnostics.single { it.startsWith("ncm io final:") }
            assertTrue(finalSummary.contains("rxFrames=1"))
            assertTrue(finalSummary.contains("ncmRxIcmp6_NS=1"))
            assertTrue(finalSummary.contains("tunRxIcmp6_NA=1"))
            assertTrue(finalSummary.contains("tunPatchedNA=1"))
            assertTrue(finalSummary.contains("ncmTxCompleted=1"))
            assertTrue(finalSummary.contains("ncmTxDroppedNotReady=0"))
        }
    }

    @Test fun sendNotReadyAccuratelyReportsAttemptWithoutFalseCompletion() {
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        val mockNcm = mock(NcmUsbBridge::class.java)

        // Mock ncm.send returning false (not ready / bulkTransfer <= 0)
        val sendAttemptLatch = CountDownLatch(1)
        `when`(mockNcm.send(any(), anyInt())).thenAnswer {
            sendAttemptLatch.countDown()
            false
        }
        `when`(mockNcm.recv(anyLong())).thenAnswer { Thread.sleep(20); null }

        SocketPairFixture().use { fixture ->
            val bridge = Ipv6NcmBridge(
                ncm = mockNcm,
                tun = fixture.tunPfd!!,
                hostMac = dummyHostMac(),
                onError = {},
                onDiagnostic = { diagnostics.add(it) },
            )

            try {
                bridge.start()

                // Send multicast packet from TUN so it does not wait for peerMac
                val testPacket = ByteArray(48)
                testPacket[0] = 0x60
                testPacket[6] = 6 // TCP
                testPacket[24] = 0xff.toByte(); testPacket[25] = 0x02.toByte()

                fixture.hostSide!!.getOutputStream().write(testPacket)
                fixture.hostSide!!.getOutputStream().flush()

                assertTrue("Send attempt must occur", sendAttemptLatch.await(3, TimeUnit.SECONDS))
            } finally {
                bridge.close()
            }

            val finalSummary = diagnostics.single { it.startsWith("ncm io final:") }
            assertTrue("Must record 1 attempted TX frame", finalSummary.contains("ncmTxAttempts=1"))
            assertTrue("Must report 0 completed frames when send returned false", finalSummary.contains("ncmTxCompleted=0"))
            assertTrue("Must report 1 dropped not ready", finalSummary.contains("ncmTxDroppedNotReady=1"))
        }
    }

    @Test fun diagnosticCallbackExceptionIsIsolatedAndNeverDisruptsForwarding() {
        val mockNcm = mock(NcmUsbBridge::class.java)
        val nsFrame = buildIcmp6Frame(135)
        val ncmDeliveredLatch = CountDownLatch(1)
        val readCount = AtomicInteger(0)

        `when`(mockNcm.recv(anyLong())).thenAnswer {
            if (readCount.getAndIncrement() == 0) {
                ncmDeliveredLatch.countDown()
                nsFrame
            } else {
                Thread.sleep(20)
                null
            }
        }

        val exceptionThrown = AtomicInteger(0)

        SocketPairFixture().use { fixture ->
            val bridge = Ipv6NcmBridge(
                ncm = mockNcm,
                tun = fixture.tunPfd!!,
                hostMac = dummyHostMac(),
                onError = {},
                onDiagnostic = {
                    exceptionThrown.incrementAndGet()
                    throw RuntimeException("Diagnostic error simulated")
                },
            )

            try {
                bridge.start()

                // Verify packet forwarding still succeeds despite diagnostic crash
                assertTrue(ncmDeliveredLatch.await(3, TimeUnit.SECONDS))
                val bytesRead = readFully(fixture.hostSide!!, 64)
                val expectedIpv6Payload = nsFrame.copyOfRange(14, nsFrame.size)

                assertArrayEquals("Packet forwarding must succeed despite diagnostic exception", expectedIpv6Payload, bytesRead)
                assertTrue("Diagnostic callback must have been executed and thrown", exceptionThrown.get() > 0)
            } finally {
                bridge.close()
            }
        }
    }

    @Test fun closeLogsClosingWhenWorkerIsStillActiveAndCleansUpCleanly() {
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        val mockNcm = mock(NcmUsbBridge::class.java)

        val workerStartedLatch = CountDownLatch(1)
        val workerReleaseLatch = CountDownLatch(1)
        val workerExitedLatch = CountDownLatch(1)

        `when`(mockNcm.recv(anyLong())).thenAnswer {
            workerStartedLatch.countDown()
            try {
                // Block worker past JOIN_TIMEOUT_MILLIS (2000ms), but wake on interrupt or release
                workerReleaseLatch.await(3000, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                workerExitedLatch.countDown()
            }
            null
        }

        SocketPairFixture().use { fixture ->
            val bridge = Ipv6NcmBridge(
                ncm = mockNcm,
                tun = fixture.tunPfd!!,
                hostMac = dummyHostMac(),
                onError = {},
                onDiagnostic = { diagnostics.add(it) },
            )

            bridge.start()
            assertTrue("Worker thread must enter recv", workerStartedLatch.await(3, TimeUnit.SECONDS))

            try {
                bridge.close()
                // bridge.close() waited 2000ms for ncmToTunThread, saw it still alive, and logged "closing (workers active)"
                assertTrue("Must record closing (workers active) tag",
                    diagnostics.any { it.startsWith("ncm io closing (workers active):") })
            } finally {
                // Release the worker and wait for it to cleanly terminate
                workerReleaseLatch.countDown()
                assertTrue("Worker thread must terminate cleanly", workerExitedLatch.await(2, TimeUnit.SECONDS))
            }
        }
    }
}
