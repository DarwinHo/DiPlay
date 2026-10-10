package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection

internal object UpdateReleaseLookup {
    internal const val CAMRY_RELEASES_URL =
        "https://api.github.com/repos/DarwinHo/DiPlay/releases?per_page=3"
    internal const val CAMRY_APK_NAME = "DiPlay-com.tencent.mm-full.apk"

    internal fun isCamryRelease(release: UpdateRelease): Boolean =
        release.apkName == CAMRY_APK_NAME &&
            release.apkUrl.startsWith("https://github.com/DarwinHo/DiPlay/releases/download/") &&
            release.checksumsUrl.startsWith("https://github.com/DarwinHo/DiPlay/releases/download/")

    fun latest(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
        installedPackage: String = "com.shihab.diplay",
    ): UpdateRelease? {
        val camry = installedPackage == "com.tencent.mm"
        val json = UpdateClient.fetchText(
            if (camry) CAMRY_RELEASES_URL else UpdateClient.RELEASES_URL,
            "application/vnd.github+json",
            userAgent,
            openConnection,
        )
        val release = UpdateCatalog.parse(json, if (camry) CAMRY_APK_NAME else null) ?: return null
        return release.takeIf {
            (!camry || isCamryRelease(it)) && UpdateVersion.isNewer(it.tagName, installedVersion)
        }
    }
}
