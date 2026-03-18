package com.mqttai.calc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID

class MqttAiClient(
    private val brokerUrl: String,
    private val onResponse: (String) -> Unit,
    private val onToolCall: (callId: String, name: String, args: JSONObject) -> Unit,
) {
    private val deviceId = UUID.randomUUID().toString().take(8)
    private val clientId = "calc_$deviceId"
    private var client: MqttAsyncClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val currentDeviceId get() = deviceId

    fun connect() {
        scope.launch {
            try {
                val mqttClient = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
                client = mqttClient

                mqttClient.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String) {
                        Log.d(TAG, "Connected to $serverURI (reconnect=$reconnect)")
                        subscribe()
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "Connection lost", cause)
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        handleMessage(topic, message.toString())
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                }
                mqttClient.connect(options).waitForCompletion()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect", e)
            }
        }
    }

    private fun subscribe() {
        try {
            client?.subscribe("calc/$deviceId/chat/out", 1)
            client?.subscribe("calc/$deviceId/tools/call", 1)
            Log.d(TAG, "Subscribed to topics for device $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe", e)
        }
    }

    private fun handleMessage(topic: String, payload: String) {
        Log.d(TAG, "[$topic] $payload")
        try {
            val json = JSONObject(payload)
            when {
                topic.endsWith("/chat/out") -> {
                    val text = json.optString("text", "")
                    CoroutineScope(Dispatchers.Main).launch { onResponse(text) }
                }
                topic.endsWith("/tools/call") -> {
                    val callId = json.getString("callId")
                    val name = json.getString("name")
                    val args = json.getJSONObject("args")
                    CoroutineScope(Dispatchers.Main).launch { onToolCall(callId, name, args) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }
    }

    fun sendMessage(text: String) {
        scope.launch {
            try {
                val msg = MqttMessage(text.toByteArray()).apply { qos = 1 }
                client?.publish("calc/$deviceId/chat/in", msg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        }
    }

    fun sendToolResult(callId: String, value: Any) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("callId", callId)
                    put("value", value)
                }
                val msg = MqttMessage(json.toString().toByteArray()).apply { qos = 1 }
                client?.publish("calc/$deviceId/tools/result", msg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send tool result", e)
            }
        }
    }

    fun publishWifiStatus(enabled: Boolean) {
        scope.launch {
            try {
                val json = JSONObject().apply { put("enabled", enabled) }
                val msg = MqttMessage(json.toString().toByteArray()).apply { qos = 1 }
                client?.publish("calc/$deviceId/status/wifi", msg)
                Log.d(TAG, "Published Wi-Fi status: $enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish Wi-Fi status", e)
            }
        }
    }

    fun disconnect() {
        try {
            client?.disconnect()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MqttAiClient"
    }
}
