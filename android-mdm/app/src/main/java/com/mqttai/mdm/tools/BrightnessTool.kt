package com.mqttai.mdm.tools

import android.content.Context
import android.provider.Settings
import android.util.Log

object BrightnessTool {
    private const val TAG = "BrightnessTool"

    /**
     * Set screen brightness (0–255) or toggle auto-brightness.
     */
    fun setBrightness(context: Context, level: Int? = null, auto: Boolean? = null): String {
        return try {
            val resolver = context.contentResolver
            if (auto != null) {
                val mode = if (auto)
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
                Log.i(TAG, "Auto-brightness: $auto")
            }
            if (level != null) {
                val clamped = level.coerceIn(0, 255)
                // Switch to manual mode when setting explicit level
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
                Log.i(TAG, "Brightness set to $clamped")
                "Brightness set to $clamped"
            } else if (auto != null) {
                "Auto-brightness ${if (auto) "enabled" else "disabled"}"
            } else {
                "No brightness parameters provided"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
            "Error: ${e.message}"
        }
    }

    fun getCurrentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { -1 }
    }
}
