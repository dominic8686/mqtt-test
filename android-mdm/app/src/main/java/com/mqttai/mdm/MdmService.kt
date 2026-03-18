package com.mqttai.mdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import com.mqttai.mdm.tools.*
import org.json.JSONObject

class MdmService : Service() {

    companion object {
        private const val TAG = "MdmService"
        private const val CHANNEL_ID = "mdm_service"
        private const val NOTIFICATION_ID = 1
        // TODO: change this to your PC's local IP when running on a real device
        private const val BROKER_URL = "tcp://10.0.2.2:1883" // 10.0.2.2 = host from Android emulator
    }

    private lateinit var mqttManager: MqttManager
    private val chatCallbacks = RemoteCallbackList<IMdmChatCallback>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        mqttManager = MqttManager(
            brokerUrl = BROKER_URL,
            onChatResponse = { text -> dispatchCloudResponse(text) },
            onToolCall = { callId, name, args -> handleToolCall(callId, name, args) }
        )
        mqttManager.connect()

        // Publish initial Wi-Fi state after a short delay
        android.os.Handler(mainLooper).postDelayed({ publishCurrentWifiStatus() }, 2000)

        Log.i(TAG, "MDM Service started, device=${mqttManager.deviceId}")
    }

    private fun handleToolCall(callId: String, name: String, args: JSONObject) {
        when (name) {
            "toggle_wifi" -> {
                val enabled = args.getBoolean("enabled")
                val result = WifiTool.setWifiEnabled(this, enabled)
                mqttManager.sendToolResult(callId, result.message)
                mqttManager.publishWifiStatus(enabled)
            }
            "set_brightness" -> {
                val level = if (args.has("level")) args.getInt("level") else null
                val auto = if (args.has("auto")) args.getBoolean("auto") else null
                val result = BrightnessTool.setBrightness(this, level, auto)
                mqttManager.sendToolResult(callId, result)
            }
            "toggle_bluetooth" -> {
                val enabled = args.getBoolean("enabled")
                val result = BluetoothTool.setBluetoothEnabled(enabled)
                mqttManager.sendToolResult(callId, result)
            }
            "set_volume" -> {
                val stream = args.optString("stream", "media")
                val level = args.getInt("level")
                val result = VolumeTool.setVolume(this, stream, level)
                mqttManager.sendToolResult(callId, result)
            }
            "set_screen" -> {
                val on = args.getBoolean("on")
                val result = ScreenTool.setScreen(this, on)
                mqttManager.sendToolResult(callId, result)
            }
            "get_device_info" -> {
                val info = DeviceInfoTool.getDeviceInfo(this)
                mqttManager.sendToolResult(callId, info.toString())
            }
            "take_screenshot" -> {
                val result = ScreenshotTool.takeScreenshot()
                mqttManager.sendToolResult(callId, result)
            }
            "manage_app" -> {
                val action = args.getString("action")
                val pkg = args.optString("package_name", "")
                val result = when (action) {
                    "launch" -> AppManagerTool.launchApp(this, pkg)
                    "stop" -> AppManagerTool.forceStopApp(pkg)
                    "list" -> AppManagerTool.listApps(this, args.optBoolean("include_system", false))
                    else -> "Unknown action: $action"
                }
                mqttManager.sendToolResult(callId, result)
            }
            "set_location" -> {
                val mode = args.getString("mode")
                val result = LocationTool.setLocationMode(this, mode)
                mqttManager.sendToolResult(callId, result)
            }
            "power_action" -> {
                val action = args.getString("action")
                val result = PowerTool.powerAction(this, action)
                mqttManager.sendToolResult(callId, result)
            }
            "push_alert" -> {
                val title = args.optString("title", "MDM Alert")
                val message = args.getString("message")
                val method = args.optString("method", "notification")
                val result = AlertTool.pushAlert(this, title, message, method)
                mqttManager.sendToolResult(callId, result)
            }
            else -> {
                // Not a hardware tool — forward to chat app via callback
                dispatchToolCallToChat(callId, name, args)
            }
        }
    }

    private fun dispatchCloudResponse(text: String) {
        val n = chatCallbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    chatCallbacks.getBroadcastItem(i).onCloudResponse(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error dispatching cloud response", e)
                }
            }
        } finally {
            chatCallbacks.finishBroadcast()
        }
    }

    private fun dispatchToolCallToChat(callId: String, name: String, argsJson: JSONObject) {
        val n = chatCallbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    chatCallbacks.getBroadcastItem(i)
                        .onToolCallFromCloud(callId, name, argsJson.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Error dispatching tool call to chat", e)
                }
            }
        } finally {
            chatCallbacks.finishBroadcast()
        }
    }

    @Suppress("DEPRECATION")
    private fun publishCurrentWifiStatus() {
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        mqttManager.publishWifiStatus(wm.isWifiEnabled)
    }

    /* ── AIDL Binder ── */

    private val binder = object : IMdmService.Stub() {

        override fun getDeviceId(): String = mqttManager.deviceId

        override fun executeTool(toolName: String, argsJson: String): String {
            val args = JSONObject(argsJson)
            return when (toolName) {
                "toggle_wifi" -> {
                    val enabled = args.getBoolean("enabled")
                    val result = WifiTool.setWifiEnabled(this@MdmService, enabled)
                    mqttManager.publishWifiStatus(enabled)
                    result.message
                }
                "set_brightness" -> BrightnessTool.setBrightness(this@MdmService, args.optInt("level", -1).let { if (it == -1) null else it }, if (args.has("auto")) args.getBoolean("auto") else null)
                "toggle_bluetooth" -> BluetoothTool.setBluetoothEnabled(args.getBoolean("enabled"))
                "set_volume" -> VolumeTool.setVolume(this@MdmService, args.optString("stream", "media"), args.getInt("level"))
                "set_screen" -> ScreenTool.setScreen(this@MdmService, args.getBoolean("on"))
                "get_device_info" -> DeviceInfoTool.getDeviceInfo(this@MdmService).toString()
                "take_screenshot" -> ScreenshotTool.takeScreenshot()
                "manage_app" -> {
                    when (args.getString("action")) {
                        "launch" -> AppManagerTool.launchApp(this@MdmService, args.getString("package_name"))
                        "stop" -> AppManagerTool.forceStopApp(args.getString("package_name"))
                        "list" -> AppManagerTool.listApps(this@MdmService, args.optBoolean("include_system", false))
                        else -> "Unknown manage_app action"
                    }
                }
                "set_location" -> LocationTool.setLocationMode(this@MdmService, args.getString("mode"))
                "power_action" -> PowerTool.powerAction(this@MdmService, args.getString("action"))
                "push_alert" -> AlertTool.pushAlert(this@MdmService, args.optString("title", "MDM Alert"), args.getString("message"), args.optString("method", "notification"))
                else -> "Unknown hardware tool: $toolName"
            }
        }

        override fun sendCloudMessage(text: String) {
            mqttManager.sendChatMessage(text)
        }

        override fun registerChatCallback(callback: IMdmChatCallback) {
            chatCallbacks.register(callback)
        }

        override fun unregisterChatCallback(callback: IMdmChatCallback) {
            chatCallbacks.unregister(callback)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        mqttManager.disconnect()
        chatCallbacks.kill()
        super.onDestroy()
    }

    /* ── Notification ── */

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MDM Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Device management service"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MDM Active")
                .setContentText("Device management service running")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MDM Active")
                .setContentText("Device management service running")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()
        }
    }

    /**
     * Send a tool result back to the cloud (called by chat app for app-level tool results).
     */
    fun sendToolResultToCloud(callId: String, value: Any) {
        mqttManager.sendToolResult(callId, value)
    }
}
