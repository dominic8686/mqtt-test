# AI Agent Architecture

This project uses a **dual-model routing architecture**: a small on-device LLM handles simple intents locally, while complex queries are forwarded to a cloud LLM via MQTT.

## Models

### On-Device Model (Local)

- **Model**: [Qwen2.5-0.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) (Q4_K_M quantization)
- **Size**: ~469 MB GGUF file
- **Runtime**: [llama.cpp](https://github.com/ggerganov/llama.cpp) compiled as a static C++ library via Android NDK
- **Purpose**: Intent classification only — determines whether a user message should be handled locally or sent to the cloud
- **Location on device**: `/data/data/com.mqttai.calc/files/model.gguf`
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
| `WIFI_ON` | Turn Wi-Fi on | `WifiTool.kt` (local) |
| `WIFI_OFF` | Turn Wi-Fi off | `WifiTool.kt` (local) |
| `CALCULATE` | Evaluate math expression | `CalculateTool.kt` (local) |
| `YOUTUBE` | Open YouTube and search | `YouTubeTool.kt` (local) |
| `CLOUD` | Forward to OpenAI | Server via MQTT |

**Safety net**: If the model classifies as CALCULATE but no digits are found in the expression, it falls back to CLOUD.

### Cloud Model (Remote)

- **Model**: OpenAI `gpt-4o-mini`
- **API**: OpenAI Chat Completions with tool calling
- **Location**: `server/index.ts` — runs inside a Docker container
- **Purpose**: Handles complex queries, general conversation, and tool orchestration via OpenAI function calling

#### Tools registered with OpenAI

The server registers two tools that the cloud model can invoke:

1. **`calculate`** — Evaluates a math expression (e.g. `6+6`). The server sends the tool call to the phone via MQTT; the phone evaluates it locally with `CalculateTool.kt` and returns the result.

2. **`toggle_wifi`** — Turns Wi-Fi on or off. Same flow: server sends tool call via MQTT, phone executes via `WifiTool.kt`, reports result back.

3. **`play_youtube`** — Opens YouTube on the phone and searches for a video/song. Accepts a `query` string (e.g. "rick astley never gonna give you up"). The phone opens the YouTube search URL via `Intent.ACTION_VIEW`. No special permissions needed.

#### Cloud flow

```
User types message
    → LocalRouter classifies as CLOUD
    → Message sent to MQTT topic calc/{deviceId}/chat/in
    → Server receives, sends to OpenAI with conversation history
    → OpenAI responds (text or tool_call)
    → If tool_call: server publishes to calc/{deviceId}/tools/call
        → Phone executes tool, publishes result to calc/{deviceId}/tools/result
        → Server feeds result back to OpenAI for final response
    → Final text response published to calc/{deviceId}/chat/out
    → Phone displays response with CLOUD tag
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
    ├─ WIFI_ON  ──► WifiTool.setWifiEnabled(true)   [LOCAL]
    ├─ WIFI_OFF ──► WifiTool.setWifiEnabled(false)  [LOCAL]
    ├─ CALCULATE ─► CalculateTool.evaluate(expr)     [LOCAL]
    │               (if no digits found → CLOUD)
    ├─ YOUTUBE ──► YouTubeTool.play(query)          [LOCAL]
    └─ CLOUD ─────► MQTT → Server → OpenAI          [CLOUD]
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
   adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /data/data/com.mqttai.calc/files/model.gguf
   ```

3. The app loads the model on startup in `CalcViewModel.init()` → `LocalRouter.init()` → `LlamaInference.loadModel()`

The OpenAI API key goes in `.env` at the project root (not committed to git).
