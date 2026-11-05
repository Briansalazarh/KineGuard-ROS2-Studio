# Chat Completions Protocol (Meta Model API)

> **Official Documentation Reference:** [Meta Model API - Chat Completions](https://dev.meta.ai/docs/protocols/chat-completions)

OpenAI-compatible core endpoint for single-turn or client-managed multi-turn interactions. Serves the Muse Spark text models (e.g. `muse-spark-1.3`).

> ⚠️ Not yet verified: which endpoints serve `muse-wonder-1.0`. Check the "Model availability" section of the Models page before relying on it here.

---

## 1. Endpoint: `POST /v1/chat/completions`

### Basic Request
```json
{
  "model": "muse-spark-1.3",
  "messages": [
    {
      "role": "developer",
      "content": "You are a spatial physics validation engine. Output concise waypoints."
    },
    {
      "role": "user",
      "content": "Propose collision-free chord for AMR-01 around hazard hull."
    }
  ],
  "max_completion_tokens": 250
}
```

---

## 2. Key Differences from Standard OpenAI API

- **Developer Role:** Use `role: "developer"` instead of `system` for high-precedence instructions (developer outranks user messages).
- **Muse Spark Always Reasons:**
  - `reasoning_effort`: Accepts `"minimal"`, `"low"`, `"medium"`, `"high"`, `"xhigh"`.
  - Setting `reasoning_effort: "none"` returns **HTTP 400** (disabling reasoning is not supported).
- **Sampling Defaults:** Muse Spark is specifically tuned for `temperature: 1.0` and `top_p: 1.0`. Meta explicitly recommends leaving both unset rather than lowering temperature.
- **Structured Output:** Configured via `response_format: { "type": "json_schema", ... }`.
- **Unsupported Parameters (Return HTTP 400):**
  - `stop` (not supported on reasoning models).
  - `n > 1` (only `n=1` is supported).
  - `modalities`, `audio`, `logprobs: true`, `frequency_penalty`, `presence_penalty`.
  - `text.format` (belongs to Responses API; Chat Completions uses `response_format`).

