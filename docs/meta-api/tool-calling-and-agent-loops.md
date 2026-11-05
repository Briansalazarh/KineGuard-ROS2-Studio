# Tool Calling & Agent Verification Loops (Meta Model API)

> **Official Documentation Reference:** [Meta Model API - Tool Calling](https://dev.meta.ai/docs/tool-calling)

Ship agents that call client-side services and functions mid-conversation. The model evaluates whether a tool is needed, returns a `tool_calls` request, your application executes the local code (e.g. kinematic trajectory verification), and the model consumes the result to adjust its next decision.

---

## 1. Tool Calling via Responses API (Recommended for Agents)

The Responses API is flat and maintains the chain-of-thought across tool execution turns using `previous_response_id`.

### Defining the Tool:
```json
{
  "type": "function",
  "name": "verify_kinematic_safety",
  "description": "Validates proposed AMR trajectory against physical stopping distances (d = v^2 / 2ug) and SAM 3.1 obstacle polygons.",
  "parameters": {
    "type": "object",
    "properties": {
      "robot_id": { "type": "string" },
      "proposed_vx": { "type": "number" },
      "proposed_vy": { "type": "number" },
      "waypoint_x": { "type": "number" },
      "waypoint_y": { "type": "number" }
    },
    "required": ["robot_id", "proposed_vx", "proposed_vy", "waypoint_x", "waypoint_y"],
    "additionalProperties": false
  }
}
```

### Turn 1: Model requests tool call
The response output contains:
```json
{
  "type": "function_call",
  "call_id": "call_123",
  "name": "verify_kinematic_safety",
  "arguments": "{\"robot_id\":\"AMR-01\",\"proposed_vx\":5.2,\"proposed_vy\":3.1,\"waypoint_x\":450,\"waypoint_y\":620}"
}
```

### Turn 2: Client returns tool result with `previous_response_id`
```json
{
  "model": "muse-spark-1.3",
  "previous_response_id": "resp_turn1_id",
  "input": [
    {
      "type": "function_call_output",
      "call_id": "call_123",
      "output": "{\"status\":\"REJECTED\",\"reason\":\"Violates stopping distance d=4.2m near obstacle boundary. Deflect vector by +35 deg required.\"}"
    }
  ]
}
```
The model then reads the rejection and synthesizes an updated, compliant trajectory without losing context.

---

## 2. Tool Calling via Chat Completions API

Uses standard OpenAI-nested structure (`tools[].function`).

```json
{
  "model": "muse-spark-1.3",
  "messages": [...],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "verify_kinematic_safety",
        "description": "Calculates stopping distance and collision envelope.",
        "parameters": {...}
      }
    }
  ]
}
```

---

## 3. Strict Rules & Constraints
- **`tool_choice`:** Only `"auto"` is supported by Meta Model API.
- **`parallel_tool_calls`:** Defaults to `true` (can evaluate all 6 AMRs in parallel). Set to `false` if strictly sequential execution is required.
- **Function Names:** Alphanumeric, underscores, hyphens, and at most one dot (`^[a-zA-Z0-9_.-]+$`).
- **`call_id`:** Must be 1 to 64 characters long and match between call and output.

