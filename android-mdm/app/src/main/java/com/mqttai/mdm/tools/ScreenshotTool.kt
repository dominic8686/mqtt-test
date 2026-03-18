package com.mqttai.mdm.tools

import android.os.Environment
import android.util.Base64
import android.util.Log
import java.io.File

object ScreenshotTool {
    private const val TAG = "ScreenshotTool"

    /**
     * Take a screenshot via `screencap` shell command.
     * Works on rooted devices or when app is granted CAPTURE privileges via adb.
     * Returns a base64-encoded PNG or an error message.
     */
    fun takeScreenshot(): String {
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            dir.mkdirs()
            val file = File(dir, "mdm_screenshot_${System.currentTimeMillis()}.png")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p ${file.absolutePath}"))
            val exitCode = process.waitFor()
            if (exitCode != 0 || !file.exists()) {
                return "Screenshot failed (exit=$exitCode). Device may need root or adb permissions."
            }
            val bytes = file.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Log.i(TAG, "Screenshot saved: ${file.absolutePath} (${bytes.size} bytes)")
            // Return path + truncated base64 preview
            "Screenshot saved to ${file.absolutePath} (${bytes.size} bytes)"
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            "Error: ${e.message}"
        }
    }
}
