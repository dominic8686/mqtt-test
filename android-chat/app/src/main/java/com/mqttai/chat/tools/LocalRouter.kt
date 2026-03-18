package com.mqttai.chat.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class RouteDecision(
    val route: String, // "local" or "cloud"
    val tool: String? = null,
    val args: JSONObject? = null
)

object LocalRouter {
    private const val TAG = "LocalRouter"
    private const val MODEL_FILENAME = "model.gguf"

    private var initialized = false

    private val ROUTING_PROMPT = """Classify the intent. Reply with ONLY one label.
Labels: WIFI_ON, WIFI_OFF, CALCULATE, YOUTUBE, CLOUD

Rules:
- WIFI_ON: message asks to enable/turn on wifi
- WIFI_OFF: message asks to disable/turn off wifi
- CALCULATE: message contains numbers and math
- YOUTUBE: message asks to play music, a song, a video, or open youtube
- CLOUD: everything else (questions, conversation, no math)

Examples:
"turn on wifi" -> WIFI_ON
"disable wifi" -> WIFI_OFF
"turn off the wifi" -> WIFI_OFF
"enable wifi" -> WIFI_ON
"6+6" -> CALCULATE
"what is 10 times 5" -> CALCULATE
"play rick astley" -> YOUTUBE
"play never gonna give you up" -> YOUTUBE
"play some music" -> YOUTUBE
"open youtube" -> YOUTUBE
"play despacito" -> YOUTUBE
"whats the meaning of life" -> CLOUD
"hello" -> CLOUD
"explain gravity" -> CLOUD
"how are you" -> CLOUD
"tell me a joke" -> CLOUD

Message: """

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, MODEL_FILENAME)
        if (!modelFile.exists()) {
            Log.w(TAG, "Model file not found at ${modelFile.absolutePath}")
            Log.w(TAG, "Push model with: adb push $MODEL_FILENAME /data/data/${context.packageName}/files/")
            return@withContext
        }
        val loaded = LlamaInference.loadModel(modelFile.absolutePath)
        initialized = loaded
        Log.i(TAG, if (loaded) "Local router ready" else "Failed to load model")
    }

    suspend fun route(userMessage: String): RouteDecision = withContext(Dispatchers.IO) {
        if (!initialized || !LlamaInference.isLoaded()) {
            Log.d(TAG, "Model not loaded, falling back to cloud")
            return@withContext RouteDecision("cloud")
        }

        try {
            val prompt = ROUTING_PROMPT + "\"" + userMessage + "\" ->"
            val output = LlamaInference.complete(prompt, 10).trim().uppercase()
            Log.d(TAG, "Model output: $output")

            // Extract the first recognized label from the output
            val label = listOf("WIFI_ON", "WIFI_OFF", "CALCULATE", "YOUTUBE", "CLOUD")
                .firstOrNull { output.contains(it) } ?: "CLOUD"

            Log.i(TAG, "Classified intent: $label")

            when (label) {
                "WIFI_ON" -> RouteDecision(
                    "local", "toggle_wifi",
                    JSONObject().put("enabled", true)
                )
                "WIFI_OFF" -> RouteDecision(
                    "local", "toggle_wifi",
                    JSONObject().put("enabled", false)
                )
                "CALCULATE" -> {
                    val expr = extractExpression(userMessage)
                    if (expr.isBlank() || !expr.any { it.isDigit() }) {
                        RouteDecision("cloud")
                    } else {
                        RouteDecision(
                            "local", "calculate",
                            JSONObject().put("expression", expr)
                        )
                    }
                }
                "YOUTUBE" -> {
                    val query = extractYouTubeQuery(userMessage)
                    RouteDecision(
                        "local", "play_youtube",
                        JSONObject().put("query", query)
                    )
                }
                else -> RouteDecision("cloud")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Routing failed, falling back to cloud", e)
            RouteDecision("cloud")
        }
    }

    /** Extract a math expression from natural language */
    private fun extractExpression(input: String): String {
        // Try to find a pure math expression first (e.g. "6+6", "10 * 5")
        val mathPattern = Regex("""[\d.]+\s*[+\-*/^]\s*[\d.]+(?:\s*[+\-*/^]\s*[\d.]+)*""")
        mathPattern.find(input)?.let { return it.value.replace(" ", "") }

        // Replace words with operators
        return input.lowercase()
            .replace("plus", "+").replace("minus", "-")
            .replace("times", "*").replace("multiplied by", "*")
            .replace("divided by", "/").replace("over", "/")
            .replace("power", "^").replace("to the", "^")
            .replace(Regex("what is|calculate|compute|what's|equals"), "")
            .trim()
    }

    /** Extract a YouTube search query from natural language */
    private fun extractYouTubeQuery(input: String): String {
        return input.lowercase()
            .replace(Regex("(play|open|search|find|put on|on youtube|youtube|music video|video|song)"), "")
            .trim()
            .ifBlank { input }
    }

    fun isReady(): Boolean = initialized && LlamaInference.isLoaded()

    fun shutdown() {
        if (initialized) {
            LlamaInference.unloadModel()
            initialized = false
        }
    }
}
