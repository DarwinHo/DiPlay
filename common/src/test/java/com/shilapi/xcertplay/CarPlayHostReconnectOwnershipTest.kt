package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostReconnectOwnershipTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var mockController: CarPlayController
    private lateinit var retryButton: Button
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")

    private fun size(width: Int, height: Int): Any = sizeClass
        .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(width, height)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)

        // Establish proper host ownership and active display via reflection fixture
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        ReflectionHelpers.setField(activity, "activeDisplaySize", size(1280, 720))

        retryButton = Button(activity).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "startupRetryButton", retryButton)

        // Install deterministic PausedExecutorService for teardown synchronization
        (ReflectionHelpers.getField<Any>(activity, "teardownExecutor") as ExecutorService).shutdownNow()
        ReflectionHelpers.setField(activity, "teardownExecutor", PausedExecutorService())

        mockController = mock(CarPlayController::class.java)
        `when`(mockController.awaitClosed(anyLong())).thenReturn(true)
        ReflectionHelpers.setField(activity, "controller", mockController)
    }

    @After fun cleanup() {
        (ReflectionHelpers.getField<Any>(activity, "shuttingDown") as AtomicBoolean).set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(null)
        ReflectionHelpers.getField<CarPlayController?>(activity, "controller")?.let {
            CarPlayMediaKeys.detach(it)
        }
        (ReflectionHelpers.getField<Any?>(activity, "sink") as? AndroidMediaSink)?.close()
        (ReflectionHelpers.getField<Any>(activity, "teardownExecutor") as ExecutorService).shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        AirPlayPersistence.saveLocationReportingEnabled(activity, false)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
    }

    private fun statusReporter(generation: Int = 0): (CarPlayStatus) -> Unit =
        ReflectionHelpers.callInstanceMethod(
            activity,
            "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation),
        )

    private fun sessionListener(generation: Int = 0): AirPlaySessionListener =
        ReflectionHelpers.callInstanceMethod(
            activity,
            "createSessionListener",
            from(Int::class.javaPrimitiveType, generation),
        )

    private fun mainLooper() = shadowOf(Looper.getMainLooper())

    private fun drainTeardown() {
        (ReflectionHelpers.getField<Any>(activity, "teardownExecutor") as PausedExecutorService).runAll()
    }

    @Test fun earlyUsbStartupFailureScheduledReconnectIsCancelledBySessionActive() {
        val initialGeneration = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        val reporter = statusReporter(initialGeneration)
        val listener = sessionListener(initialGeneration)

        // 1. Early USB failure occurs during re-enumeration (earlyUsbFailure = true)
        reporter(CarPlayStatus.Failed("UsbManager could not open the iPhone", earlyUsbFailure = true))
        mainLooper().idle()

        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // 2. AirPlay session becomes active (confirmed recovery)
        val mockSession = mock(AirPlaySession::class.java)
        listener.onSessionActive(mockSession)
        mainLooper().idle()

        assertFalse("onSessionActive must cancel the early USB startup failure timer",
            ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // 3. Fast-forward past the 4000ms delay
        mainLooper().idleFor(Duration.ofMillis(4500))
        drainTeardown()

        // 4. Assert controller was NOT torn down and generation did not increment
        verify(mockController, never()).close()
        assertEquals(initialGeneration, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertSame(mockController, ReflectionHelpers.getField<CarPlayController?>(activity, "controller"))
    }

    @Test fun streamReconfigurationReconnectMustNotBeCancelledBySessionActive() {
        val initialGeneration = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        val listener = sessionListener(initialGeneration)

        // Schedule reconnect for intentional cluster stream reconfiguration
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "reconnectAfterLoss",
            from(String::class.java, "Cluster small-window navi on"),
            from(WirelessStartupFailure::class.java, null),
            from(String::class.java, "Cluster small-window navi on"),
            from(CarPlayHostActivity.ReconnectPurpose::class.java, CarPlayHostActivity.ReconnectPurpose.STREAM_RECONFIGURATION),
        )
        mainLooper().idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // Session active arrives
        val mockSession = mock(AirPlaySession::class.java)
        listener.onSessionActive(mockSession)
        mainLooper().idle()

        assertTrue("STREAM_RECONFIGURATION must NOT be cancelled by onSessionActive",
            ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // Advance looper to fire the reconfiguration restart
        mainLooper().idleFor(Duration.ofMillis(4500))
        drainTeardown()
        verify(mockController, times(1)).close()
        assertEquals(initialGeneration + 1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
    }

    @Test fun priorityUpgradePreservesStreamReconfigurationWhenArrivingDuringEarlyFailure() {
        val initialGeneration = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        val reporter = statusReporter(initialGeneration)
        val listener = sessionListener(initialGeneration)

        // 1. Early USB failure schedules reconnect
        reporter(CarPlayStatus.Failed("UsbManager could not open the iPhone", earlyUsbFailure = true))
        mainLooper().idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // 2. Setting / cluster stream reconfiguration arrives while failure reconnect is pending
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "reconnectAfterLoss",
            from(String::class.java, "Cluster small-window navi on"),
            from(WirelessStartupFailure::class.java, null),
            from(String::class.java, "Cluster small-window navi on"),
            from(CarPlayHostActivity.ReconnectPurpose::class.java, CarPlayHostActivity.ReconnectPurpose.STREAM_RECONFIGURATION),
        )
        mainLooper().idle()

        // 3. Controller recovers and activates session
        val mockSession = mock(AirPlaySession::class.java)
        listener.onSessionActive(mockSession)
        mainLooper().idle()

        // 4. Must NOT cancel because purpose was upgraded to STREAM_RECONFIGURATION!
        assertTrue("Upgraded STREAM_RECONFIGURATION must survive controller recovery",
            ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // 5. Advance looper; stream reconfiguration restart executes
        mainLooper().idleFor(Duration.ofMillis(4500))
        drainTeardown()
        verify(mockController, times(1)).close()
        assertEquals(initialGeneration + 1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
    }

    @Test fun subsequentRealChannelFailureUpgradesEarlyFailureToUncancelable() {
        val initialGeneration = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        val reporter = statusReporter(initialGeneration)
        val listener = sessionListener(initialGeneration)

        // 1. Early USB failure schedules reconnect
        reporter(CarPlayStatus.Failed("UsbManager could not open the iPhone", earlyUsbFailure = true))
        mainLooper().idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // 2. Real channel failure arrives in same generation (earlyUsbFailure = false)
        reporter(CarPlayStatus.Failed("Real channel transport failure", earlyUsbFailure = false))
        mainLooper().idle()

        // 3. Even if late session active arrives, it cannot cancel the upgraded GENERAL_FAILURE
        val mockSession = mock(AirPlaySession::class.java)
        listener.onSessionActive(mockSession)
        mainLooper().idle()

        assertTrue("Upgraded GENERAL_FAILURE must not be cancelled by onSessionActive",
            ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // 4. Timer fires and executes teardown
        mainLooper().idleFor(Duration.ofMillis(4500))
        drainTeardown()
        verify(mockController, times(1)).close()
        assertEquals(initialGeneration + 1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
    }

    @Test fun staleOlderGenerationProgressCannotCancelNewerGenerationTimer() {
        val gen0 = ReflectionHelpers.getField<Int>(activity, "restartGeneration")

        // Transition activity to generation 1
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "restartCarPlay", from(String::class.java, "test bump"))
        drainTeardown()
        val gen1 = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        assertEquals(gen0 + 1, gen1)

        val reporterGen1 = statusReporter(gen1)
        val listenerGen0 = sessionListener(gen0)
        val newController = mock(CarPlayController::class.java)
        `when`(newController.awaitClosed(anyLong())).thenReturn(true)
        ReflectionHelpers.setField(activity, "controller", newController)

        // Gen 1 schedules failure reconnect
        reporterGen1(CarPlayStatus.Failed("Gen 1 failure", earlyUsbFailure = true))
        mainLooper().idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // Stale callback from Gen 0 attempts to cancel
        val mockSession = mock(AirPlaySession::class.java)
        listenerGen0.onSessionActive(mockSession)
        mainLooper().idle()
        assertTrue("Stale Gen 0 progress must not cancel Gen 1 timer",
            ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // Fast-forward to verify Gen 1 timer legitimately fires
        mainLooper().idleFor(Duration.ofMillis(4500))
        drainTeardown()
        verify(newController, times(1)).close()
        assertEquals(gen1 + 1, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
    }

    @Test fun manualRestartCleansUpPendingTimerWithoutResidualExecution() {
        val gen = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        val reporter = statusReporter(gen)

        reporter(CarPlayStatus.Failed("Transient error", earlyUsbFailure = true))
        mainLooper().idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        // User or system initiates restartCarPlay directly
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "restartCarPlay", from(String::class.java, "manual restart"))
        drainTeardown()
        assertFalse("restartCarPlay must clear reconnectScheduled",
            ReflectionHelpers.getField<Boolean>(activity, "reconnectScheduled"))

        val newGen = ReflectionHelpers.getField<Int>(activity, "restartGeneration")
        assertEquals(gen + 1, newGen)

        // Advance looper past original 4000ms delay; ensure old timer does not fire again
        mainLooper().idleFor(Duration.ofMillis(5000))
        drainTeardown()
        assertEquals("Old timer must not increment generation after manual restart",
            newGen, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
    }
}
