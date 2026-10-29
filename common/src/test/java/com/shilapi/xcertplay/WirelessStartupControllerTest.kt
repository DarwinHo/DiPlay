package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupControllerTest {
    private val statuses = mutableListOf<CarPlayStatus>()

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
    }
    private fun controller(pioneerBluetooth: Boolean = false) = CarPlayController(
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        },
        CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            transport = CarPlayTransport.WIRELESS,
            pioneerBluetoothEnabled = pioneerBluetooth,
            identification = Iap2IdentificationConfig(
                name = "test",
                modelIdentifier = "test",
                manufacturer = "test",
                serialNumber = "test",
                firmwareVersion = "1",
                hardwareVersion = "1",
                carPlayUsbInterfaceNumber = 3,
            ),
        ),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
        PairingStore(),
        object : AirPlaySessionListener {},
        object : AirPlayMediaHandler {},
        statuses::add,
    )

    private fun invokeRunWireless(controller: CarPlayController) {
        val phaseEnum = Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController\$Phase")
        val wirelessPhase = phaseEnum.enumConstants.first { (it as Enum<*>).name == "WIRELESS" }
        ReflectionHelpers.setField(controller, "phase", wirelessPhase)
        val dummyAuthenticator = object : com.shilapi.xcertplay.mfi.MfiAuthenticator {
            override fun protocolMajor(): Int = 2
            override fun readCertificate(maximumOutputLength: Int): ByteArray = ByteArray(0)
            override fun signChallenge(challenge: ByteArray): ByteArray = ByteArray(0)
        }
        ReflectionHelpers.setField(controller, "mfiSession", MfiSession(dummyAuthenticator, null))
        val runWirelessMethod = controller.javaClass.getDeclaredMethod("runWireless", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        runWirelessMethod.invoke(controller, 0)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createPioneerTarget(
        address: String = "12:34:56:78:9A:BC",
        name: String = "iPhone",
        handler: Int = 1,
        localAddress: String = "00:11:22:33:44:55",
    ): Any {
        val targetClass = Class.forName("com.shilapi.xcertplay.transport.PioneerBluetooth\$Target")
        val constructor = targetClass.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
        ).apply { isAccessible = true }
        return constructor.newInstance(address, name, handler, localAddress)
    }

    private fun fail(controller: CarPlayController, generation: Int, error: Throwable) {
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, error, generation)
    }

    @Test fun timeoutAndCleanupExceptionProduceOnlyOneTypedFailure() {
        val controller = controller()
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            fail(controller, 0, IOException("Bluetooth socket closed"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, statuses.size)
            assertEquals(WirelessStartupFailure.FIRST_TCP_TIMEOUT, (statuses.single() as CarPlayStatus.Failed).startupFailure)
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun queuedOldFailureAndOldCleanupDoNotAffectNewGeneration() {
        val controller = controller()
        var closed = 0
        val hotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("Not started")
            override fun close() { closed++ }
        }
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
            ReflectionHelpers.setField(controller, "hotspot", hotspot)
            controller.javaClass.getDeclaredMethod("closeWirelessStack", CarPlayVpnService::class.java, Int::class.javaObjectType)
                .apply { isAccessible = true }.invoke(controller, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
            assertEquals(0, closed)
            assertSame(hotspot, ReflectionHelpers.getField(controller, "hotspot"))
            fail(controller, 0, IOException("late old failure"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun pioneerBackendWithNativeBluetoothOffIsNotBlockedByStandardBluetoothGate() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null) shadowOf(adapter).setEnabled(false)
        val controller = controller(pioneerBluetooth = true)
        var hotspotCreated = 0
        var hotspotStarted = 0
        val mockHotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo {
                hotspotStarted++
                throw IOException("Hotspot check reached")
            }
            override fun close() = Unit
        }
        ReflectionHelpers.setField(controller, "hotspotManagerFactory", { _: WirelessHotspotMode, _: Int ->
            hotspotCreated++
            mockHotspot
        })
        val target = createPioneerTarget()
        ReflectionHelpers.setField(controller, "pioneerTargetSelector", { _: String? -> target })
        try {
            invokeRunWireless(controller)
            assertEquals("Expected hotspot to be created via factory", 1, hotspotCreated)
            assertEquals("Expected startup to proceed past Bluetooth check to start hotspot", 1, hotspotStarted)
            val failures = statuses.filterIsInstance<CarPlayStatus.Failed>()
            assertFalse(
                "Pioneer backend should not be blocked by native Bluetooth off",
                failures.any { it.message.contains(BluetoothRadioCheck.RADIO_OFF_MARK) },
            )
        } finally {
            controller.close()
            controller.awaitClosed(2_000)
        }
    }

    @Test fun standardBackendWithNativeBluetoothOffIsBlockedWithRadioOff() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null) shadowOf(adapter).setEnabled(false)
        val controller = controller(pioneerBluetooth = false)
        var hotspotCreated = 0
        var hotspotStarted = 0
        val mockHotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo {
                hotspotStarted++
                throw IOException("Hotspot check reached")
            }
            override fun close() = Unit
        }
        ReflectionHelpers.setField(controller, "hotspotManagerFactory", { _: WirelessHotspotMode, _: Int ->
            hotspotCreated++
            mockHotspot
        })
        try {
            invokeRunWireless(controller)
            assertEquals("Hotspot manager must not be created when standard Bluetooth is off", 0, hotspotCreated)
            assertEquals("Hotspot must not be started when standard Bluetooth is off", 0, hotspotStarted)
            val failure = statuses.filterIsInstance<CarPlayStatus.Failed>().single()
            assertTrue(
                "Standard backend should block when Bluetooth is off",
                failure.message.contains(BluetoothRadioCheck.RADIO_OFF_MARK),
            )
        } finally {
            controller.close()
            controller.awaitClosed(2_000)
        }
    }

    @Test fun pioneerBackendUnavailableReportsExplicitFailureWithoutSilentFallback() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null) shadowOf(adapter).setEnabled(true)
        val controller = controller(pioneerBluetooth = true)
        var hotspotCreated = 0
        var hotspotStarted = 0
        val mockHotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo {
                hotspotStarted++
                throw IOException("Hotspot check reached")
            }
            override fun close() = Unit
        }
        ReflectionHelpers.setField(controller, "hotspotManagerFactory", { _: WirelessHotspotMode, _: Int ->
            hotspotCreated++
            mockHotspot
        })
        ReflectionHelpers.setField(controller, "pioneerTargetSelector", { _: String? ->
            throw IOException("Pioneer Bluetooth adapter is unavailable")
        })
        try {
            invokeRunWireless(controller)
            assertEquals("Hotspot manager must not be created when Pioneer Bluetooth is unavailable", 0, hotspotCreated)
            assertEquals("Hotspot must not be started when Pioneer Bluetooth is unavailable", 0, hotspotStarted)
            val failure = statuses.filterIsInstance<CarPlayStatus.Failed>().single()
            assertEquals("Pioneer Bluetooth adapter is unavailable", failure.message)
            assertFalse(
                "Must not silently fall back to standard Bluetooth radio message",
                failure.message.contains(BluetoothRadioCheck.RADIO_OFF_MARK),
            )
        } finally {
            controller.close()
            controller.awaitClosed(2_000)
        }
    }
}
