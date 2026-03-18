package com.mqttai.mdm.tools

import android.content.Context
import android.provider.Settings
import android.util.Log

object LocationTool {
    private const val TAG = "LocationTool"

    /**
     * Toggle location services. Requires WRITE_SECURE_SETTINGS
     * (grant via: adb shell pm grant com.mqttai.mdm android.permission.WRITE_SECURE_SETTINGS)
     *
     * @param mode: "off", "sensors_only", "battery_saving", "high_accuracy"
     */
    @Suppress("DEPRECATION")
    fun setLocationMode(context: Context, mode: String): String {
        return try {
            val modeInt = when (mode.lowercase()) {
                "off" -> Settings.Secure.LOCATION_MODE_OFF
                "sensors_only", "gps" -> Settings.Secure.LOCATION_MODE_SENSORS_ONLY
                "battery_saving" -> Settings.Secure.LOCATION_MODE_BATTERY_SAVING
                "high_accuracy", "on" -> Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                else -> return "Unknown mode: $mode. Use off/sensors_only/battery_saving/high_accuracy"
            }
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, modeInt)
            Log.i(TAG, "Location mode set to $mode ($modeInt)")
            "Location mode set to $mode"
        } catch (e: SecurityException) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS not granted", e)
            "Permission denied. Grant via: adb shell pm grant com.mqttai.mdm android.permission.WRITE_SECURE_SETTINGS"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set location mode", e)
            "Error: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    fun getLocationMode(context: Context): String {
        return try {
            when (Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)) {
                Settings.Secure.LOCATION_MODE_OFF -> "off"
                Settings.Secure.LOCATION_MODE_SENSORS_ONLY -> "sensors_only"
                Settings.Secure.LOCATION_MODE_BATTERY_SAVING -> "battery_saving"
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY -> "high_accuracy"
                else -> "unknown"
            }
        } catch (_: Exception) { "unknown" }
    }
}
