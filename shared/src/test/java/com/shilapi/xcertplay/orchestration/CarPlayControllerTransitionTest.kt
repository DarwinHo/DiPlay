package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerTransitionTest {
    private lateinit var context: Context
    private lateinit var controller: CarPlayController
    private lateinit var mockHost: IphoneUsbHost
    private val statuses = mutableListOf<CarPlayStatus>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        val config = CarPlayRuntimeConfig(
            transport = CarPlayTransport.WIRED,
            mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3),
        )
        val airPlayConfig = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1280, 720))

        mockHost = mock(IphoneUsbHost::class.java)

        controller = CarPlayController(
            context = context,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
            reportStatus = { statuses.add(it) },
        )

        // Inject controlled IphoneUsbHost through its lazy delegate
        ReflectionHelpers.setField(controller, "iphoneHost\$delegate", lazyOf(mockHost))

        // Enter legitimate production phase: startIphone puts controller into Phase.IPHONE
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "startIphone")
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun tearDown() {
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
    }

    private fun device(name: String, configurations: Array<UsbConfiguration> = emptyArray()): UsbDevice =
        ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", name)
            ReflectionHelpers.setField(this, "mVendorId", IphoneUsbMatcher.APPLE_VENDOR_ID)
            ReflectionHelpers.setField(this, "mProductId", 0x12a8)
            ReflectionHelpers.setField(this, "mConfigurations", configurations)
        }

    private fun mainLooper() = shadowOf(Looper.getMainLooper())

    @Test fun duplicatePermissionTriggerDuringWaitingForReenumerationWindowIsSuppressed() {
        val dev1 = device("/dev/bus/usb/002/006")
        `when`(mockHost.requestPermission(eq(dev1), anyBoolean()))
            .thenReturn(IphoneUsbHost.PermissionRequest.AlreadyGranted(dev1))

        // 1. Initial permission request via real entrypoint
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller,
            "requestIphonePermission",
            from(UsbDevice::class.java, dev1),
        )
        mainLooper().idle()

        // Verify requestCarPlayReenumerationAsync was called once
        val captor = ArgumentCaptor.forClass(Function1::class.java as Class<Function1<IphoneUsbHost.TransitionResult, Unit>>)
        verify(mockHost, times(1)).requestCarPlayReenumerationAsync(eq(dev1), any(Executor::class.java), captor.capture())
        assertEquals(1, ReflectionHelpers.getField<Int>(controller, "reenumerationAttempts"))

        // 2. Callback returns ReenumerationRequested, entering the waiting-for-reenumeration window
        captor.value.invoke(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        mainLooper().idle()

        assertTrue("Status must be WaitingForReenumeration",
            statuses.contains(CarPlayStatus.WaitingForReenumeration))

        // 3. Duplicate trigger arrives for dev1 while physical re-enumeration has not completed yet
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller,
            "requestIphonePermission",
            from(UsbDevice::class.java, dev1),
        )
        mainLooper().idle()

        // Assert transition was NOT called a second time
        verify(mockHost, times(1)).requestCarPlayReenumerationAsync(any(), any(), any())
        assertEquals("Transition count must remain 1 during waiting window",
            1, ReflectionHelpers.getField<Int>(controller, "reenumerationAttempts"))
    }

    @Test fun oldTransitionFailureArrivingLateAfterOpenDataPathsIsInvalidatedAndDoesNotFailSession() {
        val dev1 = device("/dev/bus/usb/002/006")
        `when`(mockHost.requestPermission(eq(dev1), anyBoolean()))
            .thenReturn(IphoneUsbHost.PermissionRequest.AlreadyGranted(dev1))

        // Start transition on dev1
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller,
            "requestIphonePermission",
            from(UsbDevice::class.java, dev1),
        )
        mainLooper().idle()

        val captor = ArgumentCaptor.forClass(Function1::class.java as Class<Function1<IphoneUsbHost.TransitionResult, Unit>>)
        verify(mockHost, times(1)).requestCarPlayReenumerationAsync(eq(dev1), any(Executor::class.java), captor.capture())
        val callback1 = captor.value

        // Re-enumerated device dev2 arrives and enters data paths
        val dev2 = device("/dev/bus/usb/002/007")
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller,
            "openDataPaths",
            from(UsbDevice::class.java, dev2),
        )
        mainLooper().idle()

        val phase = ReflectionHelpers.getField<Any>(controller, "phase")
        assertEquals("DATAPATHS", phase.toString())

        // Late callback from dev1 transition now arrives reporting failure!
        callback1.invoke(IphoneUsbHost.TransitionResult.Failed(
            IphoneUsbException.DeviceUnavailable("Late transition error"),
        ))
        mainLooper().idle()

        // Assert that the late callback did not destroy the session or emit failure
        assertEquals("Phase must remain DATAPATHS; late callback must not revert phase",
            "DATAPATHS", ReflectionHelpers.getField<Any>(controller, "phase").toString())
        assertFalse("Late callback must not report failure to status reporter",
            statuses.any { it is CarPlayStatus.Failed && it.message.contains("Late transition error") })
    }

    @Test fun oldTransitionSuccessArrivingLateAfterOpenDataPathsDoesNotRevertPhase() {
        val dev1 = device("/dev/bus/usb/002/006")
        `when`(mockHost.requestPermission(eq(dev1), anyBoolean()))
            .thenReturn(IphoneUsbHost.PermissionRequest.AlreadyGranted(dev1))

        // Start transition on dev1
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller,
            "requestIphonePermission",
            from(UsbDevice::class.java, dev1),
        )
        mainLooper().idle()

        val captor = ArgumentCaptor.forClass(Function1::class.java as Class<Function1<IphoneUsbHost.TransitionResult, Unit>>)
        verify(mockHost, times(1)).requestCarPlayReenumerationAsync(eq(dev1), any(Executor::class.java), captor.capture())
        val callback1 = captor.value

        // Re-enumerated device dev2 arrives and enters data paths
        val dev2 = device("/dev/bus/usb/002/007")
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller,
            "openDataPaths",
            from(UsbDevice::class.java, dev2),
        )
        mainLooper().idle()

        statuses.clear()

        // Late success callback arrives
        callback1.invoke(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        mainLooper().idle()

        assertEquals("Phase must remain DATAPATHS",
            "DATAPATHS", ReflectionHelpers.getField<Any>(controller, "phase").toString())
        assertFalse("Late success must not report WaitingForReenumeration",
            statuses.contains(CarPlayStatus.WaitingForReenumeration))
    }

    @Test fun reenumeratedDeviceWithNewNameIsAllowedToTransition() {
        val devOld = device("/dev/bus/usb/002/006")
        val devNew = device("/dev/bus/usb/002/007")
        `when`(mockHost.requestPermission(any(), anyBoolean()))
            .thenAnswer { invocation ->
                IphoneUsbHost.PermissionRequest.AlreadyGranted(invocation.arguments[0] as UsbDevice)
            }

        // Transition on devOld
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller,
            "requestIphonePermission",
            from(UsbDevice::class.java, devOld),
        )
        mainLooper().idle()
        assertEquals(1, ReflectionHelpers.getField<Int>(controller, "reenumerationAttempts"))

        // devNew arrives and needs transition
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller,
            "requestIphonePermission",
            from(UsbDevice::class.java, devNew),
        )
        mainLooper().idle()

        assertEquals("New device enumeration must be allowed to transition",
            2, ReflectionHelpers.getField<Int>(controller, "reenumerationAttempts"))
    }

    @Test fun failedTransitionClearsActiveTransitionToAllowRetry() {
        val dev1 = device("/dev/bus/usb/002/006")
        `when`(mockHost.requestPermission(eq(dev1), anyBoolean()))
            .thenReturn(IphoneUsbHost.PermissionRequest.AlreadyGranted(dev1))

        // Start transition
        ReflectionHelpers.callInstanceMethod<Unit>(
            controller,
            "requestIphonePermission",
            from(UsbDevice::class.java, dev1),
        )
        mainLooper().idle()

        val captor = ArgumentCaptor.forClass(Function1::class.java as Class<Function1<IphoneUsbHost.TransitionResult, Unit>>)
        verify(mockHost, times(1)).requestCarPlayReenumerationAsync(eq(dev1), any(), captor.capture())

        // Callback returns failure
        captor.value.invoke(IphoneUsbHost.TransitionResult.Failed(
            IphoneUsbException.DeviceUnavailable("Rejection"),
        ))
        mainLooper().idle()

        assertNull("Failed transition must clear active transition enumeration",
            ReflectionHelpers.getField<String?>(controller, "activeTransitionEnumeration"))
    }
}
