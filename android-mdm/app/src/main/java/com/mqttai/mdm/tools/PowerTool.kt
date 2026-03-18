package com.mqttai.mdm.tools

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

object PowerTool {
    private const val TAG = "PowerTool"

    /**
     * Perform a power action: "sleep" (lock screen) or "reboot".
     * - sleep: uses DevicePolicyManager.lockNow() if device admin, else falls back to shell.
     * - reboot: requires root or device-owner.
     */
    fun powerAction(context: Context, action: String): String {
        return try {
            when (action.lowercase()) {
                "sleep", "lock" -> lockScreen(context)
                "reboot", "restart" -> reboot()
                else -> "Unknown action: $action. Use sleep or reboot."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Power action failed", e)
            "Error: ${e.message}"
        }
    }

    private fun lockScreen(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            Log.i(TAG, "Screen locked via DevicePolicyManager")
            "Screen locked"
        } catch (e: SecurityException) {
            // Fallback to shell command
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 26"))
                proc.waitFor()
                Log.i(TAG, "Screen locked via keyevent")
                "Screen locked (via keyevent)"
            } catch (e2: Exception) {
                "Lock failed. Device admin not enabled and keyevent fallback failed: ${e2.message}"
            }
        }
    }

    private fun reboot(): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "reboot"))
            val exit = proc.waitFor()
            if (exit == 0) "Rebooting..." else "Reboot failed (exit=$exit). Requires root."
        } catch (e: Exception) {
            "Reboot failed: ${e.message}. Requires root or device-owner."
        }
    }
}
