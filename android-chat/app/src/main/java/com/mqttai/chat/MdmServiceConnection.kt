package com.mqttai.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.mqttai.mdm.IMdmChatCallback
import com.mqttai.mdm.IMdmService

/**
 * Manages the connection to the MDM system app's bound service.
 */
class MdmServiceConnection(
    private val onConnected: (IMdmService) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        private const val TAG = "MdmServiceConnection"
        private const val MDM_SERVICE_ACTION = "com.mqttai.mdm.MdmService"
        private const val MDM_PACKAGE = "com.mqttai.mdm"
    }

    var service: IMdmService? = null
        private set

    val isConnected: Boolean get() = service != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IMdmService.Stub.asInterface(binder)
            Log.i(TAG, "Connected to MDM service")
            onConnected(service!!)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            Log.w(TAG, "Disconnected from MDM service")
            onDisconnected()
        }
    }

    fun bind(context: Context) {
        val intent = Intent(MDM_SERVICE_ACTION).apply {
            setPackage(MDM_PACKAGE)
        }
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "Bind to MDM service: $bound")
    }

    fun unbind(context: Context) {
        try {
            service = null
            context.unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding", e)
        }
    }
}
