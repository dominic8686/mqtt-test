# MQTT AI Calculator

A proof-of-concept full-stack project: a native Android calculator app with MQTT messaging, OpenAI tool-calling backend, on-device LLM routing, and a web dashboard for remote device control.

## Architecture

```
┌─────────────┐   MQTT    ┌─────────────────┐   HTTP   ┌───────────┐
│  Android App│ ◄────────►│  Node.js Server  │◄────────►│ Dashboard │
│  (Kotlin)   │           │  (OpenAI + MQTT) │   SSE    │  (Web UI) │
└─────────────┘           └─────────────────┘          └───────────┘
       │                         │
  On-device LLM            Docker Compose
  (llama.cpp)              (Mosquitto + Server)
```

**Key features:**
- Calculator UI with chat interface (Jetpack Compose)
- Local intent classification via on-device Qwen2.5-0.5B model (llama.cpp + NDK)
- Cloud fallback to OpenAI gpt-4o-mini with tool calling
- Wi-Fi toggle tool (controlled from app chat or web dashboard)
- Real-time web dashboard with SSE for device state monitoring
- MQTT broker (Mosquitto) for all communication

## AI Models

This project uses two AI models with an on-device router that decides which one handles each request:

| Model | Location | Role | Used For |
|-------|----------|------|----------|
| **Qwen2.5-0.5B-Instruct** | On-device (llama.cpp) | Intent classifier | Wi-Fi toggle, basic math |
| **OpenAI gpt-4o-mini** | Cloud (Docker server) | Full assistant | Complex queries, conversation |

The on-device model (~469MB GGUF) runs inference locally via llama.cpp/NDK and classifies user messages into one of four intents: `WIFI_ON`, `WIFI_OFF`, `CALCULATE`, or `CLOUD`. Simple commands execute locally with zero latency; everything else goes to OpenAI.

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
├── android/
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── cpp/
│   │       │   ├── CMakeLists.txt    # llama.cpp native build
│   │       │   └── llm_bridge.cpp    # JNI bridge
│   │       └── java/com/mqttai/calc/
│   │           ├── MainActivity.kt
│   │           ├── CalcViewModel.kt
│   │           ├── MqttAiClient.kt
│   │           └── tools/
│   │               ├── CalculateTool.kt
│   │               ├── WifiTool.kt
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
- Android emulator (API 29+ recommended)
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
git clone https://github.com/ggerganov/llama.cpp android/app/src/main/cpp/llama.cpp
```

### 4. Build & Install Android App

```bash
cd android
gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 5. Grant Permission (emulator only)

```bash
adb shell pm grant com.mqttai.calc android.permission.WRITE_SECURE_SETTINGS
```

### 6. Push Model to Device (for local routing)

Download [Qwen2.5-0.5B-Instruct Q4_K_M GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) and push:

```bash
adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /data/data/com.mqttai.calc/files/model.gguf
```

### 7. Open Dashboard

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

- The Android app targets SDK 28 to allow `WifiManager.setWifiEnabled()` on API 29 emulators
- The on-device model handles simple intents (wifi on/off, basic math); complex queries go to OpenAI
- The emulator's ABI filter is set to `x86_64` — change to `arm64-v8a` for real devices
- See [agent.md](agent.md) for detailed AI model architecture and routing documentation
