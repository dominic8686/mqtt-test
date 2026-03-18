package com.mqttai.mdm.tools

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

data class WifiResult(val success: Boolean, val message: String, val needsSettingsPanel: Boolean = false)

object WifiTool {
    private const val TAG = "WifiTool"

    /**
     * Toggles Wi-Fi using WifiManager.setWifiEnabled().
     * Requires targetSdk <= 28 and CHANGE_WIFI_STATE permission.
     */
    @Suppress("DEPRECATION")
    fun setWifiEnabled(context: Context, enabled: Boolean): WifiResult {
        val state = if (enabled) "enabled" else "disabled"
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ok = wifiManager.setWifiEnabled(enabled)
            if (ok) {
                Log.i(TAG, "Wi-Fi $state via WifiManager")
                WifiResult(true, "Wi-Fi $state")
            } else {
                Log.w(TAG, "WifiManager.setWifiEnabled returned false")
                WifiResult(false, "Wi-Fi toggle rejected by system", needsSettingsPanel = true)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing CHANGE_WIFI_STATE permission", e)
            WifiResult(false, "Permission denied: ${e.message}", needsSettingsPanel = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Wi-Fi", e)
            WifiResult(false, "Error: ${e.message}", needsSettingsPanel = true)
        }
    }
}
