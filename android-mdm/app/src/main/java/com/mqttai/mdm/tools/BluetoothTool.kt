package com.mqttai.mdm.tools

import android.bluetooth.BluetoothAdapter
import android.util.Log

object BluetoothTool {
    private const val TAG = "BluetoothTool"

    /**
     * Enable or disable Bluetooth.
     * Requires targetSdk <= 28 for BluetoothAdapter.enable()/disable().
     */
    @Suppress("DEPRECATION")
    fun setBluetoothEnabled(enabled: Boolean): String {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return "Bluetooth not available on this device"
            val ok = if (enabled) adapter.enable() else adapter.disable()
            val state = if (enabled) "enabled" else "disabled"
            if (ok) {
                Log.i(TAG, "Bluetooth $state")
                "Bluetooth $state"
            } else {
                Log.w(TAG, "Bluetooth toggle returned false")
                "Bluetooth toggle rejected by system"
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied", e)
            "Permission denied: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth", e)
            "Error: ${e.message}"
        }
    }

    fun isEnabled(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
        } catch (_: Exception) { false }
    }
}
