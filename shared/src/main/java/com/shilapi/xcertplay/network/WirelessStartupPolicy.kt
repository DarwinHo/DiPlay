package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import java.io.IOException

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    const val FIRST_TCP_MILLIS = 30_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L

    fun classify(error: Throwable?): WirelessStartupFailure? {
        val causes = generateSequence(error) { it.cause }.toList()
        return causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.reason
            ?: causes.filterIsInstance<LegacyChannelOutcomeUnknown>().firstOrNull()?.let {
                WirelessStartupFailure.P2P_CHANNEL_UNRESPONSIVE
            }
            ?: causes.filterIsInstance<P2pCreateRejected>().firstOrNull()?.takeIf {
                it.reason == WifiP2pManager.P2P_UNSUPPORTED
            }?.let {
                WirelessStartupFailure.P2P_UNSUPPORTED
            }
    }
}

enum class WirelessStartupFailure {
    HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION,

    /** The head unit's Android Bluetooth accepts connections that reach no phone. */
    BLUETOOTH_UNUSABLE,

    /** The legacy Wi-Fi Direct channel selection command did not receive a framework callback (outcome unknown). */
    P2P_CHANNEL_UNRESPONSIVE,

    /** The framework rejected Wi-Fi Direct with P2P_UNSUPPORTED. */
    P2P_UNSUPPORTED;

    /** False when the current automatic retry should be stopped without implying permanent unrecoverability. */
    val retryable: Boolean get() = this != HOTSPOT_CONFIGURATION &&
        this != BLUETOOTH_UNUSABLE &&
        this != P2P_CHANNEL_UNRESPONSIVE &&
        this != P2P_UNSUPPORTED
}

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)
