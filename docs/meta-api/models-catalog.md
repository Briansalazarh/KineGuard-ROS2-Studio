# Meta Model API - Models Catalog & Availability

> **Official Documentation Reference:** [Meta Model API - Models](https://dev.meta.ai/docs/models)

Meta ships six model lines: **Muse Wonder**, **Muse Spark**, **Muse Image**, **Muse Voice Transcribe**, **Segment Anything Model (SAM 3.1)** hosted on Meta Model API, plus open-weight releases **Muse Spark Open** and **Muse Glimmer**.

---

## 1. Models at a Glance

| Model Line | Official Model ID | Inputs | Outputs | Endpoints | Context Window |
|---|---|---|---|---|---|
| **Muse Wonder** | `muse-wonder-1.0` | Text, image, video, audio, PDF | Text | `/responses`, `/chat/completions`, `/messages` | 1,048,576 tokens |
| **Muse Spark** | `muse-spark-1.3`, `muse-spark-1.2`, `muse-spark-1.1` | Text, image, video, audio, PDF | Text | `/responses`, `/chat/completions`, `/messages` | 1,048,576 tokens |
| **Muse Image** | `muse-image-1.0` | Text, image | Image | `/responses`, `/images/generations`, `/images/edits` | Multi-turn visual |
| **Muse Voice Transcribe** | `muse-voice-transcribe-1.0` | Audio (WAV 16/24kHz) | Text | `POST /v1/asr/transcribe`, `wss://.../asr/realtime` | Up to 10 min audio |
| **SAM 3.1** | `sam-3.1` | Text prompt, image, video | Boxes & masks | `/responses` (`POST /v1/responses`) | Stills & up to 15k frames |
| **Muse Spark Open** | `muse-spark-open-1026` (Baseten) | Text, image, video, audio | Text | Partner Inference (Baseten, Modal) or Self-Host | 816B MoE (42B active) |
| **Muse Glimmer** | Open-weight edge release | Text, image | Text | Self-Host (vLLM, llama.cpp, ExecuTorch) | Lightweight edge |

---

## 2. Choosing Muse Wonder vs Muse Spark

Muse Wonder and Muse Spark take the **exact same requests on the exact same endpoints**. Moving a workload between them is simply changing the `model` string.

- **`muse-wonder-1.0`:** Meta's most capable model, built for complex software engineering, long-horizon agentic work, sub-agent delegation, and tool calling. Available on pay-as-you-go in Standard mode.
- **`muse-spark-1.3`:** Tuned for agentic workflows, sub-agent tasks, classification, and tight/low-latency tool loops. Recommended for fast, interactive response cycles.

---

## 3. Account Modes: Standard vs Contributor
- **Standard Mode (Default):** Prompts, code, and context are **never** used to train Meta models.
- **Contributor Mode (`muse-spark-1.3-contributor`):** Discounted per-token pricing in exchange for training allowance.

