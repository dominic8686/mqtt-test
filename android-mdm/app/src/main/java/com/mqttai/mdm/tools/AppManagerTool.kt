package com.mqttai.mdm.tools

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object AppManagerTool {
    private const val TAG = "AppManagerTool"

    /**
     * Launch an app by package name.
     */
    fun launchApp(context: Context, packageName: String): String {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return "App not found: $packageName"
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "Launched $packageName")
            "Launched $packageName"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Force-stop an app (requires root or device-owner).
     */
    fun forceStopApp(packageName: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "am force-stop $packageName"))
            val exit = proc.waitFor()
            if (exit == 0) {
                Log.i(TAG, "Force-stopped $packageName")
                "Force-stopped $packageName"
            } else {
                "force-stop failed (exit=$exit). May need root."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force-stop $packageName", e)
            "Error: ${e.message}"
        }
    }

    /**
     * List installed apps (user-installed only by default).
     */
    fun listApps(context: Context, includeSystem: Boolean = false): String {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val arr = JSONArray()
            for (app in apps) {
                if (!includeSystem && (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
                val obj = JSONObject()
                obj.put("package", app.packageName)
                obj.put("name", pm.getApplicationLabel(app).toString())
                arr.put(obj)
            }
            arr.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list apps", e)
            "Error: ${e.message}"
        }
    }
}
