# MQTT AI Calculator

A proof-of-concept full-stack project: two Android apps (MDM system service + chat UI) with MQTT messaging, OpenAI tool-calling backend, on-device LLM routing, and a web dashboard for remote device control.

## Architecture

```
┌──────────────────────┐          ┌──────────────────────────┐
│   Chat App           │  AIDL /  │   MDM System App         │
│   (com.mqttai.chat)  │◄────────►│   (com.mqttai.mdm)       │
│                      │  Bind    │                          │     ┌─────────────────┐   HTTP   ┌───────────┐
│  - Chat UI (Compose) │          │  - MQTT connection       │◄───►│  Node.js Server  │◄────────►│ Dashboard │
│  - LocalRouter + LLM │          │  - WifiTool, Bluetooth   │MQTT │  (OpenAI + MQTT) │   SSE    │  (Web UI) │
│  - CalculateTool     │          │  - Volume, Brightness    │     └─────────────────┘          └───────────┘
│                      │          │  - Screen, Power, etc.   │            │
│                      │          │  - Foreground Service    │       Docker Compose
└──────────────────────┘          └──────────────────────────┘    (Mosquitto + Server)
```

**Key features:**
- Two-app architecture: MDM system service + chat UI (AIDL IPC)
- Chat UI with calculator display (Jetpack Compose)
- Local intent classification via on-device Qwen2.5-0.5B model (llama.cpp + NDK)
- Cloud fallback to OpenAI gpt-4o-mini with tool calling
- 12 MDM tools: Wi-Fi, Bluetooth, volume, brightness, screen, location, power, screenshots, app management, device info, alerts
- MDM installed as system priv-app for privileged permissions (WRITE_SECURE_SETTINGS, etc.)
- Real-time web dashboard with SSE for device state monitoring
- MQTT broker (Mosquitto) for all communication

## AI Models

This project uses two AI models with an on-device router that decides which one handles each request:

| Model | Location | Role | Used For |
|-------|----------|------|----------|
| **Qwen2.5-0.5B-Instruct** | On-device (llama.cpp) | Intent classifier | Wi-Fi toggle, basic math |
| **OpenAI gpt-4o-mini** | Cloud (Docker server) | Full assistant | Complex queries, conversation |

The on-device model (~469MB GGUF) runs inference locally via llama.cpp/NDK and classifies user messages into one of three intents: `WIFI_ON`/`WIFI_OFF`, `CALCULATE`, or `CLOUD`. Simple commands execute locally with zero latency; everything else goes to OpenAI which can invoke any of the 12 registered tools.

See **[agent.md](agent.md)** for the full AI architecture, routing logic, integration details, and model setup instructions.

## Project Structure

```
mqtt-test/
├── docker-compose.yml          # Mosquitto + Server
├── server/
│   ├── index.ts                # MQTT subscriber + OpenAI + HTTP dashboard
│   ├── Dockerfile
│   ├── package.json
│   └── tsconfig.json
├── mosquitto/
│   └── mosquitto.conf
├── android-mdm/                # MDM System App (com.mqttai.mdm) — standalone project
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── aidl/com/mqttai/mdm/
│   │       │   ├── IMdmService.aidl
│   │       │   └── IMdmChatCallback.aidl
│   │       └── java/com/mqttai/mdm/
│   │           ├── MdmService.kt       # Foreground service + AIDL binder
│   │           ├── MdmLauncherActivity.kt
│   │           ├── MqttManager.kt      # MQTT connection lifecycle
│   │           └── tools/
│   │               ├── WifiTool.kt
│   │               ├── BluetoothTool.kt
│   │               ├── BrightnessTool.kt
│   │               ├── VolumeTool.kt
│   │               ├── ScreenTool.kt
│   │               ├── ScreenshotTool.kt
│   │               ├── DeviceInfoTool.kt
│   │               ├── LocationTool.kt
│   │               ├── PowerTool.kt
│   │               ├── AppManagerTool.kt
│   │               └── AlertTool.kt
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle/
├── android-chat/               # Chat App (com.mqttai.chat) — standalone project
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── aidl/com/mqttai/mdm/    # AIDL copy (must match mdm)
│   │       ├── cpp/
│   │       │   ├── CMakeLists.txt
│   │       │   ├── llm_bridge.cpp
│   │       │   └── llama.cpp/          # git clone
│   │       └── java/com/mqttai/chat/
│   │           ├── MainActivity.kt
│   │           ├── ChatViewModel.kt
│   │           ├── MdmServiceConnection.kt
│   │           └── tools/
│   │               ├── CalculateTool.kt  # App-level tool
│   │               ├── LocalRouter.kt
│   │               └── LlamaInference.kt
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle/
└── .env                        # OPENAI_API_KEY (not committed)
```

## Prerequisites

- Docker Desktop (Windows)
- Android SDK with NDK 27.0.12077973 and CMake 3.22.1
- Android emulator (AOSP `default` image, API 29+; must support `adb root`)
- OpenAI API key

## Setup

### 1. Environment

```bash
cp server/.env.example .env
# Edit .env and add your OPENAI_API_KEY
```

### 2. Start Backend (Docker)

```bash
docker compose up -d
```

This starts:
- **Mosquitto** MQTT broker on port 1883
- **Node.js server** with OpenAI integration + web dashboard on port 3001

### 3. Clone llama.cpp (for on-device LLM)

```bash
git clone --depth 1 https://github.com/ggerganov/llama.cpp android-chat/app/src/main/cpp/llama.cpp
```

### 4. Build & Install MDM as System App

The MDM needs privileged permissions, so it must be installed as a system priv-app on a rooted emulator:

```bash
# Build
cd android-mdm
gradlew.bat assembleDebug
cd ..

# Root and remount (first time requires reboot)
adb root
adb remount
adb reboot
# Wait for reboot, then:
adb root
adb remount

# Push as system priv-app
adb shell mkdir -p /system/priv-app/MqttAiMdm
adb push android-mdm/app/build/outputs/apk/debug/app-debug.apk /system/priv-app/MqttAiMdm/MqttAiMdm.apk
adb shell chmod 755 /system/priv-app/MqttAiMdm
adb shell chmod 644 /system/priv-app/MqttAiMdm/MqttAiMdm.apk

# Push privapp permissions whitelist (see privapp-permissions-mqttmdm.xml)
adb push privapp-permissions-mqttmdm.xml /system/etc/permissions/
adb reboot
```

### 5. Build & Install Chat App

```bash
cd android-chat
gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
cd ..
```

### 6. Start MDM Service

Open the "MQTT MDM" app on the device — it starts the foreground service and closes.
The service runs in the background and the Chat app will bind to it automatically.

### 7. Push Model to Device (for local routing)

Download [Qwen2.5-0.5B-Instruct Q4_K_M GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) and push:

```bash
adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /data/data/com.mqttai.chat/files/model.gguf
```

### 8. Open Dashboard

Navigate to http://localhost:3001 to view connected devices, toggle Wi-Fi, and send chat messages.

## MQTT Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `calc/{deviceId}/chat/in` | Phone → Server | User chat messages |
| `calc/{deviceId}/chat/out` | Server → Phone | AI responses |
| `calc/{deviceId}/tools/call` | Server → Phone | Tool call requests |
| `calc/{deviceId}/tools/result` | Phone → Server | Tool execution results |
| `calc/{deviceId}/status/wifi` | Phone → Server | Wi-Fi state updates |

## Notes

- The MDM app targets SDK 28 to allow `WifiManager.setWifiEnabled()` on API 29+ emulators
- The Chat app targets SDK 35 (modern) — it does not need the low targetSdk workaround
- The MDM must be installed as a system priv-app for `WRITE_SECURE_SETTINGS` and other privileged permissions
- Requires a rooted AOSP emulator (use `default` system image, not `google_apis_playstore` which blocks `adb root`)
- Launch emulator with `-writable-system` flag to allow pushing to `/system`
- The on-device model handles simple intents (wifi on/off, basic math); complex queries go to OpenAI
- Hardware/system tools are handled by the MDM app; app-level tools (calculator) stay in the Chat app
- The Chat app communicates with the MDM app via AIDL (bound service IPC)
- The emulator's ABI filter is set to `x86_64` — change to `arm64-v8a` for real devices
- See [agent.md](agent.md) for detailed AI model architecture and routing documentation
