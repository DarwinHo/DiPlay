package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.ArrayDeque

/** SPP callbacks are Binder threads: never block them on the iAP2 consumer. */
internal interface PioneerSppPort {
    fun subscribe(onBytes: (ByteArray) -> Unit, onFailure: (IOException) -> Unit)
    fun connect()
    fun write(bytes: ByteArray)
    fun close()
}

internal class PioneerSppDuplexStream(private val port: PioneerSppPort) : BlockingDuplexByteStream {
    private val lock = Object()
    private val sendLock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var closed = false
    private var failure: IOException? = null

    init {
        try {
            port.subscribe(::receive, ::fail)
        } catch (error: Exception) {
            runCatching { port.close() }
            throw IOException("Pioneer Bluetooth callback registration failed", error)
        }
    }

    fun connect() {
        try { port.connect() } catch (error: Exception) {
            close()
            throw IOException("Pioneer Bluetooth iAP2 connection failed", error)
        }
    }

    override fun send(data: ByteArray) = synchronized(sendLock) {
        synchronized(lock) { checkOpen() }
        try {
            // The verified native client accepts at most 4096 bytes per shared-memory send.
            for (offset in data.indices step 4096) {
                synchronized(lock) { checkOpen() }
                port.write(data.copyOfRange(offset, minOf(data.size, offset + 4096)))
            }
        } catch (error: Exception) {
            val io = IOException("Pioneer Bluetooth send failed", error)
            fail(io)
            throw io
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0)
        require(timeoutMillis >= 0)
        val started = System.nanoTime()
        val timeoutNanos = timeoutMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000) * 1_000_000
        synchronized(lock) {
            while (true) {
                failure?.let { throw it }
                if (closed) return ByteArray(0)
                queue.pollFirst()?.let { bytes ->
                    val count = minOf(maxBytes, bytes.size)
                    queuedBytes -= count
                    if (count < bytes.size) queue.addFirst(bytes.copyOfRange(count, bytes.size))
                    return bytes.copyOf(count)
                }
                val remaining = timeoutNanos - (System.nanoTime() - started)
                if (remaining <= 0) return null
                try { lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt()) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
            }
        }
    }

    private fun receive(bytes: ByteArray) = synchronized(lock) {
        if (closed || failure != null || bytes.isEmpty()) return@synchronized
        if (bytes.size > MAX_PENDING_BYTES - queuedBytes) {
            failure = IOException("Pioneer Bluetooth receive buffer overflow")
            queue.clear(); queuedBytes = 0
        } else {
            queue.addLast(bytes.copyOf()); queuedBytes += bytes.size
        }
        lock.notifyAll()
    }

    private fun fail(error: IOException) = synchronized(lock) {
        if (!closed && failure == null) failure = error
        lock.notifyAll()
    }

    private fun checkOpen() {
        failure?.let { throw it }
        if (closed) throw IOException("Pioneer Bluetooth stream is closed")
    }

    override fun close() {
        val first = synchronized(lock) {
            if (closed) false else {
                closed = true; queue.clear(); queuedBytes = 0; lock.notifyAll(); true
            }
        }
        if (first) port.close()
    }

    companion object { private const val MAX_PENDING_BYTES = 65536 }
}
