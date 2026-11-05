# Responses API Protocol (Meta Model API)

> **Official Documentation Reference:** [Meta Model API - Responses API](https://dev.meta.ai/docs/protocols/responses)

Build multi-step agents that keep reasoning intact across turns. The Responses API is the only endpoint that carries the model's chain of thought between calls. Serves `muse-spark-1.3` (text/vision), `sam-3.1` (segmentation), and `muse-image-1.0` (conversational image generation).

---

## 1. Context Continuity Modes

1. **Server-Managed History (`previous_response_id`):**
   - The server stores conversation state (`store: true` by default).
   - In subsequent turns, send only the new input and pass `previous_response_id: "<id_anterior>"`.
2. **Stateless Encrypted Replay:**
   - Client sends full conversation each turn with `store: false` and `include: ["reasoning.encrypted_content"]`.
   - The model's chain-of-thought travels in encrypted `reasoning` items without server persistence.

---

## 2. Basic Request & Response

### Endpoint: `POST /v1/responses`

```json
{
  "model": "muse-spark-1.3",
  "input": "Analyze obstacle clearance at coordinate (450, 620)",
  "temperature": 1.0,
  "top_p": 1.0
}
```

### Multi-Turn with `previous_response_id`:
```json
{
  "model": "muse-spark-1.3",
  "previous_response_id": "resp_abc123",
  "input": "Calculate alternative BVC deflection waypoint for AMR-03"
}
```

---

## 3. Streaming & Background Execution

- **Streaming:** Set `stream: true`. Emits server-sent events (`response.output_text.delta`, `response.completed`). Long generations should always be streamed to avoid HTTP 504 timeouts.
- **Background Execution:** Set `background: true` for asynchronous task execution (status starts at `"queued"`). Retrieve status with `GET /v1/responses/{id}` or cancel with `POST /v1/responses/{id}/cancel`.

---

## 4. Key Parameter Rules & Restrictions

- **Structured Output:** Configured via `text.format` (NOT `response_format`).
- **Instructions:** Use the top-level `instructions` field for developer-level behavior rules (inserted with highest precedence).
- **Sampling:** `temperature` and `top_p` defaults are `1.0`. Adjust one or the other, not both.
- **Tools:** Supports `tools: [{"type": "web_search"}]` or custom function tools.

