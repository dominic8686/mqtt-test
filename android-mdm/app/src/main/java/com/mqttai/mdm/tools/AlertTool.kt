package com.mqttai.mdm.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

object AlertTool {
    private const val TAG = "AlertTool"
    private const val CHANNEL_ID = "mdm_alerts"
    private const val CHANNEL_NAME = "MDM Alerts"

    /**
     * Push an alert via the given method.
     * @param method: "notification", "toast", or "overlay"
     */
    fun pushAlert(context: Context, title: String, message: String, method: String = "notification"): String {
        return try {
            when (method.lowercase()) {
                "notification" -> showNotification(context, title, message)
                "toast" -> showToast(context, message)
                else -> "Unknown method: $method. Use notification or toast."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Alert failed", e)
            "Error: ${e.message}"
        }
    }

    private fun showNotification(context: Context, title: String, message: String): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel for API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        builder.setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)

        nm.notify(System.currentTimeMillis().toInt(), builder.build())
        Log.i(TAG, "Notification sent: $title")
        return "Notification sent: $title"
    }

    private fun showToast(context: Context, message: String): String {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        Log.i(TAG, "Toast shown: $message")
        return "Toast displayed"
    }
}
