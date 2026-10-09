package com.shilapi.xcertplay

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.PioneerBluetooth
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], qualifiers = "en", manifest = Config.NONE)
class PioneerPhonePickerTest {
    @Test fun pioneerPickerWorksWithAndroidBluetoothOffAndSavesSelectedPhone() = withHome { activity ->
        DiPlayPreferences.savePioneerBluetooth(activity, true)
        val phones = listOf(
            PioneerBluetooth.PairedDevice("12:34:56:78:9A:BC", "iPhone"),
            PioneerBluetooth.PairedDevice("AA:BB:CC:DD:EE:FF", "iPhone"),
        )
        var reads = 0
        ReflectionHelpers.setField(activity, "pioneerPhoneReader", { reads++; phones })
        choosePhone(activity)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(1, reads)
        assertEquals(listOf("iPhone · 9A:BC", "iPhone · EE:FF"),
            shadowOf(dialog).items.map { it.toString() })
        assertNull(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.takeIf { it.isNotEmpty() })
        shadowOf(dialog).clickOnItem(1)
        assertEquals("AA:BB:CC:DD:EE:FF", DiPlayPreferences.phoneAddress(activity))
        assertEquals("iPhone", DiPlayPreferences.phoneName(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun pioneerReadFailureIsShownWithoutAndroidFallbackOrChangingSelection() = withHome { activity ->
        DiPlayPreferences.savePioneerBluetooth(activity, true)
        DiPlayPreferences.savePhone(activity, "12:34:56:78:9A:BC", "Existing")
        ReflectionHelpers.setField(activity, "pendingWireless", true)
        val read: () -> List<PioneerBluetooth.PairedDevice> = { throw IOException("Firmware mismatch") }
        ReflectionHelpers.setField(activity, "pioneerPhoneReader", read)
        choosePhone(activity)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(shadowOf(dialog).message.toString().contains(activity.getString(R.string.pioneer_bluetooth_read_failed)))
        assertTrue(shadowOf(dialog).message.toString().contains("Firmware mismatch"))
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "pendingWireless"))
        assertEquals("Existing", DiPlayPreferences.phoneName(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun emptyFactoryListAsksForFactoryPairingAndCancellationClearsPendingConnect() = withHome { activity ->
        DiPlayPreferences.savePioneerBluetooth(activity, true)
        ReflectionHelpers.setField(activity, "pioneerPhoneReader", { emptyList<PioneerBluetooth.PairedDevice>() })
        ReflectionHelpers.setField(activity, "pendingWireless", true)
        choosePhone(activity)
        assertEquals(activity.getString(R.string.pioneer_bluetooth_no_paired_phones),
            shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString())
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "pendingWireless"))
        val read = { listOf(PioneerBluetooth.PairedDevice("12:34:56:78:9A:BC", "iPhone")) }
        ReflectionHelpers.setField(activity, "pioneerPhoneReader", read)
        ReflectionHelpers.setField(activity, "pendingWireless", true)
        choosePhone(activity)
        ShadowAlertDialog.getLatestAlertDialog().cancel()
        // Dialog.cancel posts its listener message to the main looper on Android 7.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "pendingWireless"))
        assertNull(DiPlayPreferences.phoneAddress(activity))
    }

    @Test fun disabledPioneerOptionStillUsesNormalAndroidBluetoothCheck() = withHome { activity ->
        DiPlayPreferences.savePioneerBluetooth(activity, false)
        val read: () -> List<PioneerBluetooth.PairedDevice> = { error("Factory stack must not be read") }
        ReflectionHelpers.setField(activity, "pioneerPhoneReader", read)
        choosePhone(activity)
        assertEquals(activity.getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first),
            shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString())
    }

    private fun choosePhone(activity: DiPlayActivity) =
        ReflectionHelpers.callInstanceMethod<Void>(activity, "choosePhone")

    private fun withHome(check: (DiPlayActivity) -> Unit) {
        CarPlayBackgroundSession.clear()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false)
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup().visible()
        try { check(activity) } finally {
            controller.pause().stop().destroy()
            CarPlayBackgroundSession.clear()
        }
    }
}
