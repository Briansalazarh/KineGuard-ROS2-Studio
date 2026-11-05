# Speech-to-Text (Muse Voice Transcribe 1.0)

> **Official Documentation Reference:** [Meta Model API - Speech to Text](https://dev.meta.ai/docs/speech-to-text)

`muse-voice-transcribe-1.0` transcribes live audio or recorded files, with speech-turn detection (endpointing), speaker labels (diarization), and vocabulary/language biasing.

---

## 1. Endpoints

| Endpoint | Use Case | Protocol & Auth |
|---|---|---|
| `wss://api.meta.ai/v1/asr/realtime?sessionId=<id>` | Live streaming microphone / low latency | WebSocket. Handshake JSON frame with Bearer token. |
| `POST https://api.meta.ai/v1/asr/transcribe?sessionId=<id>` | Pre-recorded audio files | Multipart upload. `Authorization: Bearer <KEY>` header. |

---

## 2. File Transcription (`POST /v1/asr/transcribe`)

### Audio Requirements:
- **Format:** RIFF/WAVE container (`.wav`).
- **Encoding:** Mono, 16-bit integer PCM (`pcm_s16le`).
- **Sample Rate:** Native 24 kHz (`24000`) or 16 kHz (`16000`).
- **Size Cap:** Max 32 MB and up to 10 minutes of audio per request.

### Multipart/form-data Structure:
A valid POST request sends **two parts**:
1. **`request` (JSON):** Settings (`mode`, `model`, `audioEncoding`, `languageBias`, `keywords`).
2. **`audio` (Binary):** The WAV audio payload.

#### Curl Example:
```bash
curl --fail-with-body \
  'https://api.meta.ai/v1/asr/transcribe?sessionId=my-session-id' \
  -H "Authorization: Bearer $MODEL_API_KEY" \
  -F 'request={"mode":"PUSH_TO_TALK","model":"muse-voice-transcribe-1.0","audioEncoding":"WAV"};type=application/json' \
  -F 'audio=@command.wav'
```

### JSON Response Schema:
```json
{
  "sessionId": "9f1c...",
  "transcript": "Thermal runaway detected in Sector B, initiate perimeter diversion.",
  "audioDurationMs": 4200,
  "turns": [
    {
      "turnId": 1,
      "startMs": 320,
      "endMs": 4180,
      "transcript": "Thermal runaway detected in Sector B, initiate perimeter diversion."
    }
  ]
}
```

---

## 3. Operational Modes
- **`PUSH_TO_TALK` (Default):** Single-turn voice commands (ideal for robotics, terminal dispatch, and push-to-talk buttons).
- **`ENDPOINTING`:** The model detects speech boundaries automatically and returns events when speech starts and finishes.
- **`DIARIZATION`:** Identifies and labels multiple speakers (`"A"`, `"B"`).

---

## 4. Biasing & Accuracy Features
- **`languageBias`:** Array of expected languages (e.g. `["English", "Spanish"]`).
- **`keywords`:** Vocabulary biasing for acronyms, technical IDs, or robotics terms (e.g. `["BVC", "AMR-01", "Voronoi", "Kinematic"]`).

