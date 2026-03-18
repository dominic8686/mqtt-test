package com.mqttai.mdm.tools

import android.content.Context
import android.os.PowerManager
import android.util.Log

object ScreenTool {
    private const val TAG = "ScreenTool"
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Turn screen on (acquire wake lock) or off (release wake lock).
     * Full screen-off/lock requires DevicePolicyManager which needs device admin enrollment.
     */
    @Suppress("DEPRECATION")
    fun setScreen(context: Context, on: Boolean): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (on) {
                if (wakeLock?.isHeld == true) return "Screen is already on"
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "mqttmdm:screen"
                )
                wakeLock?.acquire(10 * 60 * 1000L) // 10 min max
                Log.i(TAG, "Screen turned on (wake lock acquired)")
                "Screen turned on"
            } else {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
                wakeLock = null
                Log.i(TAG, "Screen wake lock released")
                "Screen wake lock released (screen will turn off per system timeout)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to control screen", e)
            "Error: ${e.message}"
        }
    }

    fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }
}
