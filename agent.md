# AI Agent Architecture

This project uses a **dual-model routing architecture** split across **two standalone Android projects**:
- **MDM System App** (`android-mdm/`, package `com.mqttai.mdm`) — owns the MQTT connection and hardware/system tools (Wi-Fi, Bluetooth, brightness, volume, screen, location, power, screenshots, app management, alerts)
- **Chat App** (`android-chat/`, package `com.mqttai.chat`) — chat UI, on-device LLM router, and app-level tools (calculator, YouTube)

Each app is a fully independent Android project with its own Gradle wrapper and can be opened/built separately. The Chat App binds to the MDM service via AIDL. Simple intents are handled locally; complex queries are forwarded to a cloud LLM via MQTT through the MDM app.

## Models

### On-Device Model (Local)

- **Model**: [Qwen2.5-0.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) (Q4_K_M quantization)
- **Size**: ~469 MB GGUF file
- **Runtime**: [llama.cpp](https://github.com/ggerganov/llama.cpp) compiled as a static C++ library via Android NDK
- **Purpose**: Intent classification only — determines whether a user message should be handled locally or sent to the cloud
- **Location on device**: `/data/data/com.mqttai.chat/files/model.gguf`
- **Not bundled in the repo** — must be downloaded from HuggingFace and pushed to the device via ADB

#### How it's integrated

```
LlamaInference.kt  →  JNI  →  llm_bridge.cpp  →  llama.cpp (C++)
     (Kotlin)                    (C++ JNI)          (static lib)
```

1. **`llm_bridge.cpp`** — JNI bridge exposing `loadModel()`, `complete()`, `unloadModel()`, `isLoaded()` to Kotlin
2. **`LlamaInference.kt`** — Kotlin wrapper that loads the native library (`libllm-bridge.so`) and exposes the JNI functions
3. **`CMakeLists.txt`** — Builds llama.cpp as a static library and links it into the JNI bridge. CPU-only, no OpenMP, targeting x86_64 (emulator) or arm64-v8a (real device)

#### Intent classification

The on-device model is used exclusively as an **intent classifier**, not as a general-purpose chatbot. `LocalRouter.kt` sends a structured prompt with few-shot examples:

```
Classify the intent. Reply with ONLY one label.
Labels: WIFI_ON, WIFI_OFF, CALCULATE, CLOUD

Examples:
"turn on wifi" -> WIFI_ON
"6+6" -> CALCULATE
"explain gravity" -> CLOUD

Message: "<user input>" ->
```

The model outputs one of four labels:

| Label | Action | Handled by |
|-------|--------|------------|
| `WIFI_ON` | Turn Wi-Fi on | MDM app → `WifiTool.kt` (via AIDL) |
| `WIFI_OFF` | Turn Wi-Fi off | MDM app → `WifiTool.kt` (via AIDL) |
| `CALCULATE` | Evaluate math expression | Chat app → `CalculateTool.kt` (local) |
| `YOUTUBE` | Open YouTube and search | Chat app → `YouTubeTool.kt` (local) |
| `CLOUD` | Forward to OpenAI | MDM app → MQTT → Server |

**Safety net**: If the model classifies as CALCULATE but no digits are found in the expression, it falls back to CLOUD.

### Cloud Model (Remote)

- **Model**: OpenAI `gpt-4o-mini`
- **API**: OpenAI Chat Completions with tool calling
- **Location**: `server/index.ts` — runs inside a Docker container
- **Purpose**: Handles complex queries, general conversation, and tool orchestration via OpenAI function calling

#### Tools registered with OpenAI

The server registers the following tools that the cloud model can invoke:

**App-level tools** (handled by Chat App):
1. **`calculate`** — Evaluates a math expression (e.g. `6+6`). Sent to phone via MQTT; evaluated locally with `CalculateTool.kt`.
2. **`play_youtube`** — Opens YouTube on the phone and searches for a video/song.

**MDM hardware/system tools** (handled by MDM App directly):
3. **`toggle_wifi`** — Turns Wi-Fi on or off via `WifiTool.kt`. Requires `CHANGE_WIFI_STATE`.
4. **`set_brightness`** — Sets screen brightness (0–255) or toggles auto-brightness. Requires `WRITE_SETTINGS`.
5. **`toggle_bluetooth`** — Enables or disables Bluetooth. Requires `BLUETOOTH_ADMIN` (targetSdk ≤ 28).
6. **`set_volume`** — Sets volume for a stream (media/ring/notification/alarm/system). No extra permissions.
7. **`set_screen`** — Turns screen on (wake lock) or off (release). Requires `WAKE_LOCK`.
8. **`get_device_info`** — Returns battery %, storage, RAM, model, Android version as JSON.
9. **`take_screenshot`** — Takes a screenshot via `screencap` shell command. Requires root or adb.
10. **`manage_app`** — Launch, force-stop, or list installed apps. Actions: `launch`, `stop`, `list`.
11. **`set_location`** — Sets location mode (off/sensors_only/battery_saving/high_accuracy). Requires `WRITE_SECURE_SETTINGS` (granted via adb).
12. **`power_action`** — Sleep (lock screen) or reboot. Sleep uses DevicePolicyManager or keyevent fallback; reboot requires root.
13. **`push_alert`** — Sends a notification or toast to the device. Requires `SYSTEM_ALERT_WINDOW` for overlays.

#### Cloud flow

```
User types message in Chat App
    → LocalRouter classifies as CLOUD
    → ChatViewModel calls mdmService.sendCloudMessage(text)
    → MDM publishes to MQTT topic calc/{deviceId}/chat/in
    → Server receives, sends to OpenAI with conversation history
    → OpenAI responds (text or tool_call)
    → If tool_call:
        → Server publishes to calc/{deviceId}/tools/call
        → MDM receives tool call:
            → Hardware tool (toggle_wifi) → MDM executes directly
            → App-level tool (calculate, youtube) → forwarded to Chat App via AIDL callback
        → Result published to calc/{deviceId}/tools/result
        → Server feeds result back to OpenAI for final response
    → Final text response published to calc/{deviceId}/chat/out
    → MDM receives it, dispatches via AIDL callback → onCloudResponse()
    → Chat App displays response with CLOUD tag
```

## Routing Decision Flow

```
User Input
    │
    ▼
LocalRouter.route(message)
    │
    ├─ Model not loaded? ──────────► CLOUD (fallback)
    │
    ├─ Run Qwen2.5 inference
    │   (prompt + 10 max tokens)
    │
    ▼
Parse output label
    │
    ├─ WIFI_ON  ──► MDM.executeTool("toggle_wifi")  [LOCAL → AIDL]
    ├─ WIFI_OFF ──► MDM.executeTool("toggle_wifi")  [LOCAL → AIDL]
    ├─ CALCULATE ─► CalculateTool.evaluate(expr)     [LOCAL]
    │               (if no digits found → CLOUD)
    ├─ YOUTUBE ──► YouTubeTool.play(query)           [LOCAL]
    └─ CLOUD ─────► MDM.sendCloudMessage() → MQTT   [CLOUD]
```

## UI Indication

The chat UI shows the source of each response:
- **LOCAL** (green tag) — answered by the on-device model + local tool execution
- **CLOUD** (blue tag) — answered by OpenAI via the server

## Dashboard (Server-Side)

The web dashboard at `http://localhost:3001` can also trigger tool calls directly:
- **Wi-Fi toggle buttons** send tool calls via MQTT to the phone, bypassing OpenAI entirely
- **Chat input** sends messages through the full OpenAI pipeline
- **SSE (Server-Sent Events)** push real-time Wi-Fi state and chat updates to the browser

## Model Files — Where to Get Them

The on-device GGUF model is **not included in the repository**. To set it up:

1. Download from HuggingFace:
   https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF
   (get the `qwen2.5-0.5b-instruct-q4_k_m.gguf` file)

2. Push to the emulator/device:
   ```bash
   adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /data/data/com.mqttai.chat/files/model.gguf
   ```

3. The Chat app loads the model on startup in `ChatViewModel.init()` → `LocalRouter.init()` → `LlamaInference.loadModel()`

The OpenAI API key goes in `.env` at the project root (not committed to git).
