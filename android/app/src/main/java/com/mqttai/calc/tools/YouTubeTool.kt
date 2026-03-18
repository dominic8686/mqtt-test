package com.mqttai.calc.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLEncoder

data class YouTubeResult(val success: Boolean, val message: String)

object YouTubeTool {
    private const val TAG = "YouTubeTool"

    fun play(context: Context, query: String): YouTubeResult {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val uri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Opening YouTube: $query")
            YouTubeResult(true, "Opening YouTube: $query")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open YouTube", e)
            YouTubeResult(false, "Error opening YouTube: ${e.message}")
        }
    }
}
