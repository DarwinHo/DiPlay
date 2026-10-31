package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WirelessStartupPolicyTest {

    @Test
    fun classifyExtractsLegacyChannelOutcomeUnknownEvenWhenNested() {
        val direct = LegacyChannelOutcomeUnknown("Wi-Fi Direct channel selection did not respond")
        assertEquals(WirelessStartupFailure.P2P_CHANNEL_UNRESPONSIVE, WirelessStartupPolicy.classify(direct))

        val nested = IOException("Could not establish WIFI_P2P hotspot: timeout", direct)
        assertEquals(WirelessStartupFailure.P2P_CHANNEL_UNRESPONSIVE, WirelessStartupPolicy.classify(nested))

        val deep = RuntimeException("Outer wrapper", IOException("Middle wrapper", direct))
        assertEquals(WirelessStartupFailure.P2P_CHANNEL_UNRESPONSIVE, WirelessStartupPolicy.classify(deep))
    }

    @Test
    fun classifyExtractsP2pUnsupportedEvenWhenNested() {
        val direct = P2pCreateRejected(WifiP2pManager.P2P_UNSUPPORTED, "Wi-Fi P2P createGroup failed: P2P_UNSUPPORTED (code=1)")
        assertEquals(WirelessStartupFailure.P2P_UNSUPPORTED, WirelessStartupPolicy.classify(direct))

        val nested = IOException("Could not establish WIFI_P2P hotspot: rejected", direct)
        assertEquals(WirelessStartupFailure.P2P_UNSUPPORTED, WirelessStartupPolicy.classify(nested))
    }

    @Test
    fun classifyDoesNotMisclassifyBusyOrGenericP2pErrors() {
        val busy = P2pCreateRejected(WifiP2pManager.BUSY, "Wi-Fi P2P createGroup failed: BUSY (code=2)")
        assertNull(WirelessStartupPolicy.classify(busy))
        assertNull(WirelessStartupPolicy.classify(IOException("Could not establish hotspot", busy)))

        val genericError = P2pCreateRejected(WifiP2pManager.ERROR, "Wi-Fi P2P createGroup failed: ERROR (code=0)")
        assertNull(WirelessStartupPolicy.classify(genericError))
    }

    @Test
    fun classifyDoesNotMisclassifyTemporarilyDisabledWifiOrGenericExceptions() {
        val wifiOff = IOException("Turn on Wi-Fi in the head unit's settings, then reconnect")
        assertNull(WirelessStartupPolicy.classify(wifiOff))

        val generic = IOException("Connection lost")
        assertNull(WirelessStartupPolicy.classify(generic))

        assertNull(WirelessStartupPolicy.classify(null))
    }

    @Test
    fun classifyPreservesExistingWirelessStartupExceptions() {
        val config = WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "config error")
        assertEquals(WirelessStartupFailure.HOTSPOT_CONFIGURATION, WirelessStartupPolicy.classify(config))

        val tcp = WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "tcp timeout")
        assertEquals(WirelessStartupFailure.FIRST_TCP_TIMEOUT, WirelessStartupPolicy.classify(tcp))

        val notReady = WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY, "not ready")
        assertEquals(WirelessStartupFailure.HOTSPOT_NOT_READY, WirelessStartupPolicy.classify(notReady))

        val bt = WirelessStartupException(WirelessStartupFailure.BLUETOOTH_UNUSABLE, "bt unusable")
        assertEquals(WirelessStartupFailure.BLUETOOTH_UNUSABLE, WirelessStartupPolicy.classify(bt))
    }

    @Test
    fun retryablePolicyMatchesRequirements() {
        assertFalse("P2P_CHANNEL_UNRESPONSIVE must not automatically retry", WirelessStartupFailure.P2P_CHANNEL_UNRESPONSIVE.retryable)
        assertFalse("P2P_UNSUPPORTED must not automatically retry", WirelessStartupFailure.P2P_UNSUPPORTED.retryable)
        assertFalse("HOTSPOT_CONFIGURATION must not automatically retry", WirelessStartupFailure.HOTSPOT_CONFIGURATION.retryable)
        assertFalse("BLUETOOTH_UNUSABLE must not automatically retry", WirelessStartupFailure.BLUETOOTH_UNUSABLE.retryable)

        assertTrue("HOTSPOT_NOT_READY is retryable", WirelessStartupFailure.HOTSPOT_NOT_READY.retryable)
        assertTrue("FIRST_TCP_TIMEOUT is retryable", WirelessStartupFailure.FIRST_TCP_TIMEOUT.retryable)
    }
}
