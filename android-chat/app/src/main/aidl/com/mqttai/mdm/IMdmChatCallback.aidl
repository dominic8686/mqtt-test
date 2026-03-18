package com.mqttai.mdm;

interface IMdmChatCallback {
    // Called when a cloud (OpenAI) response arrives
    void onCloudResponse(String text);

    // Called when the cloud requests a tool call on the device
    // (for app-level tools like calculate/youtube that the chat app handles)
    void onToolCallFromCloud(String callId, String toolName, String argsJson);
}
