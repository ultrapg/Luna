# Luna — Native Android AI Digital Assistant

**Luna** (`com.ultrapg.luna`) is a fast, privacy-focused native Android Digital Assistant built with **Material Design 3**, **Jetpack Compose**, and Android's native `VoiceInteractionService` architecture. Designed for modern Android smartphones (**8 GB minimum RAM, 12 GB+ recommended**), Luna triggers directly from your phone's **physical side/power button** as a sleek, edge-snapped vertical notch bubble.

---

## Key Features

### 1. Edge-Snapped Side-Notch Bubble, 5 Pill Styles & Light/Dark Mode
- **Native Default Digital Assistant (`ROLE_ASSISTANT`)**: Registers as Android's system-wide `VoiceInteractionService`, `VoiceInteractionSessionService`, `ACTION_ASSIST` handler, and optional `AccessibilityService` overlay.
- **5 Selectable Pill Styles**:
  1. **Classic Equalizer (Default)**: Vertical audio-reactive equalizer bars & stretched pill thinking indicator.
  2. **Fluid Wave Ribbon**: Dual harmonic sine-wave ribbons flowing vertically along the capsule.
  3. **Material 3 Aura Orb**: Tonal capsule with a breathing voice orb and symmetric vertical acoustic ripples.
  4. **Minimal Edge Blade**: Ultra-sleek hardware-edge light bar with a reactive glowing core and scanner sweep.
  5. **Dot Matrix Pulse**: Vertical 3-column LED dot-matrix level visualizer and chasing wave pulse.
- **Automatic Light & Dark Mode Adaptation**: Both the side-notch overlay and the in-app UI automatically adapt to Android's system Light Mode and Dark Mode in real time.
- **Outside-Touch Auto-Dismiss & Persistent App-Launch Continuity**: Tapping anywhere outside the bubble immediately stops and dismisses the assistant, while launching external apps (`open_app`, settings, timers, deep links) keeps the bubble alive so Luna always finishes speaking her confirmation.
- **Fullscreen Power-Button Calibration**: Fullscreen edge-to-edge calibration mode using real display hardware metrics (`WindowManager.maximumWindowMetrics`) so you can drag the notch to the exact pixel height of your physical side button.
- **Continuous Conversation Window**: Re-triggering the side button within a configurable window (default **20 seconds**) automatically continues the active conversation with full memory of previous messages and tool outputs; waiting longer starts a fresh chat session.

### 2. Redesigned Chat UI, Multi-Step Tool Timeline & Reasoning Inspector
- **Live Multi-Step Execution Banner**: While Luna is thinking or running tools, the chat displays a real-time step-by-step execution timeline with running spinners, completion checkmarks, category badges (`ANDROID`, `WEB`, `MEMORY`, `API MCP`, `MCP`), and step latency in `ms`.
- **Collapsible Model Reasoning Card**: Supports reasoning models (`<think>...</think>`, `reasoning_content`, and configurable `reasoning_effort`: `Default`, `Off`, `Low`, `Med`, `High`). Internal thoughts are shown in a collapsible reasoning card in the chat and are never read aloud by TTS.
- **Interactive Tool Execution Inspector**: Tap any tool pill or the multi-step summary badge (`N Steps • ms`) above a message to inspect **what it did**, view JSON parameters, and interact with rich result cards (clickable DuckDuckGo search cards, extracted website reader cards, and device status telemetry chips).

### 3. Balanced Tool Usage, Speed-First Grounding & Deep Research
- **Android Hardware & System Control**:
  - Toggle the camera **Flashlight / Torch** (`set_flashlight`)
  - Launch installed Android apps with bilingual (EN/DE) alias & fuzzy matching plus automatic self-correction (`open_app`)
  - Open URLs, YouTube searches, and Maps navigation (`open_url_or_deep_link`)
  - Adjust system **Media, Ring, Notification, or Alarm volume** (`set_volume`)
  - Set system **Alarms** and **Countdown Timers** (`set_alarm_or_timer`)
  - Query **Battery level, charging state, volume, and time** (`get_device_status`)
  - Control **Media Playback** (`play_pause`, `next`, `previous`)
  - Open **System Settings** panels (`wifi`, `bluetooth`, `display`, `sound`, `battery`, `assistant`)
  - Read or write the **System Clipboard** (`clipboard_action`)
- **Speed-First Web Search & On-Demand Deep Research**:
  - Prefers fast, single-step lookups (`search_duckduckgo` + `read_website`) by default so voice answers stay instant.
  - When asked for a detailed or deep search, `deep_research` runs multiple search queries in parallel and pulls full readable text from multiple websites concurrently.
- **API Built-In MCPs & External MCP Servers**:
  - Automatically discovers and invokes **API Built-In MCPs** (`<baseUrl>/mcp`, server-side `executed_tools`, `mcp_calls`, `context7`, API web search, citations, etc.) as well as user-configured HTTP JSON-RPC / SSE **MCP Servers** in a balanced, task-appropriate way.
- **Persistent On-Device Memory**: Saves, searches, and manages personal facts and preferences across sessions (`save_memory`, `recall_memories`, `forget_memory`).

### 4. 100% Local On-Device Voice Input (`EAR` + `VOZ`) & Neural Female TTS
- **Desert Ant Labs `EAR` + `VOZ` STT**: On-device spoken language detection and fast offline speech-to-text with configurable silence auto-stop.
- **Local Sherpa-ONNX Neural Female TTS**: High-quality offline neural female voices for **English** (`Amy Medium`, `Kokoro 82M af_heart`) and **German** (`Dii High`, `Kerstin`), plus a custom `.onnx` + `tokens.txt` voice model slot and Android neural TTS fallback.
- **Auto-Language Matching**: Defaults to the device's language and, in Auto mode, always replies in the exact language the user asked their question in—optimized for natural spoken flow text without Markdown clutter.

### 5. OpenAI-Compatible AI Backend
- Works with any OpenAI-compatible API (`OpenAI`, `OpenRouter`, `Groq`, `DeepSeek`, `Ollama`, `vLLM`, `LM Studio`, etc.) with live `/v1/models` discovery and custom model ID entry.

---

## Deutsch (Überblick)

**Luna** ist ein nativer Android-Sprachassistent mit **Material Design 3**:
- **Vertikale Side-Notch-Bubble**: Erscheint direkt neben der physischen Ein-/Aus-Taste (mit Vollbild-Kalibrierung), zeigt beim Zuhören eine weiße Wellenform, beim Nachdenken eine weiße Lade-Animation und beim Sprechen einen hellblauen Audio-Equalizer.
- **Mehrstufige Werkzeug-Timeline & Gedankengang-Ansicht**: Zeigt im Chat genau an, welche Werkzeuge (`ANDROID`, `WEB`, `MEMORY`, `API MCP`, `MCP`) mit welchen Ergebnissen ausgeführt wurden.
- **Lokale Spracherkennung (`EAR` + `VOZ`) & Lokale Neurale TTS (`Sherpa-ONNX`)**: Komplett auf dem Gerät ausgeführte Spracherkennung und natürliche weibliche Sprachausgabe in natürlichem Fließtext.

---

## License

- **Application Source Code**: Licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see [`LICENSE`](./LICENSE).
- **Downloaded & Custom AI Models**: Governed by their respective model licenses — see [`LICENSE-MODELS.md`](./LICENSE-MODELS.md).
