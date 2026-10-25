package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Looper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportUiTest {
    @Test fun missingPickerSavesAReportAndProvidesSelectableTextInsideDiPlay() = checkExport(false)

    @Test @Config(sdk = [25])
    fun androidSevenSaveButtonBypassesPickerThatOpensButCannotSave() = checkExport(true)

    private fun checkExport(primaryButton: Boolean) {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        // Exercise the OEM picker failure path when storage permission is unavailable.
        // The upstream button first asks permission; a missing permission UI must still save.
        ReflectionHelpers.setField(activity, "reportStoragePermission", object : ActivityResultLauncher<String>() {
            override fun launch(input: String, options: ActivityOptionsCompat?) {
                throw ActivityNotFoundException("No permission UI")
            }
            override fun unregister() = Unit
            override fun getContract() = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        })
        val context = activity.applicationContext
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, DiagnosticReportProvider().apply { attachInfo(context, info) })
        val reports = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")
        reports.deleteRecursively()
        var pickerLaunches = 0
        val missingPicker = object : ActivityResultLauncher<String>() {
            override fun launch(input: String, options: ActivityOptionsCompat?) {
                pickerLaunches++
                if (!primaryButton) throw ActivityNotFoundException("No DocumentsUI")
                // A broken OEM picker opens but never returns a destination.
            }
            override fun unregister() = Unit
            override fun getContract() = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
        }
        ReflectionHelpers.setField(activity, "export", missingPicker)
        try {
            if (primaryButton) {
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
                    ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, LinearLayout(activity)),
                    ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.DIAGNOSTICS)))
                ReflectionHelpers.getField<View>(activity, "exportButton").performClick()
                assertEquals(0, pickerLaunches)
                assertNull(shadowOf(activity).nextStartedActivity)
            } else {
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "chooseReportDestination")
                assertEquals(1, pickerLaunches)
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ShadowAlertDialog.getLatestAlertDialog() == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val saved = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val file = reports.listFiles()!!.single()
            assertTrue(file.name.endsWith(".txt"))
            assertTrue(descendants(saved.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.contains(file.absolutePath) })
            val androidVersion = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"
            assertTrue(file.readText().contains(androidVersion))
            saved.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val viewer = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(descendants(viewer.window!!.decorView).filterIsInstance<TextView>()
                .any { it.isTextSelectable && it.text.contains(androidVersion) })
            viewer.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun openFileManagerLaunchesDocumentsUiFilesActivity() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val content = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
                ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.DIAGNOSTICS)))
            val button = descendants(content).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.settings_open_file_manager) }
            button.performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertNotNull(intent)
            assertEquals("android.provider.action.BROWSE", intent.action)
            assertEquals("vnd.android.document/root", intent.type)
            assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun openAndroidSettingsLaunchesNativeSettings() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val content = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
                ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.DIAGNOSTICS)))
            val button = descendants(content).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.settings_open_android_settings) }
            button.performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertNotNull(intent)
            assertEquals(android.provider.Settings.ACTION_SETTINGS, intent.action)
            assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun openPioneerMaintenanceLaunchesDebugModeMain() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val content = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
                ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.DIAGNOSTICS)))
            val button = descendants(content).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.settings_open_pioneer_maintenance) }
            button.performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertNotNull(intent)
            assertEquals("android.intent.action.DEBUGMODEMAIN", intent.action)
            assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun reloadWifiCountryCodeRendersAndCanBeClicked() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val content = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
                ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.DIAGNOSTICS)))
            val button = descendants(content).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.settings_reload_wifi_country_code) }
            button.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun scanUsbApksRendersAndShowsDialogWhenClicked() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val content = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
                ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.DIAGNOSTICS)))
            val button = descendants(content).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.settings_scan_usb_apks) }
            button.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            assertEquals(activity.getString(R.string.settings_scan_usb_apks), org.robolectric.Shadows.shadowOf(dialog).title)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun enableWirelessAdbRendersAndCanBeClicked() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val content = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
                ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.DIAGNOSTICS)))
            val button = descendants(content).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.settings_enable_wireless_adb) }
            button.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun testPioneerRootChannelRendersAndCanBeClicked() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val content = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
                ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.DIAGNOSTICS)))
            val button = descendants(content).filterIsInstance<Button>()
                .single { it.text == activity.getString(R.string.settings_test_pioneer_root) }
            button.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
