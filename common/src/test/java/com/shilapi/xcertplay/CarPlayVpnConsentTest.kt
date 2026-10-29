package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], qualifiers = "en", shadows = [VpnConsentTestShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayVpnConsentTest {
    private lateinit var activity: CarPlayHostActivity
    private var launches = 0

    @Before fun setUp() {
        VpnConsentTestShadow.failure = null
        VpnConsentTestShadow.consent = Intent().setClassName(
            "com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog",
        )
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "stageStatusView", TextView(activity))
        invoke("initializeSessionLog")
        replaceLauncher { launches += 1 }
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(null)
        field<CarPlayController?>("controller")?.let {
            CarPlayMediaKeys.detach(it)
        }
        (ReflectionHelpers.getField<Any?>(activity, "sink") as? AndroidMediaSink)?.close()
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        AirPlayPersistence.saveLocationReportingEnabled(activity, false)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        VpnConsentTestShadow.failure = null
        VpnConsentTestShadow.consent = null
    }

    @Test fun missingConsentDialogDoesNotCrashOrLeaveAuthorizationPending() {
        ReflectionHelpers.setField(activity, "vpnReady", true)
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }

        invoke("requestVpnConsent")

        assertUnavailable()
        assertTrue(log().contains("failureClass=ActivityNotFoundException"))
        assertTrue(log().contains("component=com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog"))
    }

    @Test fun deniedConsentLaunchDoesNotCrashOrUnlockUsb() {
        replaceLauncher { throw SecurityException("OEM blocked VPN activity") }
        invoke("requestVpnConsent")
        assertUnavailable()
        assertTrue(log().contains("operation=launch"))
        assertTrue(log().contains("failureClass=SecurityException"))
    }

    @Test fun deniedPrepareDoesNotCrashOrLeaveStaleAuthorization() {
        ReflectionHelpers.setField(activity, "vpnReady", true)
        VpnConsentTestShadow.failure = SecurityException("OEM blocked VPN preparation")
        invoke("requestVpnConsent")
        assertUnavailable()
        assertEquals(0, launches)
        assertTrue(log().contains("operation=prepare"))
    }

    @Test fun grantedAuthorizationDoesNotOpenAConsentActivity() {
        VpnConsentTestShadow.consent = null
        invoke("requestVpnConsent")
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertEquals(0, launches)
    }

    @Test fun pendingAuthorizationDoesNotLaunchTwice() {
        repeat(2) { invoke("requestVpnConsent") }
        assertFalse(field("vpnReady"))
        assertTrue(field("awaitingVpnConsent"))
        assertEquals(1, launches)
    }

    @Test fun failedLaunchCanBeRetried() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        replaceLauncher { launches += 1 }
        invoke("requestVpnConsent")
        assertTrue(field("awaitingVpnConsent"))
        assertFalse(field("vpnReady"))
        assertEquals(1, launches)
    }

    @Test fun lateSuccessfulResultAfterFailedLaunchDoesNotUnlockUsb() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
    }

    @Test fun resultOfPendingConsentCompletesOrDeniesAuthorization() {
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED))
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
    }

    @Test fun initialVpnFailureCanBeRepairedInSettingsAndResumed() {
        prepareCarPlayPrerequisites()
        var preparerCalls = 0
        activity.vpnPreparer = { ctx ->
            preparerCalls++
            VpnConsentTestShadow.prepare(ctx)
        }
        val controllers: MockedConstruction<CarPlayController> = mockCarPlayController()
        try {
            VpnConsentTestShadow.consent = Intent().setClassName("com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog")
            invoke("requestVpnConsent")
            assertEquals("Initial request must check VPN consent", 1, preparerCalls)
            assertFalse(field<Boolean>("vpnReady"))
            assertTrue(field<Boolean>("awaitingVpnConsent"))
            assertEquals(1, launches)
            assertEquals("CarPlay must not start when VPN is awaiting consent", 0, controllers.constructed().size)

            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED))
            assertFalse(field<Boolean>("vpnReady"))
            assertFalse(field<Boolean>("awaitingVpnConsent"))
            assertEquals("CarPlay must not start when VPN consent was denied", 0, controllers.constructed().size)

            VpnConsentTestShadow.consent = null
            val preparerBeforeResume = preparerCalls
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onResume")

            assertFalse("Must remain in wired mode after onResume", field<Boolean>("wirelessEnabled"))
            assertTrue("VPN preparer must be called on normal resume", preparerCalls > preparerBeforeResume)
            assertTrue("vpnReady must be set to true on valid authorization", field<Boolean>("vpnReady"))
            assertFalse("awaitingVpnConsent must be false", field<Boolean>("awaitingVpnConsent"))
            assertEquals("VPN consent dialog must not be launched again", 1, launches)

            assertEquals("Exactly one CarPlayController must be created", 1, controllers.constructed().size)
            val createdController = controllers.constructed().single()
            org.mockito.Mockito.verify(createdController, org.mockito.Mockito.times(1)).start()
            org.mockito.Mockito.verify(createdController, org.mockito.Mockito.never()).close()
            assertSame("Controller field must hold the newly created instance", createdController, field("controller"))

            val preparerBeforeSecondResume = preparerCalls
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onResume")
            assertEquals("Existing session must skip VPN recheck", preparerBeforeSecondResume, preparerCalls)
            assertEquals("Duplicate onResume must not create another controller", 1, controllers.constructed().size)
            org.mockito.Mockito.verify(createdController, org.mockito.Mockito.times(1)).start()
            org.mockito.Mockito.verify(createdController, org.mockito.Mockito.never()).close()
            assertSame("Existing controller must not be replaced", createdController, field("controller"))
        } finally {
            field<CarPlayController?>("controller")?.let {
                CarPlayMediaKeys.detach(it)
            }
            (ReflectionHelpers.getField<Any?>(activity, "sink") as? AndroidMediaSink)?.close()
            CarPlayBackgroundSession.clear()
            controllers.close()
        }
    }

    @Test fun resumeWhenVpnStillDeniedDoesNotReportReadyOrLaunchConsent() {
        prepareCarPlayPrerequisites()
        var preparerCalls = 0
        activity.vpnPreparer = { ctx ->
            preparerCalls++
            VpnConsentTestShadow.prepare(ctx)
        }
        val controllers: MockedConstruction<CarPlayController> = mockCarPlayController()
        try {
            VpnConsentTestShadow.consent = Intent().setClassName("com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog")
            ReflectionHelpers.setField(activity, "vpnReady", false)
            ReflectionHelpers.setField(activity, "awaitingVpnConsent", false)

            val preparerBefore = preparerCalls
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onResume")

            assertFalse("Must remain in wired mode after onResume", field<Boolean>("wirelessEnabled"))
            assertTrue("VPN preparer must be called on resume", preparerCalls > preparerBefore)
            assertFalse("vpnReady must remain false when still denied", field<Boolean>("vpnReady"))
            assertFalse("awaitingVpnConsent must not be set on silent resume", field<Boolean>("awaitingVpnConsent"))
            assertEquals("Must not popup duplicate consent dialog", 0, launches)
            assertEquals("CarPlay must not start when VPN is still denied", 0, controllers.constructed().size)
            assertNull(field<CarPlayController?>("controller"))
        } finally {
            field<CarPlayController?>("controller")?.let {
                CarPlayMediaKeys.detach(it)
            }
            (ReflectionHelpers.getField<Any?>(activity, "sink") as? AndroidMediaSink)?.close()
            CarPlayBackgroundSession.clear()
            controllers.close()
        }
    }

    @Test fun resumeWhileAwaitingVpnConsentDoesNotInterfereOrStartCarPlay() {
        prepareCarPlayPrerequisites()
        var preparerCalls = 0
        activity.vpnPreparer = { ctx ->
            preparerCalls++
            VpnConsentTestShadow.prepare(ctx)
        }
        val controllers: MockedConstruction<CarPlayController> = mockCarPlayController()
        try {
            VpnConsentTestShadow.consent = Intent().setClassName("com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog")
            ReflectionHelpers.setField(activity, "vpnReady", false)
            ReflectionHelpers.setField(activity, "awaitingVpnConsent", true)

            val preparerBefore = preparerCalls
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onResume")

            assertFalse("Must remain in wired mode after onResume", field<Boolean>("wirelessEnabled"))
            assertEquals("Awaiting consent protection branch must skip VPN preparer", preparerBefore, preparerCalls)
            assertTrue("awaitingVpnConsent must be preserved", field<Boolean>("awaitingVpnConsent"))
            assertFalse("vpnReady must remain false while awaiting consent", field<Boolean>("vpnReady"))
            assertEquals("Must not popup duplicate consent dialog", 0, launches)
            assertEquals("CarPlay must not start while awaiting consent", 0, controllers.constructed().size)
            assertNull(field<CarPlayController?>("controller"))
        } finally {
            field<CarPlayController?>("controller")?.let {
                CarPlayMediaKeys.detach(it)
            }
            (ReflectionHelpers.getField<Any?>(activity, "sink") as? AndroidMediaSink)?.close()
            CarPlayBackgroundSession.clear()
            controllers.close()
        }
    }

    @Test fun silentVpnRecheckExceptionPreservesFalseWithoutTriggeringBackgroundAdbConsent() {
        prepareCarPlayPrerequisites()
        var preparerCalls = 0
        activity.vpnPreparer = { ctx ->
            preparerCalls++
            VpnConsentTestShadow.prepare(ctx)
        }
        val controllers: MockedConstruction<CarPlayController> = mockCarPlayController()
        try {
            VpnConsentTestShadow.failure = SecurityException("OEM security restriction")
            ReflectionHelpers.setField(activity, "vpnAdbConsentTried", false)
            ReflectionHelpers.setField(activity, "vpnReady", false)
            ReflectionHelpers.setField(activity, "awaitingVpnConsent", false)

            val preparerBefore = preparerCalls
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onResume")

            assertFalse("Must remain in wired mode after onResume", field<Boolean>("wirelessEnabled"))
            assertTrue("VPN preparer must be called on resume", preparerCalls > preparerBefore)
            assertFalse("vpnReady must remain false on exception", field<Boolean>("vpnReady"))
            assertFalse("awaitingVpnConsent must remain false", field<Boolean>("awaitingVpnConsent"))
            assertFalse("Silent recheck must NOT trigger background adb consent", field<Boolean>("vpnAdbConsentTried"))
            assertEquals("Must not launch consent on exception", 0, launches)
            assertEquals("CarPlay must not start on exception", 0, controllers.constructed().size)
            assertTrue(log().contains("failureClass=SecurityException"))
        } finally {
            field<CarPlayController?>("controller")?.let {
                CarPlayMediaKeys.detach(it)
            }
            (ReflectionHelpers.getField<Any?>(activity, "sink") as? AndroidMediaSink)?.close()
            CarPlayBackgroundSession.clear()
            controllers.close()
        }
    }

    private fun mockCarPlayController(): MockedConstruction<CarPlayController> =
        mockConstruction(CarPlayController::class.java) { controller, _ ->
            org.mockito.Mockito.`when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        }

    private fun prepareCarPlayPrerequisites() {
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        AirPlayPersistence.saveLocationReportingEnabled(activity, false)
        AirPlayPersistence.saveSmoothVideo(activity, false)
        AirPlayPersistence.saveDirectVideoOutput(activity, false)
        ReflectionHelpers.setField(activity, "airPlayIdentity", AirPlayIdentity.generate())
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 990)
        ReflectionHelpers.setField(activity, "activeDisplaySize", size)
        ReflectionHelpers.setField(activity, "microphonePermissionResolved", true)
        ReflectionHelpers.setField(activity, "menuOpen", false)
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", false)
        ReflectionHelpers.setField(activity, "pendingDisplaySize", null)
    }

    private fun replaceLauncher(launch: (Intent) -> Unit) {
        ReflectionHelpers.setField(activity, "vpnConsent", object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) = launch(input)
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        })
    }

    private fun assertUnavailable() {
        assertFalse(field<Boolean>("vpnReady"))
        assertFalse(field<Boolean>("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
        val message = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()
        assertTrue(message.contains("VPN authorization"))
        assertTrue(message.contains("Return to DiPlay"))
        assertFalse(message.contains("Exception"))
        assertFalse(message.contains("adb shell"))
    }

    private fun <T> field(name: String): T = ReflectionHelpers.getField(activity, name)
    private fun log() = File(activity.filesDir, "logs/diplay.log").readText()
    private fun invoke(name: String) {
        try { activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
}

@Implements(VpnService::class)
class VpnConsentTestShadow {
    companion object {
        var consent: Intent? = null
        var failure: RuntimeException? = null
        @JvmStatic @Implementation fun prepare(context: Context): Intent? {
            failure?.let { throw it }
            return consent
        }
    }
}
