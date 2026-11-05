# Structured Output with JSON Schema (Meta Model API)

> **Official Documentation Reference:** [Meta Model API - Structured Output](https://dev.meta.ai/docs/structured-output)

Enforce strict, 100% schema-compliant JSON output without post-processing or retry logic. The model constrains token decoding directly to match your defined JSON Schema.

---

## 1. Parameters by Endpoint

- **Chat Completions API (`POST /v1/chat/completions`):** Use parameter `response_format`.
- **Responses API (`POST /v1/responses`):** Use parameter `text.format`.

---

## 2. Using `response_format` with JSON Schema

```json
{
  "model": "muse-spark-1.3",
  "messages": [
    {
      "role": "system",
      "content": "Extract detected physical obstacles and recommended clearance zones."
    },
    {
      "role": "user",
      "content": "Analyze warehouse floor and output navigation boundaries."
    }
  ],
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "SpatialObstacleReport",
      "schema": {
        "type": "object",
        "properties": {
          "hazards": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "label": { "type": "string" },
                "severity": { "type": "number" },
                "center_x": { "type": "number" },
                "center_y": { "type": "number" },
                "clearance_radius_meters": { "type": "number" }
              },
              "required": ["label", "severity", "center_x", "center_y", "clearance_radius_meters"],
              "additionalProperties": false
            }
          },
          "operational_verdict": { "type": "string" }
        },
        "required": ["hazards", "operational_verdict"],
        "additionalProperties": false
      }
    }
  }
}
```

---

## 3. Strict Mode Rules

When `strict: true` is configured:
- Root must be a plain `object`.
- Every object must declare `additionalProperties: false`.
- Every property defined in `properties` must be listed in `required`.
- Recursive schemas (self-referencing objects) are rejected with HTTP 400.

---

## 4. Key Constraints
- **Nesting depth:** Max 10 levels.
- **Total properties:** Up to 5,000 across the entire schema.
- **Output guarantee:** Decoding is mathematically constrained; output parses with standard JSON decoders on the first attempt without markdown wrappers.

