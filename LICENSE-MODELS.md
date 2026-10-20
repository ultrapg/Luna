# Model & Runtime Licensing Notice (`LICENSE-MODELS.md`)

While the **Luna** Android application source code is licensed under the **GNU General Public License v3.0 (GPL-3.0)** (see [`LICENSE`](./LICENSE)), the machine learning models and speech runtimes downloaded on-demand by the application or imported by the user are separate neural weight artifacts governed by their respective upstream licenses.

---

## 1. On-Device Speech Input Models (Desert Ant Labs)

Luna downloads on-device speech recognition and language identification weights on demand from Hugging Face (`desert-ant-labs/*`) and executes them locally via the Desert Ant Labs Android runtime (`ai.desertant:{voz,ear}`):

| Model ID | Purpose | Source Repository | Upstream Model License |
| :--- | :--- | :--- | :--- |
| **`desert-ant-labs/voz`** | Multilingual On-Device Speech-to-Text (STT) | `https://huggingface.co/desert-ant-labs/voz` | Apache-2.0 / Desert Ant Labs Terms |
| **`desert-ant-labs/ear`** | Spoken Language Identification (25 languages incl. English & German) | `https://huggingface.co/desert-ant-labs/ear` | Apache-2.0 / Desert Ant Labs Terms |

---

## 2. On-Device Female Neural Text-to-Speech (TTS) Models

Luna supports local neural female voices via **Sherpa-ONNX** (`com.k2fsa.sherpa.onnx`, Apache-2.0) and downloads voice archives on demand:

| Voice ID | Language | Architecture | License |
| :--- | :--- | :--- | :--- |
| **`vits-piper-en_US-amy-medium`** | English (`en_US`) | VITS / Piper | MIT License |
| **`kokoro-int8-en-v0_19`** (`af_heart`, `af_bella`) | English (`en_US`) | Kokoro 82M Int8 | Apache-2.0 License |
| **`vits-piper-de_DE-dii-high`** | German (`de_DE`) | VITS / Piper | MIT License |
| **`vits-piper-de_DE-kerstin-low`** | German (`de_DE`) | VITS / Piper | MIT License |

---

## 3. Custom User-Trained TTS Voice Models

Luna includes a dedicated **Custom Trained Female Voice Model Slot** (`custom_female_voice.onnx` + `tokens.txt`).
- Any custom `.onnx` model trained and loaded by you may be distributed under **GPL-3.0**, **MIT**, **Apache-2.0**, or your own custom model license without altering the GPL-3.0 license of the Luna application source code.
