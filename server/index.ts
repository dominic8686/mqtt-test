import "dotenv/config";
import http from "node:http";
import mqtt from "mqtt";
import OpenAI from "openai";

const MQTT_URL = process.env.MQTT_URL ?? "mqtt://localhost:1883";
const HTTP_PORT = parseInt(process.env.HTTP_PORT ?? "3001", 10);
const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

const SYSTEM_PROMPT = `You are a smart phone assistant. You have tools to control the phone.
When the user asks you to calculate something, use the calculate tool. Always use the tool — never calculate in your head. After getting the result, respond with a short answer like "6 + 6 = 12".
When the user asks to turn Wi-Fi on or off, use the toggle_wifi tool. Confirm what you did afterwards.
When the user asks to play music, a song, a video, or open YouTube, use the play_youtube tool with a good search query. Confirm what you opened.`;

const TOOLS: OpenAI.Chat.Completions.ChatCompletionTool[] = [
  {
    type: "function",
    function: {
      name: "calculate",
      description:
        "Evaluate a mathematical expression and return the numeric result. The expression should use standard math notation (e.g. 6+6, 10*3, 100/4, 2^8).",
      parameters: {
        type: "object",
        properties: {
          expression: {
            type: "string",
            description: "The math expression to evaluate, e.g. '6+6'",
          },
        },
        required: ["expression"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "toggle_wifi",
      description:
        "Turn the phone's Wi-Fi on or off.",
      parameters: {
        type: "object",
        properties: {
          enabled: {
            type: "boolean",
            description: "true to turn Wi-Fi on, false to turn it off",
          },
        },
        required: ["enabled"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "play_youtube",
      description:
        "Open YouTube on the phone and search for a video or song to play.",
      parameters: {
        type: "object",
        properties: {
          query: {
            type: "string",
            description: "The search query for YouTube, e.g. 'rick astley never gonna give you up'",
          },
        },
        required: ["query"],
      },
    },
  },
];

/* ── Device & state tracking ── */
interface DeviceState {
  lastSeen: number;
  wifiEnabled: boolean | null;
  chatLog: { role: string; text: string; source?: string; ts: number }[];
}
const devices = new Map<string, DeviceState>();

function getDevice(deviceId: string): DeviceState {
  if (!devices.has(deviceId)) {
    devices.set(deviceId, { lastSeen: Date.now(), wifiEnabled: null, chatLog: [] });
  }
  const d = devices.get(deviceId)!;
  d.lastSeen = Date.now();
  return d;
}

/* ── SSE clients ── */
const sseClients = new Set<http.ServerResponse>();

function broadcast(event: string, data: unknown) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of sseClients) {
    res.write(msg);
  }
}

/* ── Pending UI-initiated tool calls ── */
const uiPendingCalls = new Map<string, (result: string) => void>();

// Per-device conversation history (in-memory)
const conversations = new Map<string, OpenAI.Chat.Completions.ChatCompletionMessageParam[]>();

// Pending tool calls waiting for results from the phone
const pendingToolCalls = new Map<
  string,
  {
    deviceId: string;
    toolCallId: string;
    messages: OpenAI.Chat.Completions.ChatCompletionMessageParam[];
  }
>();

function getHistory(deviceId: string) {
  if (!conversations.has(deviceId)) {
    conversations.set(deviceId, [{ role: "system", content: SYSTEM_PROMPT }]);
  }
  return conversations.get(deviceId)!;
}

/* ── MQTT ── */
const mqttClient = mqtt.connect(MQTT_URL);

mqttClient.on("connect", () => {
  console.log(`Connected to MQTT broker at ${MQTT_URL}`);
  mqttClient.subscribe("calc/+/chat/in");
  mqttClient.subscribe("calc/+/chat/out");
  mqttClient.subscribe("calc/+/tools/result");
  mqttClient.subscribe("calc/+/status/wifi");
  console.log("Subscribed to MQTT topics");
});

mqttClient.on("message", async (topic, payload) => {
  const parts = topic.split("/");
  const deviceId = parts[1];
  const channel = parts.slice(2).join("/");
  const message = payload.toString();

  console.log(`[${topic}] ${message}`);
  const device = getDevice(deviceId);

  try {
    if (channel === "chat/in") {
      device.chatLog.push({ role: "user", text: message, ts: Date.now() });
      broadcast("chat", { deviceId, role: "user", text: message });
      await handleUserMessage(deviceId, message);
    } else if (channel === "chat/out") {
      // Capture outgoing AI responses for the dashboard
      try {
        const json = JSON.parse(message);
        if (json.text) {
          device.chatLog.push({ role: "assistant", text: json.text, source: "cloud", ts: Date.now() });
          broadcast("chat", { deviceId, role: "assistant", text: json.text, source: "cloud" });
        }
      } catch { /* ignore parse errors */ }
    } else if (channel === "tools/result") {
      await handleToolResult(deviceId, message);
    } else if (channel === "status/wifi") {
      try {
        const status = JSON.parse(message);
        device.wifiEnabled = status.enabled;
        broadcast("wifi", { deviceId, enabled: status.enabled });
        console.log(`📶 Device ${deviceId} Wi-Fi: ${status.enabled ? "ON" : "OFF"}`);
      } catch { /* ignore */ }
    }
  } catch (err) {
    console.error("Error handling message:", err);
    mqttClient.publish(
      `calc/${deviceId}/chat/out`,
      JSON.stringify({ type: "error", text: "Something went wrong." })
    );
  }
});

async function handleUserMessage(deviceId: string, text: string) {
  const history = getHistory(deviceId);
  history.push({ role: "user", content: text });
  await callOpenAI(deviceId, history);
}

async function handleToolResult(deviceId: string, resultJson: string) {
  const result = JSON.parse(resultJson);

  // Check if this is a UI-initiated call
  const uiCb = uiPendingCalls.get(result.callId);
  if (uiCb) {
    uiPendingCalls.delete(result.callId);
    uiCb(String(result.value));
    return;
  }

  const pending = pendingToolCalls.get(result.callId);
  if (!pending) {
    console.warn(`No pending tool call for callId=${result.callId}`);
    return;
  }
  pendingToolCalls.delete(result.callId);

  pending.messages.push({
    role: "tool",
    tool_call_id: pending.toolCallId,
    content: JSON.stringify(result.value),
  });

  await callOpenAI(deviceId, pending.messages);
}

async function callOpenAI(
  deviceId: string,
  messages: OpenAI.Chat.Completions.ChatCompletionMessageParam[]
) {
  const response = await openai.chat.completions.create({
    model: "gpt-4o-mini",
    messages,
    tools: TOOLS,
    tool_choice: "auto",
  });

  const choice = response.choices[0];
  const assistantMessage = choice.message;
  messages.push(assistantMessage);

  if (assistantMessage.tool_calls && assistantMessage.tool_calls.length > 0) {
    const toolCall = assistantMessage.tool_calls[0];
    const callId = `call_${Date.now()}`;

    pendingToolCalls.set(callId, {
      deviceId,
      toolCallId: toolCall.id,
      messages,
    });

    const toolRequest = {
      callId,
      name: toolCall.function.name,
      args: JSON.parse(toolCall.function.arguments),
    };

    console.log(`→ Tool call to ${deviceId}:`, toolRequest);
    mqttClient.publish(`calc/${deviceId}/tools/call`, JSON.stringify(toolRequest));
  } else {
    const text = assistantMessage.content ?? "";
    console.log(`→ Response to ${deviceId}: ${text}`);
    mqttClient.publish(
      `calc/${deviceId}/chat/out`,
      JSON.stringify({ type: "response", text })
    );
  }
}

mqttClient.on("error", (err) => {
  console.error("MQTT error:", err);
});

/* ── HTTP Server + Dashboard ── */
function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (c: Buffer) => (body += c.toString()));
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

const httpServer = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", `http://localhost:${HTTP_PORT}`);

  // CORS headers
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") { res.writeHead(204); res.end(); return; }

  /* ── SSE stream ── */
  if (url.pathname === "/api/events" && req.method === "GET") {
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    sseClients.add(res);
    req.on("close", () => sseClients.delete(res));

    // Send current state snapshot
    for (const [id, d] of devices) {
      res.write(`event: device\ndata: ${JSON.stringify({ deviceId: id, wifiEnabled: d.wifiEnabled, lastSeen: d.lastSeen })}\n\n`);
    }
    return;
  }

  /* ── List devices ── */
  if (url.pathname === "/api/devices" && req.method === "GET") {
    const list = [...devices.entries()].map(([id, d]) => ({
      deviceId: id,
      wifiEnabled: d.wifiEnabled,
      lastSeen: d.lastSeen,
      chatLog: d.chatLog.slice(-50),
    }));
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(list));
    return;
  }

  /* ── Toggle Wi-Fi from dashboard ── */
  if (url.pathname.startsWith("/api/wifi/") && req.method === "POST") {
    const deviceId = url.pathname.split("/")[3];
    if (!deviceId) { res.writeHead(400); res.end("Missing deviceId"); return; }

    const body = JSON.parse(await readBody(req));
    const enabled = !!body.enabled;
    const callId = `ui_${Date.now()}`;

    // Send tool call directly to the phone
    const toolRequest = { callId, name: "toggle_wifi", args: { enabled } };
    mqttClient.publish(`calc/${deviceId}/tools/call`, JSON.stringify(toolRequest));

    // Wait for result (timeout 10s)
    const result = await new Promise<string>((resolve) => {
      const timer = setTimeout(() => {
        uiPendingCalls.delete(callId);
        resolve("Timeout waiting for device response");
      }, 10_000);
      uiPendingCalls.set(callId, (val) => {
        clearTimeout(timer);
        resolve(val);
      });
    });

    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, result }));
    return;
  }

  /* ── Send chat from dashboard ── */
  if (url.pathname.startsWith("/api/chat/") && req.method === "POST") {
    const deviceId = url.pathname.split("/")[3];
    if (!deviceId) { res.writeHead(400); res.end("Missing deviceId"); return; }
    const body = JSON.parse(await readBody(req));
    const text = body.text ?? "";
    mqttClient.publish(`calc/${deviceId}/chat/in`, text);
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  /* ── Dashboard HTML ── */
  if (url.pathname === "/" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "text/html" });
    res.end(DASHBOARD_HTML);
    return;
  }

  res.writeHead(404);
  res.end("Not found");
});

httpServer.listen(HTTP_PORT, () => {
  console.log(`Dashboard running at http://localhost:${HTTP_PORT}`);
});

console.log("MQTT AI Calculator Server starting...");

/* ── Inline Dashboard HTML ── */
const DASHBOARD_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MQTT AI Calc – Dashboard</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: system-ui, -apple-system, sans-serif; background: #0f172a; color: #e2e8f0; min-height: 100vh; }
  .header { background: #1e293b; padding: 1rem 2rem; border-bottom: 1px solid #334155; display: flex; align-items: center; gap: 1rem; }
  .header h1 { font-size: 1.25rem; font-weight: 600; }
  .header .dot { width: 10px; height: 10px; border-radius: 50%; background: #22c55e; }
  .container { max-width: 900px; margin: 2rem auto; padding: 0 1rem; }
  .no-devices { text-align: center; padding: 4rem 1rem; color: #94a3b8; }
  .device-card { background: #1e293b; border-radius: 12px; border: 1px solid #334155; margin-bottom: 1.5rem; overflow: hidden; }
  .device-header { padding: 1rem 1.5rem; border-bottom: 1px solid #334155; display: flex; justify-content: space-between; align-items: center; }
  .device-id { font-family: monospace; font-size: 0.9rem; color: #94a3b8; }
  .wifi-status { display: flex; align-items: center; gap: 0.75rem; }
  .wifi-badge { padding: 0.25rem 0.75rem; border-radius: 9999px; font-size: 0.8rem; font-weight: 600; }
  .wifi-on { background: #065f46; color: #6ee7b7; }
  .wifi-off { background: #7f1d1d; color: #fca5a5; }
  .wifi-unknown { background: #374151; color: #9ca3af; }
  .toggle-btn { padding: 0.5rem 1.25rem; border: none; border-radius: 8px; font-size: 0.85rem; font-weight: 600; cursor: pointer; transition: all 0.2s; }
  .toggle-btn:disabled { opacity: 0.5; cursor: not-allowed; }
  .btn-off { background: #dc2626; color: white; }
  .btn-off:hover:not(:disabled) { background: #b91c1c; }
  .btn-on { background: #16a34a; color: white; }
  .btn-on:hover:not(:disabled) { background: #15803d; }
  .device-body { padding: 1rem 1.5rem; }
  .chat-log { max-height: 300px; overflow-y: auto; display: flex; flex-direction: column; gap: 0.5rem; padding: 0.5rem 0; }
  .chat-msg { padding: 0.5rem 0.75rem; border-radius: 8px; max-width: 80%; font-size: 0.85rem; line-height: 1.4; }
  .chat-user { background: #3b82f6; color: white; align-self: flex-end; }
  .chat-assistant { background: #334155; color: #e2e8f0; align-self: flex-start; }
  .chat-source { font-size: 0.65rem; opacity: 0.6; text-transform: uppercase; margin-bottom: 2px; }
  .chat-input-row { display: flex; gap: 0.5rem; margin-top: 0.75rem; }
  .chat-input-row input { flex: 1; background: #0f172a; border: 1px solid #475569; border-radius: 8px; padding: 0.5rem 0.75rem; color: #e2e8f0; font-size: 0.85rem; }
  .chat-input-row input:focus { outline: none; border-color: #3b82f6; }
  .chat-input-row button { padding: 0.5rem 1rem; background: #3b82f6; color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 0.85rem; }
  .event-log { margin-top: 2rem; }
  .event-log h3 { font-size: 0.9rem; color: #94a3b8; margin-bottom: 0.5rem; }
  .events { background: #1e293b; border-radius: 8px; padding: 0.75rem; max-height: 200px; overflow-y: auto; font-family: monospace; font-size: 0.75rem; color: #94a3b8; }
  .events div { padding: 2px 0; }
</style>
</head>
<body>
<div class="header">
  <div class="dot" id="sseDot"></div>
  <h1>MQTT AI Calc Dashboard</h1>
</div>
<div class="container">
  <div id="devices"></div>
  <div class="event-log">
    <h3>Event Log</h3>
    <div class="events" id="events"></div>
  </div>
</div>
<script>
const devicesEl = document.getElementById('devices');
const eventsEl = document.getElementById('events');
const sseDot = document.getElementById('sseDot');
const state = {}; // deviceId -> { wifiEnabled, chatLog }

function logEvent(msg) {
  const d = document.createElement('div');
  d.textContent = new Date().toLocaleTimeString() + ' ' + msg;
  eventsEl.prepend(d);
  while (eventsEl.children.length > 100) eventsEl.lastChild.remove();
}

function render() {
  const ids = Object.keys(state);
  if (ids.length === 0) {
    devicesEl.innerHTML = '<div class="no-devices">Waiting for devices to connect...</div>';
    return;
  }
  devicesEl.innerHTML = ids.map(id => {
    const d = state[id];
    const w = d.wifiEnabled;
    const badgeClass = w === true ? 'wifi-on' : w === false ? 'wifi-off' : 'wifi-unknown';
    const badgeText = w === true ? 'Wi-Fi ON' : w === false ? 'Wi-Fi OFF' : 'Unknown';
    const chatHtml = (d.chatLog || []).map(m => {
      const cls = m.role === 'user' ? 'chat-user' : 'chat-assistant';
      const src = m.source ? '<div class="chat-source">' + m.source + '</div>' : '';
      return '<div class="chat-msg ' + cls + '">' + src + escHtml(m.text) + '</div>';
    }).join('');
    return '<div class="device-card" data-device="' + id + '">' +
      '<div class="device-header">' +
        '<span class="device-id">Device: ' + id + '</span>' +
        '<div class="wifi-status">' +
          '<span class="wifi-badge ' + badgeClass + '">' + badgeText + '</span>' +
          '<button class="toggle-btn btn-off" data-action="wifi-off"' + (w === false ? ' disabled' : '') + '>Turn OFF</button>' +
          '<button class="toggle-btn btn-on" data-action="wifi-on"' + (w === true ? ' disabled' : '') + '>Turn ON</button>' +
        '</div>' +
      '</div>' +
      '<div class="device-body">' +
        '<div class="chat-log" id="chatlog-' + id + '">' + chatHtml + '</div>' +
        '<div class="chat-input-row">' +
          '<input class="chat-input" data-device="' + id + '" placeholder="Send a message...">' +
          '<button class="chat-send" data-device="' + id + '">&rarr;</button>' +
        '</div>' +
      '</div>' +
    '</div>';
  }).join('');
  ids.forEach(id => {
    const el = document.getElementById('chatlog-' + id);
    if (el) el.scrollTop = el.scrollHeight;
  });
}

function escHtml(s) {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// Event delegation
document.addEventListener('click', async function(e) {
  var btn = e.target.closest('[data-action]');
  if (!btn) return;
  var card = btn.closest('[data-device]');
  if (!card) return;
  var deviceId = card.dataset.device;
  var action = btn.dataset.action;
  if (action === 'wifi-off' || action === 'wifi-on') {
    var enabled = action === 'wifi-on';
    logEvent('Sending Wi-Fi ' + (enabled ? 'ON' : 'OFF') + ' to ' + deviceId);
    document.querySelectorAll('.toggle-btn').forEach(function(b) { b.disabled = true; });
    try {
      var r = await fetch('/api/wifi/' + deviceId, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: enabled })
      });
      var data = await r.json();
      logEvent('Result: ' + data.result);
    } catch (err) {
      logEvent('Error: ' + err.message);
    }
    render();
  }
});
document.addEventListener('click', async function(e) {
  var btn = e.target.closest('.chat-send');
  if (!btn) return;
  var deviceId = btn.dataset.device;
  var input = btn.parentElement.querySelector('.chat-input');
  var text = input.value.trim();
  if (!text) return;
  input.value = '';
  if (!state[deviceId]) state[deviceId] = { wifiEnabled: null, chatLog: [] };
  state[deviceId].chatLog.push({ role: 'user', text: text, ts: Date.now() });
  render();
  await fetch('/api/chat/' + deviceId, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: text })
  });
});
document.addEventListener('keydown', function(e) {
  if (e.key !== 'Enter') return;
  var input = e.target.closest('.chat-input');
  if (!input) return;
  var deviceId = input.dataset.device;
  var btn = input.parentElement.querySelector('.chat-send');
  if (btn) btn.click();
});

// Load initial data
fetch('/api/devices').then(r => r.json()).then(list => {
  list.forEach(d => {
    state[d.deviceId] = { wifiEnabled: d.wifiEnabled, chatLog: d.chatLog || [] };
  });
  render();
});

// SSE
const es = new EventSource('/api/events');
es.onopen = () => { sseDot.style.background = '#22c55e'; logEvent('SSE connected'); };
es.onerror = () => { sseDot.style.background = '#dc2626'; };
es.addEventListener('device', e => {
  const d = JSON.parse(e.data);
  if (!state[d.deviceId]) state[d.deviceId] = { wifiEnabled: null, chatLog: [] };
  state[d.deviceId].wifiEnabled = d.wifiEnabled;
  render();
});
es.addEventListener('wifi', e => {
  const d = JSON.parse(e.data);
  if (!state[d.deviceId]) state[d.deviceId] = { wifiEnabled: null, chatLog: [] };
  state[d.deviceId].wifiEnabled = d.enabled;
  logEvent('Device ' + d.deviceId + ' Wi-Fi: ' + (d.enabled ? 'ON' : 'OFF'));
  render();
});
es.addEventListener('chat', e => {
  const d = JSON.parse(e.data);
  if (!state[d.deviceId]) state[d.deviceId] = { wifiEnabled: null, chatLog: [] };
  state[d.deviceId].chatLog.push({ role: d.role, text: d.text, source: d.source, ts: Date.now() });
  render();
});
</script>
</body>
</html>`;
