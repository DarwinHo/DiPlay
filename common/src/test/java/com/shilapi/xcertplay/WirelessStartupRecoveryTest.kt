package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.transport.BluetoothLinkCheck
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupRecoveryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private lateinit var pausedTeardown: PausedExecutorService
    private val failure = CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)

        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val sizeCtor = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        ReflectionHelpers.setField(activity, "activeDisplaySize", sizeCtor.newInstance(800, 480))

        (ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor")).shutdownNow()
        pausedTeardown = PausedExecutorService()
        ReflectionHelpers.setField(activity, "teardownExecutor", pausedTeardown)

        retry = Button(activity).apply {
            visibility = View.GONE
            setOnClickListener { activity.performManualStartupRetry() }
        }
        ReflectionHelpers.setField(activity, "startupRetryButton", retry)
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        (ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor")).shutdownNow()
        (ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor")).shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    private fun report(generation: Int = 0, status: CarPlayStatus = failure) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }
    private fun budget() = ReflectionHelpers.getField<WirelessStartupRetryBudget>(activity, "startupRetryBudget")

    private fun createLegacyChannelOutcomeUnknown(message: String, cause: Throwable? = null): Throwable {
        val targetClass = Class.forName("com.shilapi.xcertplay.network.LegacyChannelOutcomeUnknown")
        val constructor = targetClass.getDeclaredConstructor(String::class.java, Throwable::class.java)
            .apply { isAccessible = true }
        return constructor.newInstance(message, cause) as Throwable
    }

    private fun createP2pCreateRejected(reason: Int, message: String): Throwable {
        val targetClass = Class.forName("com.shilapi.xcertplay.network.P2pCreateRejected")
        val constructor = targetClass.getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
        return constructor.newInstance(reason, message) as Throwable
    }

    @Test fun duplicateFailureAndCleanupErrorScheduleOnlyOneRetry() {
        report()
        report()
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(1, budget().retries)
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun exhaustedBudgetStopsAutomaticRecoveryAndExposesRetry() {
        repeat(5) { round ->
            val currentGen = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
            report(currentGen)

            val pending = ReflectionHelpers.getField<Any?>(activity, "pendingReconnect")
            assertNotNull("Round $round must create pendingReconnect", pending)
            assertEquals("Retry count must match consumed round", round + 1, budget().retries)

            val runnable = ReflectionHelpers.getField<Runnable>(pending!!, "runnable")
            ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacks(runnable)
            runnable.run()

            pausedTeardown.runAll()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("Generation must genuinely advance after restart", currentGen + 1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
            assertFalse("handshakeResetInProgress must clear after teardown", ReflectionHelpers.getField<Boolean>(activity, "handshakeResetInProgress"))
            assertNull("pendingReconnect must be consumed", ReflectionHelpers.getField<Any?>(activity, "pendingReconnect"))
            assertFalse("reconnectScheduled must be reset", ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))
        }

        val finalGen = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        report(finalGen)

        assertEquals(5, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "pendingReconnect"))
        assertEquals(View.VISIBLE, retry.visibility)

        report(finalGen, CarPlayStatus.Failed("Bluetooth socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun unrecoverableConfigurationDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Permission missing", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // No further attempt can make a head unit's Bluetooth reach the phone.
    @Test fun bluetoothThatReachesNoPhoneDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Android Bluetooth on this head unit reports connections that are not real",
            startupFailure = WirelessStartupFailure.BLUETOOTH_UNUSABLE))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // Issue 709: every connect fails inside Android. The iPhone is not what is missing, and no retry helps.
    @Test fun bluetoothThatCannotOpenConnectionsSaysSoAndDoesNotRetry() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val failure = BluetoothLinkCheck.connectFailure(NullPointerException("Attempt to invoke interface method " +
            "'android.os.ParcelFileDescriptor android.bluetooth.IBluetoothSocketManager.connectSocket(" +
            "android.bluetooth.BluetoothDevice, int, android.os.ParcelUuid, int, int)' on a null object reference"),
            "phone-address") as WirelessStartupException
        report(status = CarPlayStatus.Failed(failure.message!!, startupFailure = failure.reason))
        assertEquals(activity.getString(R.string.bluetooth_cannot_open_connections), stage.text.toString())
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // A head unit whose Android has no Bluetooth adapter: nothing to turn on and nothing to wait for.
    @Test fun aHeadUnitWithoutABluetoothAdapterSaysSoAndDoesNotRetry() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val failure = BluetoothRadioCheck.failure(BluetoothRadioCheck.NO_ADAPTER) as WirelessStartupException
        report(status = CarPlayStatus.Failed(failure.message!!, startupFailure = failure.reason))
        assertEquals(activity.getString(R.string.bluetooth_cannot_open_connections), stage.text.toString())
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // The driver can switch Bluetooth on, so the screen asks for it and the attempts go on.
    @Test fun bluetoothThatIsOffAsksForItAndKeepsRetrying() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(status = CarPlayStatus.Failed(BluetoothRadioCheck.RADIO_OFF))
        assertEquals(activity.getString(R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect), stage.text.toString())
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertFalse(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.GONE, retry.visibility)
    }

    @Test fun staleGenerationUserDisconnectMenuAndDifferentOwnerCannotSchedule() {
        report(1)
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", false)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report()
        assertEquals(0, budget().retries)
    }

    @Test fun p2pChannelUnresponsiveStopsAutomaticRetryAndExposesManualRetry() {
        val initialGeneration = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        report(status = CarPlayStatus.Failed("Could not establish WIFI_P2P hotspot: Wi-Fi Direct channel selection did not respond",
            startupFailure = WirelessStartupFailure.P2P_CHANNEL_UNRESPONSIVE))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        assertFalse("Must not schedule reconnect after advancing time", ReflectionHelpers.getField(activity, "reconnectScheduled"))

        retry.performClick()
        assertFalse("Manual retry resets stopped state", ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.GONE, retry.visibility)
        assertEquals("restartGeneration must increment on manual retry", initialGeneration + 1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertTrue("handshakeResetInProgress must be set", ReflectionHelpers.getField<Boolean>(activity, "handshakeResetInProgress"))

        pausedTeardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("handshakeResetInProgress must clear after teardown completes", ReflectionHelpers.getField<Boolean>(activity, "handshakeResetInProgress"))
    }

    @Test fun p2pUnsupportedStopsAutomaticRetryAndExposesManualRetry() {
        val initialGeneration = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        report(status = CarPlayStatus.Failed("Could not establish WIFI_P2P hotspot: Wi-Fi P2P createGroup failed: P2P_UNSUPPORTED (code=1)",
            startupFailure = WirelessStartupFailure.P2P_UNSUPPORTED))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        assertFalse("Must not schedule reconnect after advancing time", ReflectionHelpers.getField(activity, "reconnectScheduled"))

        retry.performClick()
        assertFalse("Manual retry resets stopped state", ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.GONE, retry.visibility)
        assertEquals("restartGeneration must increment on manual retry", initialGeneration + 1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertTrue("handshakeResetInProgress must be set", ReflectionHelpers.getField<Boolean>(activity, "handshakeResetInProgress"))

        pausedTeardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("handshakeResetInProgress must clear after teardown completes", ReflectionHelpers.getField<Boolean>(activity, "handshakeResetInProgress"))
    }

    @Test fun p2pBusyFailureKeepsRetryingWithoutMisclassification() {
        report(status = CarPlayStatus.Failed("Wi-Fi Direct busy", startupFailure = null))
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertFalse(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.GONE, retry.visibility)
    }

    @Test fun fullEndToEndPipelineFromHotspotExceptionToHostStopsAutomaticRecovery() {
        val initialGeneration = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        val reporter = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, initialGeneration))

        val nestedException = IOException(
            "Could not establish WIFI_P2P hotspot: Wi-Fi Direct channel selection did not respond",
            createLegacyChannelOutcomeUnknown("Wi-Fi Direct channel selection did not respond"),
        )
        val classified = com.shilapi.xcertplay.network.WirelessStartupPolicy.classify(nestedException)
        assertEquals(WirelessStartupFailure.P2P_CHANNEL_UNRESPONSIVE, classified)

        reporter(CarPlayStatus.Failed(nestedException.message!!, startupFailure = classified))
        assertEquals("Automatic retry budget must not be consumed", 0, budget().retries)
        assertFalse("Automatic reconnect must not be scheduled", ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue("Startup retry must be marked stopped", ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        assertFalse("Advancing time must not trigger automatic reconnect", ReflectionHelpers.getField(activity, "reconnectScheduled"))

        retry.performClick()
        assertFalse(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.GONE, retry.visibility)
        pausedTeardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
