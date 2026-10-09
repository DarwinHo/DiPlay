# Pioneer Bluetooth adapter (experimental)

The opt-in setting is under Advanced and applies at the next connection. It defaults to off.
This is an implementation for one inspected Android 7.1.2 Pioneer firmware, not proof of
vehicle compatibility. No live connection, APK installation or reboot was performed to
validate it. The earlier boot-logo hang and sound remain unexplained.

The Android BluetoothAdapter on this vehicle reports OFF while the factory Bluetooth stack
operates independently. The adapter selects a paired iPhone through the optional Pioneer
platform library, uses its real local Bluetooth address, and opens the private Blueforce
SPP byte stream for DiPlay's existing iAP2 bootstrap. Normal Android RFCOMM is unchanged
when this option is off. There is no silent fallback or adapter/profile-setting transaction.

## Firmware and protocol evidence

Read-only copies inspected on 2026-10-09:

- `/system/lib/libblueforce.so`, SHA-256
  `7143ccf70ebb575cbec561bc25964fddc806ed3cbe913e98a04206bf48c6658e`
- `/system/bin/blueforcemanage`, SHA-256
  `ad0ddb88bd5a8f62b7fa012cb6fded2c28a7d3caaf47dc822b6ac83ea4e58642`

Both hashes and API 25 are checked before private transactions. No vendor binary is bundled.
The native client `BpBlueforce` vtable starts at 0x32148 in the inspected library:

| Operation | Native implementation | Transaction | Wire fields after interface token |
| --- | --- | --- | --- |
| register / unregister | 0x134f0 / 0x13680 | 1 / 2 | PID, callback Binder, UUID index |
| deviceGetSerialPort | 0x184b0 | 0x4e | device handler, UUID index; reply status, port |
| start / status / disconnect | 0x1c1f0 / 0x1c2fc / 0x1c418 | 0x85 / 0x86 / 0x87 | port; reply native status, optional value |
| write | 0x1c524 | 0x88, one-way | port, IMemory Binder |

`CBlueforceManage::serialportWriteData` at 0x264e8 restricts ports to 0..8 and
writes to 4096-byte MemoryHeapBase regions. `BnBluenotify::onTransact` at 0x286b0:
callback 5 carries two integers and IMemory; callback 16 carries two integers, byte count,
and raw padded bytes; callback 17 carries port and connection result. At 0x2b310,
`syncSppConnectionResult` treats 1 as successful and 0 as unsuccessful.

The daemon initializer at 0x10a24 puts UUID `00000000DECAFADEDECADEAFDECACAFE`
in slot 0 of the table at 0x22b168; this matches DiPlay's iPhone iAP2 UUID.
The meaning of device/port callback fields and disconnected state are derived from the
native client/daemon and still require device acceptance testing. Unknown/busy state is
rejected; existing SPP connections are not adopted or intentionally disconnected.

IMemory and IMemoryHeap serialization follows Android 7.1.2 AOSP
[IMemory.cpp](https://android.googlesource.com/platform/frameworks/native/+/android-7.1.2_r39/libs/binder/IMemory.cpp).
JNI only copies bounded, read-only shared memory in the app process; it does not load a
private system library. Sends retain heap objects through remote Binder references, with
a bounded outstanding allocation count; all reachable allocations close with the stream.

## Validation boundaries

Unit tests exercise stream byte ordering, packet splitting, overflow, failure propagation,
cancellation, Binder reply/callback layouts and preserving a busy port. They use fake
ports/services; they do not establish permission access, real firmware callback semantics,
iPhone handshake, Wi-Fi handoff, projection, audio, or vehicle boot stability.

Before enabling on the vehicle, review a successful CI build and use manual app startup
for the first connection test. This option does not enable system Bluetooth, grant VPN
permission, alter phone pairing, change hotspots, or set boot startup preferences. Starting
DiPlay can still use its separately configured hotspot/VPN/session behavior.
