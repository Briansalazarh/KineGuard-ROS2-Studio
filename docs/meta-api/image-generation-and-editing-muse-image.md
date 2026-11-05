# Image Generation & Editing (Muse Image 1.0)

> **Official Documentation Reference:** [Meta Model API - Image Generation with Muse Image](https://dev.meta.ai/docs/image-generation)

`muse-image-1.0` generates and edits images. One model covers the entire workflow:
1. **Generate:** Describe an image from text.
2. **Edit:** Send an existing image with an instruction, and it changes only what is requested while keeping the rest intact.
3. **Refine:** Multi-turn conversational flow via the Responses API.

---

## 1. Fast One-Off Generation (`POST /v1/images/generations`)

OpenAI Images API compatible endpoint:

```json
{
  "model": "muse-image-1.0",
  "prompt": "high-contrast thermal infrared map of industrial warehouse sector, overhead view",
  "n": 1,
  "size": "1024x1024",
  "response_format": "b64_json",
  "output_format": "png",
  "reasoning_strength": "low",
  "tool_enablement": {
    "enable_web_search": false,
    "enable_shell": false
  }
}
```

> **⚡ Performance Tip (Avoiding 14-second delays):**
> By default, `muse-image-1.0` runs with `reasoning_strength: "high"` and built-in web search. Setting `"reasoning_strength": "low"` and disabling web search/shell in `"tool_enablement"` skips multi-pass external lookups and speeds up generation dramatically!

---

## 2. Image-to-Image Editing (`POST /v1/images/edits`)

Send an input image with a text prompt describing the change:

```json
{
  "model": "muse-image-1.0",
  "prompt": "add a high-voltage electrical hazard perimeter and smoke plume in the center corridor, keep warehouse floor and racks intact",
  "images": [
    {
      "image_url": "data:image/png;base64,<base64_source_image>"
    }
  ],
  "n": 1,
  "response_format": "b64_json",
  "output_format": "png"
}
```

---

## 3. Conversational Multi-Turn Generation (`POST /v1/responses`)

Refine an image across multiple turns using `previous_response_id`:

```json
{
  "model": "muse-image-1.0",
  "previous_response_id": "resp_previous_123",
  "input": "now add warning barriers around the perimeter"
}
```

### Reading the Responses API Output:
The output array contains up to 3 items:
1. `type: "reasoning"` (summary of tool lookups and planning).
2. `type: "message"` (assistant text turn).
3. `type: "image_generation_call"` with base64 image in `result` and a signed ID to continue editing.

---

## 4. Pricing & Limits
- **Flat pricing:** $0.01 per successfully generated image (search grounding and reasoning passes are included at no extra charge).
- **Format:** `webp` (default), `png`, or `jpeg`.

