package com.mqttai.calc

import android.app.Application
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mqttai.calc.tools.CalculateTool
import com.mqttai.calc.tools.LocalRouter
import com.mqttai.calc.tools.WifiTool
import com.mqttai.calc.tools.YouTubeTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class MessageSource { USER, LOCAL, CLOUD }
data class ChatMessage(val text: String, val isUser: Boolean, val source: MessageSource = MessageSource.USER)

class CalcViewModel(application: Application) : AndroidViewModel(application) {
    // TODO: change this to your PC's local IP when running on a real device
    private val brokerUrl = "tcp://10.0.2.2:1883" // 10.0.2.2 = host from Android emulator

    val displayValue = mutableStateOf("0")
    val chatMessages = mutableStateListOf<ChatMessage>()
    val inputText = mutableStateOf("")
    val isConnected = mutableStateOf(false)
    val isPending = mutableStateOf(false)
    val showWifiPanel = mutableStateOf(false)

    private lateinit var mqttClient: MqttAiClient

    val localRouterReady = mutableStateOf(false)

    init {
        // Load local model in background
        viewModelScope.launch {
            LocalRouter.init(getApplication())
            localRouterReady.value = LocalRouter.isReady()
            Log.i("CalcViewModel", "Local router ready: ${localRouterReady.value}")
        }

        mqttClient = MqttAiClient(
            brokerUrl = brokerUrl,
            onResponse = { text ->
                chatMessages.add(ChatMessage(text, isUser = false, source = MessageSource.CLOUD))
                isPending.value = false
                val number = text.replace(Regex("[^0-9.\\-]"), " ")
                    .split(" ")
                    .lastOrNull { it.toDoubleOrNull() != null }
                if (number != null) {
                    displayValue.value = formatNumber(number.toDouble())
                }
            },
            onToolCall = { callId, name, args ->
                when (name) {
                    "calculate" -> {
                        val expression = args.getString("expression")
                        try {
                            val result = CalculateTool.evaluate(expression)
                            displayValue.value = formatNumber(result)
                            mqttClient.sendToolResult(callId, result)
                        } catch (e: Exception) {
                            mqttClient.sendToolResult(callId, "Error: ${e.message}")
                        }
                    }
                    "toggle_wifi" -> {
                        val enabled = args.getBoolean("enabled")
                        val result = WifiTool.setWifiEnabled(getApplication(), enabled)
                        if (result.needsSettingsPanel) {
                            showWifiPanel.value = true
                        }
                        mqttClient.sendToolResult(callId, result.message)
                        mqttClient.publishWifiStatus(enabled)
                    }
                    "play_youtube" -> {
                        val query = args.getString("query")
                        val result = YouTubeTool.play(getApplication(), query)
                        mqttClient.sendToolResult(callId, result.message)
                    }
                    else -> {
                        mqttClient.sendToolResult(callId, "Unknown tool: $name")
                    }
                }
            }
        )
        mqttClient.connect()
        isConnected.value = true

        // Publish initial Wi-Fi state after connection settles
        viewModelScope.launch {
            delay(2000)
            publishCurrentWifiStatus()
        }
    }

    @Suppress("DEPRECATION")
    private fun publishCurrentWifiStatus() {
        val wm = getApplication<Application>()
            .getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager
        mqttClient.publishWifiStatus(wm.isWifiEnabled)
    }

    fun send() {
        val text = inputText.value.trim()
        if (text.isEmpty()) return
        chatMessages.add(ChatMessage(text, isUser = true))
        inputText.value = ""
        isPending.value = true

        viewModelScope.launch {
            val decision = LocalRouter.route(text)
            if (decision.route == "local" && decision.tool != null && decision.args != null) {
                Log.i("CalcViewModel", "LOCAL route: ${decision.tool}")
                executeToolLocally(decision.tool, decision.args)
            } else {
                Log.i("CalcViewModel", "CLOUD route")
                mqttClient.sendMessage(text)
            }
        }
    }

    private fun executeToolLocally(tool: String, args: JSONObject) {
        when (tool) {
            "calculate" -> {
                val expression = args.optString("expression", "")
                try {
                    val result = CalculateTool.evaluate(expression)
                    displayValue.value = formatNumber(result)
                    chatMessages.add(ChatMessage("$expression = ${formatNumber(result)}", isUser = false, source = MessageSource.LOCAL))
                } catch (e: Exception) {
                    chatMessages.add(ChatMessage("Error: ${e.message}", isUser = false, source = MessageSource.LOCAL))
                }
                isPending.value = false
            }
            "toggle_wifi" -> {
                val enabled = args.optBoolean("enabled", true)
                val result = WifiTool.setWifiEnabled(getApplication(), enabled)
                if (result.needsSettingsPanel) {
                    showWifiPanel.value = true
                }
                chatMessages.add(ChatMessage(result.message, isUser = false, source = MessageSource.LOCAL))
                mqttClient.publishWifiStatus(enabled)
                isPending.value = false
            }
            "play_youtube" -> {
                val query = args.optString("query", "")
                val result = YouTubeTool.play(getApplication(), query)
                chatMessages.add(ChatMessage(result.message, isUser = false, source = MessageSource.LOCAL))
                isPending.value = false
            }
            else -> {
                mqttClient.sendMessage(args.toString())
            }
        }
    }

    private fun formatNumber(d: Double): String {
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    override fun onCleared() {
        mqttClient.disconnect()
        LocalRouter.shutdown()
    }
}
