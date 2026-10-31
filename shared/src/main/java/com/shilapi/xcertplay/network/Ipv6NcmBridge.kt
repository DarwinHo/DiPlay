package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Link-local neighbor discovery stays in the Android kernel,
 * mirroring LIVI's reliance on the host kernel for NDP on its TAP interface.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray,
    private val onError: (Throwable) -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    private var inboundLogBudget = 16
    private var outboundLogBudget = 24
    private val running = AtomicBoolean(false)
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread

    // Observational network counters (thread-safe scalar telemetry)
    @Volatile private var ncmRxAttempts = 0L
    @Volatile private var ncmRxTimeouts = 0L
    @Volatile private var ncmRxFrames = 0L
    @Volatile private var ncmRxBytes = 0L
    @Volatile private var ncmRxIcmp6Ns = 0L
    @Volatile private var ncmRxIcmp6Na = 0L

    @Volatile private var tunTxPackets = 0L
    @Volatile private var tunTxBytes = 0L

    @Volatile private var tunRxReads = 0L
    @Volatile private var tunRxZeroReads = 0L
    @Volatile private var tunRxPackets = 0L
    @Volatile private var tunRxBytes = 0L
    @Volatile private var tunRxIcmp6Ns = 0L
    @Volatile private var tunRxIcmp6Na = 0L
    @Volatile private var tunPatchedNaCount = 0L

    @Volatile private var ncmTxAttemptFrames = 0L
    @Volatile private var ncmTxAttemptBytes = 0L
    @Volatile private var ncmTxCompletedFrames = 0L
    @Volatile private var ncmTxCompletedBytes = 0L
    @Volatile private var ncmTxDroppedNotReady = 0L
    @Volatile private var ncmTxDroppedNoPeerMac = 0L

    private val lastPeriodicLogNanos = AtomicLong(0L)

    private fun emitDiagnostic(message: String) {
        runCatching { onDiagnostic(message) }
    }

    private fun checkPeriodicDiagnostics() {
        val now = System.nanoTime()
        val last = lastPeriodicLogNanos.get()
        if (now - last >= PERIODIC_DIAGNOSTIC_INTERVAL_NANOS) {
            if (lastPeriodicLogNanos.compareAndSet(last, now)) {
                emitDiagnostic(countersSummary("ncm io stats"))
            }
        }
    }

    private fun countersSummary(prefix: String): String =
        "$prefix: rxAttempts=$ncmRxAttempts rxTimeouts=$ncmRxTimeouts rxFrames=$ncmRxFrames rxBytes=$ncmRxBytes " +
            "ncmRxIcmp6_NS=$ncmRxIcmp6Ns ncmRxIcmp6_NA=$ncmRxIcmp6Na " +
            "tunTxPkts=$tunTxPackets tunTxBytes=$tunTxBytes " +
            "tunRxReads=$tunRxReads tunRxZeroReads=$tunRxZeroReads tunRxPkts=$tunRxPackets tunRxBytes=$tunRxBytes " +
            "tunRxIcmp6_NS=$tunRxIcmp6Ns tunRxIcmp6_NA=$tunRxIcmp6Na tunPatchedNA=$tunPatchedNaCount " +
            "ncmTxAttempts=$ncmTxAttemptFrames ncmTxAttemptBytes=$ncmTxAttemptBytes " +
            "ncmTxCompleted=$ncmTxCompletedFrames ncmTxCompletedBytes=$ncmTxCompletedBytes " +
            "ncmTxDroppedNotReady=$ncmTxDroppedNotReady droppedNoPeer=$ncmTxDroppedNoPeerMac"

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        ncmToTunThread = Thread(::runNcmToTun, "ncm-ipv6-in").apply {
            isDaemon = true
            start()
        }
        tunToNcmThread = Thread(::runTunToNcm, "ncm-ipv6-out").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        ncm.close()
        tun.close()
        join(ncmToTunThread)
        join(tunToNcmThread)
        val workersStopped = !ncmToTunThread.isAlive && !tunToNcmThread.isAlive
        val tag = if (workersStopped) "ncm io final" else "ncm io closing (workers active)"
        emitDiagnostic(countersSummary(tag))
    }

    private fun runNcmToTun() {
        val output = FileOutputStream(tun.fileDescriptor)
        try {
            while (running.get()) {
                ncmRxAttempts++
                val frame = ncm.recv(READ_TIMEOUT_MILLIS)
                if (frame == null) {
                    ncmRxTimeouts++
                    checkPeriodicDiagnostics()
                    continue
                }
                ncmRxFrames++
                ncmRxBytes += frame.size

                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame)
                if (ipv6 == null) {
                    checkPeriodicDiagnostics()
                    continue
                }
                peerMac = ipv6.sourceMac

                val nextHeader = frame[ipv6.payloadOffset + 6].toInt() and 0xff
                if (nextHeader == 58 && ipv6.payloadLength >= 48) {
                    val icmpType = frame[ipv6.payloadOffset + 40].toInt() and 0xff
                    if (icmpType == 135) {
                        if (ncmRxIcmp6Ns == 0L) {
                            emitDiagnostic("ncm rx first icmp6 NS (type=135) bytes=${ipv6.payloadLength}")
                        }
                        ncmRxIcmp6Ns++
                    } else if (icmpType == 136) {
                        if (ncmRxIcmp6Na == 0L) {
                            emitDiagnostic("ncm rx first icmp6 NA (type=136) bytes=${ipv6.payloadLength}")
                        }
                        ncmRxIcmp6Na++
                    }
                }

                if (!loggedInbound) {
                    loggedInbound = true
                    Log.i(
                        TAG,
                        "ncm first inbound ipv6 bytes=${ipv6.payloadLength} " +
                            "peer=${ipv6.sourceMac.macString()}",
                    )
                    emitDiagnostic("ncm first rx frame bytes=${frame.size} payload=${ipv6.payloadLength} nextHeader=$nextHeader")
                }
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    Log.i(TAG, "ncm inbound ${frame.summary(ipv6.payloadOffset)}")
                }
                output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
                tunTxPackets++
                tunTxBytes += ipv6.payloadLength
                checkPeriodicDiagnostics()
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun runTunToNcm() {
        val input = FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            while (running.get()) {
                tunRxReads++
                val length = input.read(buffer)
                if (length == -1) {
                    if (running.get()) onError(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    tunRxZeroReads++
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                tunRxPackets++
                tunRxBytes += length

                val tunPacket = buffer.copyOf(length)
                val nextHeader = if (tunPacket.size >= 40) tunPacket[6].toInt() and 0xff else 0
                if (nextHeader == 58 && tunPacket.size >= 48) {
                    val icmpType = tunPacket[40].toInt() and 0xff
                    if (icmpType == 135) {
                        if (tunRxIcmp6Ns == 0L) {
                            emitDiagnostic("tun rx first icmp6 NS (type=135) bytes=$length")
                        }
                        tunRxIcmp6Ns++
                    } else if (icmpType == 136) {
                        if (tunRxIcmp6Na == 0L) {
                            emitDiagnostic("tun rx first icmp6 NA (type=136) bytes=$length")
                        }
                        tunRxIcmp6Na++
                    }
                }

                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                if (ipv6.size != tunPacket.size) {
                    if (tunPatchedNaCount == 0L) {
                        Log.i(TAG, "ncm added target-link-layer option to first neighbor advertisement")
                        emitDiagnostic("tun added target-link-layer option to first NA (originalBytes=$length patchedBytes=${ipv6.size})")
                    }
                    tunPatchedNaCount++
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    Log.i(TAG, "ncm outbound ${ipv6.summary(0)}")
                }
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    ncmTxDroppedNoPeerMac++
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        Log.i(TAG, "ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                        emitDiagnostic("ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                    }
                    continue
                }
                if (!loggedOutbound) {
                    loggedOutbound = true
                    Log.i(
                        TAG,
                        "ncm first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}",
                    )
                    emitDiagnostic("ncm first outbound frame bytes=$length dest=${if (multicastMac != null) "multicast" else "unicast"}")
                }
                val frame = EthernetIpv6Codec.build(hostMac, mac, ipv6)
                ncmTxAttemptFrames++
                ncmTxAttemptBytes += frame.size
                val sent = ncm.send(frame, WRITE_TIMEOUT_MILLIS)
                if (sent) {
                    ncmTxCompletedFrames++
                    ncmTxCompletedBytes += frame.size
                } else {
                    ncmTxDroppedNotReady++
                }
                checkPeriodicDiagnostics()
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.summary(offset: Int): String {
        val payloadBytes = size - offset
        if (payloadBytes < 40) return "truncated bytes=$payloadBytes"
        val source = InetAddress.getByAddress(copyOfRange(offset + 8, offset + 24)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(offset + 24, offset + 40)).hostAddress
        val nextHeader = this[offset + 6].toInt() and 0xff
        val detail = when {
            nextHeader == 6 && payloadBytes >= 44 -> " tcp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 17 && payloadBytes >= 44 -> " udp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 58 && payloadBytes >= 41 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
            else -> ""
        }
        return "bytes=$payloadBytes src=$source dst=$destination next=$nextHeader$detail"
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        const val PERIODIC_DIAGNOSTIC_INTERVAL_NANOS = 10_000_000_000L
    }
}
