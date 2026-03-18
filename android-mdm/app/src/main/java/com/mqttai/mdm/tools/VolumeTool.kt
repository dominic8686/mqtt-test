package com.mqttai.mdm.tools

import android.content.Context
import android.media.AudioManager
import android.util.Log

object VolumeTool {
    private const val TAG = "VolumeTool"

    private fun streamType(name: String): Int = when (name.lowercase()) {
        "media", "music" -> AudioManager.STREAM_MUSIC
        "ring", "ringtone" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    /**
     * Set volume for a given stream. Level is 0 to max (stream-dependent).
     */
    fun setVolume(context: Context, stream: String, level: Int): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val type = streamType(stream)
            val max = am.getStreamMaxVolume(type)
            val clamped = level.coerceIn(0, max)
            am.setStreamVolume(type, clamped, 0)
            Log.i(TAG, "$stream volume set to $clamped/$max")
            "$stream volume set to $clamped/$max"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            "Error: ${e.message}"
        }
    }

    fun getVolumes(context: Context): Map<String, Pair<Int, Int>> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return mapOf(
            "media" to (am.getStreamVolume(AudioManager.STREAM_MUSIC) to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
            "ring" to (am.getStreamVolume(AudioManager.STREAM_RING) to am.getStreamMaxVolume(AudioManager.STREAM_RING)),
            "notification" to (am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) to am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)),
            "alarm" to (am.getStreamVolume(AudioManager.STREAM_ALARM) to am.getStreamMaxVolume(AudioManager.STREAM_ALARM)),
        )
    }
}
