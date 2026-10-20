# Luna — Native Android AI Digital Assistant (EN & DE)

**Luna** (`com.ultrapg.luna`) is a native Android Digital Assistant built with **Material Design 3**, **Jetpack Compose**, and native Android `VoiceInteractionService` APIs. Designed for flagship and high-end Android smartphones (**8 GB minimum RAM, 12 GB+ recommended**), Luna can be triggered directly via the **physical side/power button** just like Gemini.

---

## Key Features

### 1. Side-Button Trigger & Gemini-Style Pill/Notch Overlay
- **Native Default Digital Assistant (`ROLE_ASSISTANT`)**: Registers as Android's system-wide `VoiceInteractionService` + `VoiceInteractionSessionService` + `ACTION_ASSIST` handler + optional `AccessibilityService` shortcut.
- **Edge-Docked Pill Bubble**: Spawns a compact pill-like notch right next to the physical side/power button on the screen edge.
- **Gemini 4-Color Glowing Outline & Audio-Reactive Wave**: Features an animated sweeping multi-color perimeter glow (`#4285F4`, `#9B72CB`, `#D96570`, `#F4B400`) and a real-time 5-band audio-reactive waveform.
- **Interactive Bubble Studio**: Resize the collapsed notch width/height, adjust vertical alignment with the physical button, scale the expanded pill, tune glow intensity, and switch between Right Notch, Left Notch, or Free Floating modes directly inside the app.

### 2. 100% Local On-Device Voice Input (Desert Ant Labs `EAR` + `VOZ`)
- **`EAR` Spoken Language Detection**: Automatically detects whether you are speaking **English (`en`)** or **German (`de`)** (plus 23 other languages) locally on the phone.
- **`VOZ` Multilingual Speech-to-Text**: Fast, private on-device transcription with smart silence-aware chunking.
- **Resumable Model Downloader**: Built-in HTTP Range resumable downloader with incremental SHA-256 verification and native `.dal-meta/manifest` (`DAL1`) generation.

### 3. NPU / GPU / CPU Hardware Acceleration
- Automatically detects your flagship SoC (`Qualcomm Snapdragon Hexagon QNN HTP`, `MediaTek Dimensity NeuroPilot`, `Google Tensor TPU`, `Samsung Exynos NPU`) and prioritizes NPU/NNAPI acceleration with seamless fallback to GPU (`OpenCL/GLES`) and multi-core CPU (`XNNPACK`).

### 4. OpenAI-Compatible LLM Backend with Hybrid Editable Model Dropdown
- Connect **any OpenAI-compatible API** (`OpenAI`, `OpenRouter`, `Groq`, `DeepSeek`, `Ollama`, `vLLM`, `LM Studio`) via Base URL and API Key.
- **Hybrid Model Selector**: Either select from models dynamically discovered via `/v1/models` **or** type any custom model ID directly into the dropdown (even if it is not listed by the endpoint).

### 5. Android Hardware Control, Web Grounding & MCP Client
- **Android Hardware & System Actions**:
  - Turn the **Flashlight (Torch)** on or off (`CameraManager.setTorchMode`)
  - Launch any installed Android app by name
  - Adjust system **Media, Ring, Notification, or Alarm volume**
  - Set system **Alarms** and **Countdown Timers**
  - Query **Battery level, charging state, RAM & NPU status**
  - Control **Media Playback** (`play`, `pause`, `next`, `previous`)
  - Open **System Settings** panels (`Wi-Fi`, `Bluetooth`, `Display`, `Sound`, etc.)
  - Read or copy text to the **Clipboard**
- **Web Grounding Agent**:
  - **DuckDuckGo Web Search**: Live web search for up-to-date facts and news.
  - **Full Website Reader**: Fetches and extracts readable content from any URL.
- **Persistent Assistant Memory**: Stores, recalls, and manages user preferences and facts across conversations.
- **MCP (Model Context Protocol) Client**: Connect external HTTP JSON-RPC / SSE MCP servers (`tools/list` & `tools/call`) so Luna can act as a quick-ask and smart-home/developer agent.

### 6. Fast Local Female Neural TTS + Custom Voice Slot
- Supports **Sherpa-ONNX** neural female voices for **English** (`Amy Medium`, `Kokoro 82M af_heart`) and **German** (`Dii High`, `Kerstin`), plus zero-download Android female neural TTS fallback.
- **Custom Voice Replacement Slot**: Ready for you to drop in your own custom-trained `.onnx` + `tokens.txt` female voice model at any time.

---

## Deutsch (German Overview)

**Luna** unterstützt vollständig **Deutsch** und **Englisch**:
- Automatische Spracherkennung über **Desert Ant EAR & VOZ** direkt auf dem Gerät.
- Natürliche weibliche deutsche & englische **Sherpa-ONNX TTS-Stimmen** sowie ein eigener Slot für selbst trainierte `.onnx`-Stimmmodelle.
- Volle Android-Steuerung („Schalte die Taschenlampe ein", „Stelle einen Timer auf 5 Minuten", „Suche auf DuckDuckGo nach...") per langem Druck auf die Seitentaste.

---

## License

- **Application Source Code**: Licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see [`LICENSE`](./LICENSE).
- **Downloaded & Custom AI Models**: Governed by their respective model licenses — see [`LICENSE-MODELS.md`](./LICENSE-MODELS.md).
