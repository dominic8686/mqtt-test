package com.mqttai.mdm

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Minimal launcher activity that starts the MDM foreground service and finishes.
 * The service runs in the background and exposes AIDL for the chat app.
 */
class MdmLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, MdmService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish()
    }
}
