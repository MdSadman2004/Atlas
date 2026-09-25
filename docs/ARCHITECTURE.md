# Atlas — architecture

Atlas is an autonomous agent that runs **on the phone**. Everything below is how the pieces fit
together and how it talks to its backend (the CommandCode API).

---

## 1. Runtime shape

```
┌───────────────────────────── Android device (SM-A075F / Android 16) ─────────────────────────────┐
│                                                                                                  │
│  Compose UI (MainActivity)                                                                       │
│   Chat · Sessions · Goals · Activity · Setup (+ Tools/Memory/Skills)                             │
│        │  collectAsState                     ▲ LiveRun state (streaming text, tool chips, todos)  │
│        ▼                                     │                                                   │
│  RunController  ── one live run, app-scoped coroutine, survives the UI going away ──► AgentService│
│        │                                                                        (foreground svc)  │
│        ▼                                                                                         │
│  AgentEngine  ── the loop: history → LLM → tool calls → results → repeat (max steps / wall-clock) │
│     │        │                                                                                   │
│     │        └──► ToolRegistry (49 tools)                                                        │
│     │                 ├─ device/apps  : battery, torch, volume, clipboard, open_app, sms, …       │
│     │                 ├─ files        : fs_list / read / write / mkdir / delete                    │
│     │                 ├─ web          : web_search (DuckDuckGo), fetch_url (HTML→text)            │
│     │                 ├─ shell        : /system/bin/sh as the app UID (no root)                    │
│     │                 ├─ ui           : ui_dump, ui_tap, ui_type, ui_swipe, ui_scroll, ui_press,   │
│     │                 │                 screenshot  ──► AccessibilityService (gestures + a11y tree)│
│     │                 ├─ memory       : memory_save / search / forget                              │
│     │                 ├─ skills       : skills_list / skill_read / skill_write / skill_delete     │
│     │                 ├─ goals        : goal_create / list / update / delete, schedule_once       │
│     │                 └─ agent        : spawn_agent (nested run), set_todos, analyze_image         │
│     │                                                                                            │
│     ▼                                                                                            │
│  LlmClient  ── OkHttp, SSE streaming, tool-call accumulation, retries, two protocols:             │
│                OpenAI /chat/completions  ·  Anthropic /messages                                   │
│     │                                                                                            │
│  AtlasDb (SQLite)      Settings (SharedPreferences)      MemoryStore      SkillStore (markdown)  │
│  sessions, messages,   api key, base URL, model,         facts, prompt    frontmatter skills,     │
│  memories, goals,      approvals, TTS/voice, autonomy    block            seeded from assets      │
│  runs, events, kv                                                                                 │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
                                        │ HTTPS (bearer)
                                        ▼
                       CommandCode API  api.commandcode.ai/provider/v1
```

## 2. Backend integration (CommandCode)

| | |
|---|---|
| Base URL | `https://api.commandcode.ai/provider/v1` |
| Auth | `Authorization: Bearer <COMMANDCODE_API_KEY>` (the key never lives in source; injected into app prefs) |
| Chat | `POST /chat/completions` — OpenAI-compatible, streaming SSE, function calling |
| Claude path | `POST /messages` — Anthropic protocol for `claude-*` models (plan-gated on this account) |
| Models | `GET /models` — public, 81 models; 64 support `/chat/completions` + `/responses`, 9 Claude models use `/messages` |
| Locked model | `deepseek/deepseek-v4.1-flash` with `reasoning_effort: "high"` on every request |
| Vision | `deepseek/deepseek-v4-flash-vision-exp` fed with `image_url` data-URIs by the `analyze_image` tool |

**Tool-calling contract (verified live):**

```jsonc
// request
{
  "model": "deepseek/deepseek-v4.1-flash",
  "reasoning_effort": "high",
  "stream": true,
  "messages": [ {"role":"user","content":"…"} ],
  "tools": [ {"type":"function","function":{"name":"battery","description":"…","parameters":{…}}} ]
}

// streamed deltas
{"choices":[{"delta":{"reasoning":"…"}}]}
{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_…","function":{"name":"battery","arguments":"{}"}}]}}]}
{"choices":[{"finish_reason":"tool_calls"}]}
```

`LlmClient` accumulates the tool-call deltas, executes the tools, appends
`{"role":"tool","tool_call_id":…,"content":…}` and loops until the model stops asking for tools.

## 3. The loop (AgentEngine)

1. Persist the user turn; build the request: system prompt (identity, device facts, memory block,
   skill index, style rules) + last N stored messages (trimmed to start on a `user` row so tool
   pairs stay valid).
2. Stream one completion; emit text/reasoning deltas to the UI **and** log them.
3. If the model returned tool calls: for each call → availability check → approval policy →
   execute (300 s timeout, output truncated) → persist as a `tool` message + `events` row.
4. Repeat until no tool calls, `maxSteps` (25) or the wall-clock budget trips.
5. Post-turn: optional memory extraction with the same locked model (JSON array of durable facts).

Approvals policy: `off` (default, **auto-approve**) · `smart` (risky tools ask) · `manual`
(everything asks). Risky = `shell`, `fs_write`, `fs_delete`, `ui_tap`, `ui_type`, `ui_swipe`,
`ui_press`, `sms_send`. In unattended goal runs risky tools are auto-denied unless the policy is `off`.

## 4. Autonomy

* **Goals** (WorkManager, `PeriodicWorkRequest`, user-set interval ≥ 15 min) run in a worker —

  no foreground service needed, survives reboot and app death.
* Each goal owns a **session** (`Goal: <title>`), so autonomous runs have their own history.
* `schedule_once` queues a one-off deferred task (`OnceTaskWorker`) and notifies with the result.
* `BootReceiver` re-registers the periodic work after reboots / app updates.
* Every run is recorded in `runs` (status, steps, tokens, duration) and shown in the Activity tab.

## 5. Data model (SQLite `atlas.db`)

| table | columns |
|---|---|
| `sessions` | id, title, created, updated, archived |
| `messages` | id, session_id, role (user/assistant/tool), content, payload (tool_calls JSON), name, reasoning, tool_call_id, created |
| `memories` | id, text, importance, created, last_used, uses |
| `goals` | id, title, instruction, interval_minutes, enabled, created, last_run, next_run, run_count, last_result |
| `runs` | id, session_id, goal_id, started, ended, status, steps, tokens, summary |
| `events` | id, run_id, name, args, result, ok, created |
| `kv` | key, value (goal→session map, caches) |

## 6. Security & permissions

* The API key is never compiled in: `scripts/install.sh --key` writes it into the app's
  `shared_prefs` over ADB (or the user types it in Setup). It stays on the device.
* Permissions requested: notifications, foreground service, microphone (voice), camera (torch),
  location, contacts, SMS (tool disabled by default), `QUERY_ALL_PACKAGES` (app launching),
  exact alarms + battery-exemption button for reliable scheduling.
* Screen control is an **accessibility service** the user enables explicitly; it is the only path
  to reading the UI tree, gestures and screenshots.
* Network: HTTPS only (`usesCleartextTraffic=false`); the only backend is CommandCode.

## 7. Build

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot"   # JDK 17
D:/Apps/gradle-8.9/bin/gradle assembleDebug        # or ./gradlew (Gradle 8.9, AGP 8.4.0)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Kotlin 2.0.21 · Compose BOM 2024.12.01 · minSdk 26 · target/compileSdk 35 · no DI framework,
no KSP, no Room — a small hand-rolled container keeps the build ~30 s incremental.

## 8. What was verified on real hardware

Galaxy A07 (SM-A075F, Android 16 / API 36), 2026-09-25:

| check | result |
|---|---|
| Streaming turn with tools | `run#2 … tool now ok=true · tool battery ok=true · run#2 end status=done` |
| Reply correctness | "…running on Samsung SM-A075F, Android 16. Battery 84%, not charging, 34.3 °C." |
| Sub-agent | `spawn_agent → nested run → calc → 449.0` returned to the parent run |
| Parallel tools | one turn executed `fs_list` + `battery` + `memory_save` in a single step |
| Autonomy | goal created by the agent itself; `run now` → `AutonomyWorker` → battery check → goal state updated |
| Deferred task | `schedule_once` → 2 min later ran and posted the notification |
| Screen control | `ui_dump` read the live a11y tree; `screenshot` → `analyze_image` described the screen |
| Approvals | with `smart` a risky tool paused the run behind a dialog; with the default `off` it runs straight through |
