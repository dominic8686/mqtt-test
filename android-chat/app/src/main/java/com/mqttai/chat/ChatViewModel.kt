package com.mqttai.chat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mqttai.chat.tools.CalculateTool
import com.mqttai.chat.tools.LocalRouter
import com.mqttai.chat.tools.YouTubeTool
import com.mqttai.mdm.IMdmChatCallback
import com.mqttai.mdm.IMdmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class MessageSource { USER, LOCAL, CLOUD }
data class ChatMessage(val text: String, val isUser: Boolean, val source: MessageSource = MessageSource.USER)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val displayValue = mutableStateOf("0")
    val chatMessages = mutableStateListOf<ChatMessage>()
    val inputText = mutableStateOf("")
    val isPending = mutableStateOf(false)
    val isMdmConnected = mutableStateOf(false)
    val localRouterReady = mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    /* ── MDM Service Connection ── */

    private val mdmConnection = MdmServiceConnection(
        onConnected = { service ->
            isMdmConnected.value = true
            service.registerChatCallback(chatCallback)
            Log.i(TAG, "MDM connected, deviceId=${service.deviceId}")
        },
        onDisconnected = {
            isMdmConnected.value = false
            Log.w(TAG, "MDM disconnected")
        }
    )

    private val chatCallback = object : IMdmChatCallback.Stub() {
        override fun onCloudResponse(text: String) {
            mainHandler.post {
                chatMessages.add(ChatMessage(text, isUser = false, source = MessageSource.CLOUD))
                isPending.value = false
                // Try to extract a number for the display
                val number = text.replace(Regex("[^0-9.\\-]"), " ")
                    .split(" ")
                    .lastOrNull { it.toDoubleOrNull() != null }
                if (number != null) {
                    displayValue.value = formatNumber(number.toDouble())
                }
            }
        }

        override fun onToolCallFromCloud(callId: String, toolName: String, argsJson: String) {
            mainHandler.post {
                handleCloudToolCall(callId, toolName, argsJson)
            }
        }
    }

    init {
        // Load local model in background
        viewModelScope.launch {
            LocalRouter.init(getApplication())
            localRouterReady.value = LocalRouter.isReady()
            Log.i(TAG, "Local router ready: ${localRouterReady.value}")
        }

        // Bind to MDM service
        mdmConnection.bind(getApplication())
    }

    /* ── Send Message ── */

    fun send() {
        val text = inputText.value.trim()
        if (text.isEmpty()) return
        chatMessages.add(ChatMessage(text, isUser = true))
        inputText.value = ""
        isPending.value = true

        viewModelScope.launch {
            val decision = LocalRouter.route(text)
            if (decision.route == "local" && decision.tool != null && decision.args != null) {
                Log.i(TAG, "LOCAL route: ${decision.tool}")
                executeToolLocally(decision.tool, decision.args)
            } else {
                Log.i(TAG, "CLOUD route")
                sendToCloud(text)
            }
        }
    }

    /* ── Local Tool Execution ── */

    private suspend fun executeToolLocally(tool: String, args: JSONObject) {
        when (tool) {
            "calculate" -> {
                val expression = args.optString("expression", "")
                try {
                    val result = CalculateTool.evaluate(expression)
                    displayValue.value = formatNumber(result)
                    chatMessages.add(
                        ChatMessage("$expression = ${formatNumber(result)}", isUser = false, source = MessageSource.LOCAL)
                    )
                } catch (e: Exception) {
                    chatMessages.add(ChatMessage("Error: ${e.message}", isUser = false, source = MessageSource.LOCAL))
                }
                isPending.value = false
            }
            "toggle_wifi" -> {
                // Hardware tool → delegate to MDM service
                val service = mdmConnection.service
                if (service != null) {
                    val resultMsg = withContext(Dispatchers.IO) {
                        service.executeTool("toggle_wifi", args.toString())
                    }
                    chatMessages.add(ChatMessage(resultMsg, isUser = false, source = MessageSource.LOCAL))
                } else {
                    chatMessages.add(ChatMessage("MDM service not connected", isUser = false, source = MessageSource.LOCAL))
                }
                isPending.value = false
            }
            "play_youtube" -> {
                val query = args.optString("query", "")
                val videoUrl = args.optString("videoUrl", null)
                val result = YouTubeTool.play(getApplication(), query, videoUrl)
                chatMessages.add(ChatMessage(result.message, isUser = false, source = MessageSource.LOCAL))
                isPending.value = false
            }
            else -> {
                // Unknown local tool — send to cloud
                sendToCloud(args.toString())
            }
        }
    }

    /* ── Cloud Messaging via MDM ── */

    private fun sendToCloud(text: String) {
        val service = mdmConnection.service
        if (service != null) {
            service.sendCloudMessage(text)
        } else {
            chatMessages.add(ChatMessage("MDM service not connected. Please start the MDM app.", isUser = false, source = MessageSource.LOCAL))
            isPending.value = false
        }
    }

    /* ── Cloud-Initiated Tool Calls ── */

    private fun handleCloudToolCall(callId: String, toolName: String, argsJson: String) {
        val args = JSONObject(argsJson)
        when (toolName) {
            "calculate" -> {
                val expression = args.getString("expression")
                try {
                    val result = CalculateTool.evaluate(expression)
                    displayValue.value = formatNumber(result)
                    // Send result back to cloud via MDM
                    mdmConnection.service?.sendCloudMessage("") // placeholder — actual result goes via AIDL
                    // TODO: need a sendToolResult method exposed via AIDL
                } catch (e: Exception) {
                    Log.e(TAG, "Cloud tool call failed", e)
                }
            }
            "play_youtube" -> {
                val query = args.getString("query")
                val videoUrl = args.optString("videoUrl", null)
                YouTubeTool.play(getApplication(), query, videoUrl)
            }
        }
    }

    /* ── Helpers ── */

    private fun formatNumber(d: Double): String {
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    override fun onCleared() {
        try {
            mdmConnection.service?.unregisterChatCallback(chatCallback)
        } catch (_: Exception) {}
        mdmConnection.unbind(getApplication())
        LocalRouter.shutdown()
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
