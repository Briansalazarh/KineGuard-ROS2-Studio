# Meta Model API — Official Models & Endpoints Specification

- **Base URL única:** `https://api.meta.ai/v1`
- **Autenticación:** Header `Authorization: Bearer $META_API_KEY`

## 1. Modelos Oficiales

- **`muse-wonder-1.0` (Muse Wonder):**
  - Disponible en Model API (Standard mode pay-as-you-go).
  - Input: Text, image, video, audio, PDF. Output: Text.
  - Context window: `1,048,576` tokens.
  - Soporta `reasoning_effort` (`"minimal"`, `"low"`, `"medium"`, `"high"`, `"max"`), image understanding, tool calling y structured output (`json_schema`).

- **`muse-spark-1.3` y `muse-spark-1.3-contributor` (Muse Spark):**
  - Input: Text, image, video, audio, PDF. Output: Text.
  - Context window: `1,048,576` tokens.
  - Regla oficial de intercambiabilidad: *"Muse Wonder and Muse Spark take the same requests on the same endpoints (`POST /v1/responses` and `POST /v1/chat/completions`), so moving a workload between them is a model ID change."*

- **`muse-image-1.0` (Muse Image):**
  - Input: Text, image. Output: Image.
  - Endpoints: `POST /v1/responses`, `POST /v1/images/generations`, `POST /v1/images/edits`.
  - Soporta `reasoning_strength: "low"` y `tool_enablement: {"enable_web_search": false, "enable_shell": false}` para baja latencia.

- **`muse-voice-transcribe-1.0` (Muse Voice Transcribe):**
  - HTTP Multipart (`POST https://api.meta.ai/v1/asr/transcribe?sessionId=<uuid>`): Exige `multipart/form-data` con **exactamente dos partes**:
    1. Parte `request` (`Content-Type: application/json`):
       ```json
       {
         "mode": "PUSH_TO_TALK",
         "model": "muse-voice-transcribe-1.0",
         "audioEncoding": "WAV",
         "languageBias": ["Spanish", "English"],
         "keywords": ["waypoint", "corridor", "clearance", "obstacle", "Nav2", "ROS2"]
       }
       ```
    2. Parte `audio` (`Content-Type: audio/wav`, filename `command.wav`): Contenedor RIFF/WAVE mono (`1 channel`), 16-bit signed integer PCM (`pcm_s16le`) a `24000` Hz o `16000` Hz.
  - Respuesta JSON: `{"sessionId":"...","transcript":"...","audioDurationMs":...,"turns":[...]}`.

- **`sam-3.1` (Segment Anything Model 3.1):**
  - Endpoint: `POST https://api.meta.ai/v1/responses`.
  - Exige un mensaje `role: "user"` con una parte `input_image` (`image_url: "data:image/jpeg;base64,..."`) y una parte `input_text`:
    - **Concept prompt:** Un único sustantivo corto por petición (ej. `"box"`, `"pallet"`, `"cone"` — sin comas ni listas múltiples).
    - **Box/Point prompt:** Tokens en píxeles reales `<0f>1<|box;x1=...;y1=...;x2=...;y2=...;w=...;h=...|>` obtenidos desde el Perception Grounding de `muse-spark-1.3`.
  - Devuelve registros por objeto en `output_text`: `<0f>0<|box;x1=...;y1=...;x2=...;y2=...;w=...;h=...;c=...|><|mask;x=0;y=0;data=H,W,!base85|>`.

