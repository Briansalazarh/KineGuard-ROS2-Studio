# Image Understanding & Perception Grounding (Muse Spark 1.3)

> **Official Documentation Reference:** [Meta Model API - Image Understanding](https://dev.meta.ai/docs/image-understanding)

Send images alongside text prompts to get grounded text back that you can crop, measure, or render overlays on. Fully supported via **Responses API** (`/v1/responses`) and **Chat Completions API** (`/v1/chat/completions`).

---

## 1. Input Methods
Images can be provided in 3 ways (only in `user` messages):
1. **Base64 data URL:** `data:image/jpeg;base64,<encoded bytes>` (no hosting required).
2. **Public URL:** fully qualified `https://...` link.
3. **Files API:** `file_id` pre-uploaded with `purpose="user_data"`.

---

## 2. Request Formats

### Chat Completions API (`POST /v1/chat/completions`)
```json
{
  "model": "muse-spark-1.3",
  "messages": [
    {
      "role": "user",
      "content": [
        { "type": "text", "text": "Describe what you see in this image." },
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/jpeg;base64,<base64_data>"
          }
        }
      ]
    }
  ]
}
```

### Responses API (`POST /v1/responses`)
```json
{
  "model": "muse-spark-1.3",
  "input": [
    {
      "type": "message",
      "role": "user",
      "content": [
        { "type": "input_text", "text": "What is in this image?" },
        { "type": "input_image", "image_url": "data:image/jpeg;base64,<base64_data>" }
      ]
    }
  ]
}
```

---

## 3. Perception Grounding (Normalized 0–1000 Coordinate System)

Alongside scene understanding, Muse Spark reports exact spatial coordinates:
- **Grid Range:** `(0, 0)` is top-left; `(1000, 1000)` is bottom-right.
- **Resolution-Independent:** Stable whether using 640px or 4000px images.
- **Pixel Conversion Formula:**
  ```python
  x_px = round(x_norm * width / 1000)
  y_px = round(y_norm * height / 1000)
  ```

### Grounding Modes:
1. **Point (`{"x": int, "y": int}`):** For exact object locations.
2. **Box (`{"bbox": [{"x_min": int, "y_min": int, "x_max": int, "y_max": int}]}`):** For bounding regions.
3. **Count:** Points to instances and returns total count.

### Structured Coordinate Prompt Example:
```text
Locate every obstacle or machine in the image. Return a JSON array where each element has:
- "label": object name
- "box": [x_min, y_min, x_max, y_max] on a normalized 0-1000 grid.
Return only the JSON.
```

---

## 4. Limits & Supported Formats
- **MIME types:** `image/jpeg`, `image/png`, `image/webp`, `image/gif`.
- **Max file size:** 50 MB per inline base64 image; 1 GB via Files API.
- **Max images per request:** 50 images.

