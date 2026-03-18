package com.mqttai.mdm.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import org.json.JSONObject

object DeviceInfoTool {
    private const val TAG = "DeviceInfoTool"

    fun getDeviceInfo(context: Context): JSONObject {
        val info = JSONObject()
        try {
            // Device
            info.put("manufacturer", Build.MANUFACTURER)
            info.put("model", Build.MODEL)
            info.put("androidVersion", Build.VERSION.RELEASE)
            info.put("sdkLevel", Build.VERSION.SDK_INT)

            // Battery
            val batteryIntent = context.registerReceiver(null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (scale > 0) (level * 100 / scale) else -1
                val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                info.put("batteryPercent", pct)
                info.put("isCharging", plugged != 0)
            }

            // Storage
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            info.put("storageFreeGB", String.format("%.1f", freeBytes / 1e9))
            info.put("storageTotalGB", String.format("%.1f", totalBytes / 1e9))

            // RAM
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            info.put("ramFreeGB", String.format("%.1f", memInfo.availMem / 1e9))
            info.put("ramTotalGB", String.format("%.1f", memInfo.totalMem / 1e9))

        } catch (e: Exception) {
            Log.e(TAG, "Error gathering device info", e)
            info.put("error", e.message)
        }
        return info
    }
}
