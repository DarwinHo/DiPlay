package com.shilapi.xcertplay.transport

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.io.IOException

/** Verified ARM32 Blueforce wire protocol; intentionally no adapter/profile-setting operations. */
internal class PioneerBlueforcePort(
    private val service: IBinder,
    private val deviceHandler: Int,
    private val cancelled: () -> Boolean,
    private val log: (String) -> Unit,
) : PioneerSppPort {
    private val lock = Object()
    private var port = -1
    private var registered = false
    @Volatile private var closed = false
    private var requestedConnection = false
    private var result: Int? = null
    private var failure: IOException? = null
    private var onBytes: (ByteArray) -> Unit = {}
    private var onFailure: (IOException) -> Unit = {}
    private val memory = PioneerSharedMemory()
    private val death = IBinder.DeathRecipient { reportFailure(IOException("Pioneer Bluetooth service stopped")) }

    private val callback = object : Binder() {
        init { attachInterface(null, NOTIFY) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(NOTIFY); return true }
            if (code !in 1..17) return super.onTransact(code, data, reply, flags)
            try {
                data.enforceInterface(NOTIFY)
                if (closed) return true
                when (code) {
                    5, 16 -> {
                        val handler = data.readInt(); val spp = data.readInt()
                        if (handler != deviceHandler || spp != port) return true
                        val bytes = if (code == 5) memory.read(data.readStrongBinder()
                            ?: throw IOException("Pioneer SPP received a null memory handle"))
                        else PioneerParcel.readRaw(data, data.readInt(), 65536)
                        onBytes(bytes)
                    }
                    17 -> {
                        val spp = data.readInt(); val value = data.readInt()
                        if (spp == port) synchronized(lock) { result = value; lock.notifyAll() }
                    }
                    // Other notifications belong to phone/audio/profile services. Acknowledge only.
                    else -> Unit
                }
            } catch (error: Exception) { reportFailure(IOException("Pioneer Bluetooth callback failed", error)) }
            return true
        }
    }

    override fun subscribe(onBytes: (ByteArray) -> Unit, onFailure: (IOException) -> Unit) {
        this.onBytes = onBytes; this.onFailure = onFailure
        // UUID table entry 0 is 00000000DECAFADEDECADEAFDECACAFE in the pinned daemon.
        port = call(0x4e, { writeInt(deviceHandler); writeInt(0) }) { readInt() }
        if (port !in 0..8) throw IOException("Pioneer Bluetooth iAP2 port is unavailable")
        service.linkToDeath(death, 0)
        try {
            // Registration is scoped to this PID, this callback and iAP2 UUID slot 0.
            call(1, { writeInt(Process.myPid()); writeStrongBinder(callback); writeInt(0) }) { Unit }
            registered = true
            log("Pioneer Bluetooth callback ready; firmware verified; iAP2 port=$port")
        } catch (error: Exception) { service.unlinkToDeath(death, 0); throw error }
    }

    override fun connect() {
        // Do not take over a pre-existing SPP link belonging to another application.
        val state = call(0x86, { writeInt(port) }) { readInt() }
        if (state != 0) throw IOException("Pioneer iAP2 port is busy (state=$state); existing connection preserved")
        synchronized(lock) {
            result = null
            // Serialize the request with close: cancellation must not start a link after cleanup.
            checkCancelled()
            requestedConnection = true
            call(0x85, { writeInt(port) }) { Unit }
        }
        val started = System.nanoTime()
        synchronized(lock) {
            while (result == null) {
                checkCancelled()
                failure?.let { throw it }
                if ((System.nanoTime() - started) / 1_000_000 >= 20000) {
                    throw IOException("Pioneer Bluetooth iAP2 connection timed out")
                }
                lock.wait(100)
            }
            if (result != 1) throw IOException("Pioneer Bluetooth iAP2 connection rejected (result=$result)")
        }
        log("Pioneer Bluetooth iAP2 connection confirmed")
    }

    override fun write(bytes: ByteArray) {
        checkCancelled()
        if (bytes.isEmpty()) return
        val handle = memory.create(bytes)
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE); data.writeInt(port); data.writeStrongBinder(handle)
            // Native BpBlueforce uses a one-way IMemory send, with no Java exception header.
            if (!service.transact(0x88, data, null, IBinder.FLAG_ONEWAY)) throw IOException("Pioneer SPP send rejected")
        } finally { data.recycle() }
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; lock.notifyAll() }
        // Only this app's requested SPP connection. Never disconnect A2DP/HFP or the phone.
        if (requestedConnection) runCatching { call(0x87, { writeInt(port) }) { Unit } }
        if (registered) runCatching {
            call(2, { writeInt(Process.myPid()); writeStrongBinder(callback); writeInt(0) }) { Unit }
        }
        service.unlinkToDeath(death, 0)
        memory.close()
    }

    private fun reportFailure(error: IOException) {
        synchronized(lock) { if (failure == null) failure = error; lock.notifyAll() }
        onFailure(error)
    }

    private fun checkCancelled() {
        if (closed || cancelled() || Thread.currentThread().isInterrupted) throw IOException("Pioneer Bluetooth connection cancelled")
    }

    internal fun <T> call(code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE); data.write()
            if (!service.transact(code, data, reply, 0)) throw IOException("Pioneer Bluetooth transaction unavailable: $code")
            if (reply.dataAvail() < 4) throw IOException("Pioneer Bluetooth reply truncated: $code")
            val status = reply.readInt() // native status_t, NOT readException()
            if (status != 0) throw IOException("Pioneer Bluetooth transaction $code failed: $status")
            return reply.read()
        } finally { reply.recycle(); data.recycle() }
    }

    companion object {
        const val SERVICE = "jp.pioneer.ceam.Blueforce.manage"
        const val NOTIFY = "jp.pioneer.ceam.Bluenotify.manage"
    }
}

internal object PioneerParcel {
    fun readRaw(parcel: Parcel, count: Int, limit: Int): ByteArray {
        if (count !in 0..limit || count > parcel.dataAvail()) throw IOException("Invalid Pioneer Bluetooth payload length")
        val start = parcel.dataPosition()
        val padded = (count + 3) and -4
        if (padded > parcel.dataAvail()) throw IOException("Truncated Pioneer Bluetooth payload")
        val bytes = parcel.marshall().copyOfRange(start, start + count)
        parcel.setDataPosition(start + padded)
        return bytes
    }
}
