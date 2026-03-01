package com.meta.oakley.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connection state for BLE device (e.g. Ray-Ban Meta Smart Glasses).
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * BLE manager v1: connection and device state only.
 *
 * **Audio is NOT sent over BLE.** Ray-Ban Meta glasses do not expose raw PCM write over BLE by default.
 * Bluetooth audio uses A2DP and is handled by the Android system. So:
 *
 * - **TTS output:** TTS → Android [AudioManager] → default output (Bluetooth A2DP when glasses are paired).
 * - **This class:** connect, disconnect, [connectionState]. Optional command triggers for glasses control only.
 *
 * Do not add BLE audio streaming unless you have reverse‑engineered custom audio characteristics.
 */
class BLEManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun connect(deviceAddress: String) {
        scope.launch {
            _connectionState.value = ConnectionState.Connecting
            // TODO: Perform actual BLE connect and discover GATT services/characteristics.
            _connectionState.value = ConnectionState.Connected
        }
    }

    fun disconnect() {
        scope.launch {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * Optional: trigger a command to the glasses (e.g. custom GATT write).
     * Use for device-specific actions, not for audio.
     */
    fun sendCommand(command: ByteArray) {
        // TODO: Write to a known GATT characteristic if needed.
    }
}
