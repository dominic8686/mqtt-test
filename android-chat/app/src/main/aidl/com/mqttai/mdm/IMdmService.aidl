package com.mqttai.mdm;

import com.mqttai.mdm.IMdmChatCallback;

interface IMdmService {
    String getDeviceId();

    // Execute a hardware tool (e.g. "toggle_wifi"). Returns result string.
    String executeTool(String toolName, String argsJson);

    // Send a message to the cloud via MQTT
    void sendCloudMessage(String text);

    // Register/unregister callback for cloud responses and tool calls
    void registerChatCallback(IMdmChatCallback callback);
    void unregisterChatCallback(IMdmChatCallback callback);
}
