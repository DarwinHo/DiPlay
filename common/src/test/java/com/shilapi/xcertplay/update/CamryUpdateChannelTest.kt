package com.shilapi.xcertplay.update

import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25])
class CamryUpdateChannelTest {
    @Test fun customBuildChecksItsOwnRepositoryAndSelectsOnlyTheFullCustomApk() {
        var requested: URL? = null
        val result = UpdateReleaseLookup.latest("0.2.17", "DiPlay/test", openConnection = { url ->
            requested = url
            response(url, releases("DiPlay-com.tencent.mm-full.apk", "DiPlay-0.2.18.apk"))
        }, installedPackage = "com.tencent.mm")
        assertEquals(UpdateReleaseLookup.CAMRY_RELEASES_URL, requested.toString())
        assertEquals(UpdateReleaseLookup.CAMRY_APK_NAME, result?.apkName)
        assertTrue(result!!.apkUrl.startsWith("https://github.com/DarwinHo/DiPlay/"))
    }

    @Test fun customBuildRejectsOfficialAndInstallationOnlyAssets() {
        for (name in listOf("DiPlay-0.2.18.apk", "DiPlay-com.tencent.mm-install-test.apk")) {
            assertNull(UpdateReleaseLookup.latest("0.2.17", "DiPlay/test", openConnection = {
                response(it, releases(name))
            }, installedPackage = "com.tencent.mm"))
        }
    }

    @Test fun customNameOnAnUpstreamUrlIsRejected() {
        assertNull(UpdateReleaseLookup.latest("0.2.17", "DiPlay/test", openConnection = {
            response(it, releases(UpdateReleaseLookup.CAMRY_APK_NAME).replace("DarwinHo", "shihabal3amri"))
        }, installedPackage = "com.tencent.mm"))
    }

    @Test fun customBuildWithoutPublishedReleasesHasNoDownloadOffer() {
        assertNull(UpdateReleaseLookup.latest("0.2.17", "DiPlay/test", openConnection = {
            response(it, "[]")
        }, installedPackage = "com.tencent.mm"))
    }

    @Test fun officialBuildKeepsTheUpstreamChannel() {
        var requested: URL? = null
        assertNotNull(UpdateReleaseLookup.latest("0.2.17", "DiPlay/test", openConnection = {
            requested = it
            response(it, releases("DiPlay-0.2.18.apk"))
        }))
        assertEquals(UpdateClient.RELEASES_URL, requested.toString())
    }

    @Test fun customBuildDiscardsCachedOfficialDownloadEvenWhenItIsNewer() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = "com.tencent.mm"
        }
        try {
            UpdateAvailability.save(context, UpdateRelease("v0.2.18", "DiPlay-0.2.18.apk",
                "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.18/DiPlay-0.2.18.apk",
                "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.18/SHA256SUMS.txt"))
            assertNull(UpdateAvailability.available(context, "0.2.17"))
            UpdateAvailability.save(context, UpdateRelease("v0.2.18", UpdateReleaseLookup.CAMRY_APK_NAME,
                "$assetRoot/${UpdateReleaseLookup.CAMRY_APK_NAME}", "$assetRoot/SHA256SUMS.txt"))
            assertNotNull(UpdateAvailability.available(context, "0.2.17"))
        } finally {
            UpdateAvailability.clearAllForTest(context)
        }
    }

    private val assetRoot = "https://github.com/DarwinHo/DiPlay/releases/download/v0.2.18"
    private fun releases(vararg names: String): String {
        val assets = (names.toList() + "SHA256SUMS.txt").joinToString(",") {
            """{"name":"$it","browser_download_url":"$assetRoot/$it"}"""
        }
        return """[{"tag_name":"v0.2.18","draft":false,"assets":[$assets]}]"""
    }

    private fun response(url: URL, body: String) = object : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }
}
