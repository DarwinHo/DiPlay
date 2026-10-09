package com.shilapi.xcertplay.transport

import android.os.Binder
import android.os.IBinder
import android.os.MemoryFile
import android.os.Parcel
import java.io.FileDescriptor
import java.io.IOException
import java.lang.ref.WeakReference

/** AOSP Android 7 IMemory protocol; never loads the car's private native libraries. */
internal class PioneerSharedMemory {
    private val heaps = mutableListOf<WeakReference<Heap>>()
    private var closed = false

    @Synchronized fun create(bytes: ByteArray): IBinder {
        if (closed) throw IOException("Pioneer shared memory is closed")
        require(bytes.size in 1..4096)
        heaps.removeAll { it.get() == null }
        if (heaps.size >= 256) throw IOException("Pioneer shared-memory send backlog exceeded 1 MiB")
        val file = MemoryFile("DiPlay-iAP2", 4096)
        try {
            file.writeBytes(bytes, 0, 0, bytes.size)
            val fd = MemoryFile::class.java.getMethod("getFileDescriptor").invoke(file) as FileDescriptor
            val heap = Heap(file, fd)
            heaps.add(WeakReference(heap))
            return object : Binder() {
                init { attachInterface(null, MEMORY) }
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(MEMORY); return true }
                    if (code != 1 || reply == null) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(MEMORY)
                    reply.writeStrongBinder(heap); reply.writeInt(0); reply.writeInt(bytes.size)
                    return true
                }
            }
        } catch (error: Exception) { file.close(); throw IOException("Pioneer SPP shared-memory allocation failed", error) }
    }

    private class Heap(private val file: MemoryFile, private val fd: FileDescriptor) : Binder() {
        init { attachInterface(null, HEAP) }
        @Synchronized override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(HEAP); return true }
            if (code != 1 || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(HEAP)
            reply.writeFileDescriptor(fd); reply.writeInt(4096); reply.writeInt(1); reply.writeInt(0)
            return true
        }
        @Synchronized fun close() = file.close()
        // MemoryFile itself finalizes its fd when the remote IMemory/heap Binder references end.
    }

    fun read(memory: IBinder): ByteArray = transaction(memory, MEMORY) {
        val heap = readStrongBinder() ?: throw IOException("Pioneer memory heap is unavailable")
        val offset = readInt(); val length = readInt()
        if (offset < 0 || length !in 1..65536) throw IOException("Invalid Pioneer memory region")
        transaction(heap, HEAP) {
            val descriptor = readFileDescriptor() ?: throw IOException("Pioneer memory fd is unavailable")
            descriptor.use {
                val heapSize = readInt(); readInt(); val heapOffset = readInt()
                if (heapOffset != 0 || heapSize !in 1..1048576 || offset > heapSize - length) {
                    throw IOException("Invalid Pioneer memory heap bounds")
                }
                PioneerMemoryCopy.copy(it.fd, heapSize, offset, length)
            }
        }
    }

    private fun <T> transaction(binder: IBinder, descriptor: String, read: Parcel.() -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            if (!binder.transact(1, data, reply, 0)) throw IOException("Pioneer shared-memory query failed")
            return reply.read()
        } finally { reply.recycle(); data.recycle() }
    }

    @Synchronized fun close() {
        closed = true
        heaps.forEach { it.get()?.close() }; heaps.clear()
    }

    companion object {
        private const val MEMORY = "android.utils.IMemory"
        private const val HEAP = "android.utils.IMemoryHeap"
    }
}

internal object PioneerMemoryCopy {
    private val loadFailure: Throwable? by lazy {
        try { System.loadLibrary("pioneer_memory"); null }
        catch (error: LinkageError) { error }
        catch (error: SecurityException) { error }
    }
    fun copy(fd: Int, heapSize: Int, offset: Int, count: Int): ByteArray {
        loadFailure?.let { throw IOException("Pioneer memory helper is unavailable", it) }
        return nativeCopy(fd, heapSize, offset, count)
    }
    private external fun nativeCopy(fd: Int, heapSize: Int, offset: Int, count: Int): ByteArray
}
