package com.quotawatch.data

import com.quotawatch.ble.BleClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [isFreshBleConnection] transition check (gh #17: the M5 got no data
 * after connecting until the next manual Refresh, since doRefresh() only sends when a refresh
 * happens to complete while already Connected). [QuotaRepository] itself (the DataStore/BLE/
 * WebView-backed wiring around this) isn't exercised here — no Android/mocking test infra in this
 * module — only the pure decision of which state transitions warrant an immediate push.
 */
class QuotaRepositoryTest {

    private val connected = BleClient.State.Connected
    private val disconnected = BleClient.State.Disconnected
    private val scanning = BleClient.State.Scanning
    private val connecting = BleClient.State.Connecting
    private val reconnecting = BleClient.State.Reconnecting
    private val error = BleClient.State.Error("boom")

    @Test
    fun `arriving at Connected from any non-Connected state triggers a push`() {
        assertTrue(isFreshBleConnection(null, connected))
        assertTrue(isFreshBleConnection(disconnected, connected))
        assertTrue(isFreshBleConnection(scanning, connected))
        assertTrue(isFreshBleConnection(connecting, connected))
        assertTrue(isFreshBleConnection(error, connected))
    }

    @Test
    fun `reconnecting to Connected triggers a push — the M5 may have rebooted`() {
        // The specific case called out in the finding: Connected -> Reconnecting -> Connected
        // must resend, not just a first-ever connection.
        assertTrue(isFreshBleConnection(reconnecting, connected))
    }

    @Test
    fun `already Connected does not re-trigger on another Connected emission`() {
        assertFalse(isFreshBleConnection(connected, connected))
    }

    @Test
    fun `transitions that do not land on Connected never trigger`() {
        assertFalse(isFreshBleConnection(connected, reconnecting))
        assertFalse(isFreshBleConnection(connected, disconnected))
        assertFalse(isFreshBleConnection(disconnected, disconnected))
        assertFalse(isFreshBleConnection(scanning, connecting))
        assertFalse(isFreshBleConnection(null, disconnected))
        assertFalse(isFreshBleConnection(null, scanning))
    }
}
