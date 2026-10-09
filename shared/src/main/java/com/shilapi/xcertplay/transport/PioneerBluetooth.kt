package com.shilapi.xcertplay.transport

import android.os.Build
import android.os.IBinder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Only the byte-for-byte firmware whose Binder protocol was inspected is supported. */
object PioneerBluetooth {
    private const val LIB_HASH = "7143ccf70ebb575cbec561bc25964fddc806ed3cbe913e98a04206bf48c6658e"
    private const val DAEMON_HASH = "ad0ddb88bd5a8f62b7fa012cb6fded2c28a7d3caaf47dc822b6ac83ea4e58642"
    private const val ADAPTER_CLASS = "jp.pioneer.ceam.bluetooth.BluetoothAdapter"

    fun platformPresent(): Boolean = Build.VERSION.SDK_INT == 25 && File("/system/lib/libblueforce.so").isFile

    internal fun verifyFirmware() {
        if (!platformPresent() || hash(File("/system/lib/libblueforce.so")) != LIB_HASH ||
            hash(File("/system/bin/blueforcemanage")) != DAEMON_HASH) {
            throw IOException("Pioneer Bluetooth firmware is not supported; no private transaction sent")
        }
    }

    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    internal data class Target(val address: String, val name: String, val handler: Int, val localAddress: String)

    data class PairedDevice(val address: String, val name: String)

    /** Read-only factory pairing list for the phone picker; uses the same firmware gate. */
    fun pairedDevices(): List<PairedDevice> = readTargets().map { target ->
        if (!validMac(target.address)) throw IOException("Pioneer paired-device address is unavailable")
        PairedDevice(target.address, target.name)
    }

    internal fun selectTarget(selectedAddress: String?): Target {
        val candidates = readTargets()
        val matching = if (selectedAddress != null) candidates.filter { it.address.equals(selectedAddress, true) }
            else candidates.filter { it.name.contains("iPhone", true) }
        val target = matching.singleOrNull()
            ?: throw IOException("Pioneer Bluetooth needs one paired iPhone; connect only that iPhone and retry")
        if (target.handler < 0 || !validMac(target.address) || !validMac(target.localAddress)) {
            throw IOException("Pioneer Bluetooth device identity is unavailable")
        }
        return target
    }

    private fun readTargets(): List<Target> {
        verifyFirmware()
        try {
            val type = Class.forName(ADAPTER_CLASS)
            val adapter = type.getMethod("getDefaultAdapter").invoke(null)
                ?: throw IOException("Pioneer Bluetooth adapter is unavailable")
            // Read-only getters only. Do not enable Bluetooth, pair, clear memory or change profiles.
            val local = type.getMethod("getAddress").invoke(adapter) as? String
            val devices = type.getMethod("getBondedDevices").invoke(adapter) as? List<*>
                ?: throw IOException("Pioneer Bluetooth paired-device list is unavailable")
            return devices.filterNotNull().map { device ->
                val cls = device.javaClass
                Target(
                    cls.getMethod("getAddress").invoke(device) as String,
                    cls.getMethod("getName").invoke(device) as? String ?: "",
                    (cls.getMethod("getHandler").invoke(device) as? Number)?.toInt() ?: -1,
                    local ?: "",
                )
            }
        } catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException("Could not read Pioneer Bluetooth device information", error) }
        catch (error: LinkageError) { throw IOException("Pioneer Bluetooth platform classes are unavailable", error) }
    }

    private fun validMac(value: String): Boolean = value.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) &&
        value != "00:00:00:00:00:00" && value != "02:00:00:00:00:00"

    internal fun createStream(target: Target, cancelled: () -> Boolean, log: (String) -> Unit): PioneerSppDuplexStream {
        verifyFirmware()
        val service = try {
            Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
                .invoke(null, PioneerBlueforcePort.SERVICE) as? IBinder
        } catch (error: Exception) { throw IOException("Pioneer Bluetooth service is inaccessible", error) }
            ?: throw IOException("Pioneer Bluetooth service is unavailable")
        if (service.interfaceDescriptor != PioneerBlueforcePort.SERVICE) {
            throw IOException("Pioneer Bluetooth service descriptor mismatch")
        }
        return PioneerSppDuplexStream(PioneerBlueforcePort(service, target.handler, cancelled, log))
    }
}
