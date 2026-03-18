package com.mqttai.calc.tools

import android.util.Log

object LlamaInference {
    private const val TAG = "LlamaInference"

    init {
        try {
            System.loadLibrary("llm-bridge")
            Log.i(TAG, "Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    external fun loadModel(path: String): Boolean
    external fun complete(prompt: String, maxTokens: Int): String
    external fun unloadModel()
    external fun isLoaded(): Boolean
}
