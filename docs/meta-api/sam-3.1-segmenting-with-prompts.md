# Segmenting with Prompts (Meta SAM 3.1)

> **Official Documentation Reference:** [Meta Model API - SAM 3.1](https://dev.meta.ai/docs/sam/segmenting)

Segment Anything Model 3.1 (SAM 3.1) segments by concept or by location. Give it a short noun phrase naming one thing — *"yellow school bus"*, *"glasses"*, *"red hat"* — and it returns a box and a mask for every match. Or mark specific objects with boxes and points, and it segments those objects and follows them through a video.

A request names what to segment in its `input_text` part in one of two ways, never both:
1. **A concept prompt:** a short noun phrase. SAM 3.1 detects every instance of the concept.
2. **Box and point prompts:** a box or points on each object you want, under an object id you choose. SAM 3.1 segments those objects and reports each one under your id.

One **Responses API** call (`POST /v1/responses`) on `sam-3.1` carries the prompt and the media (image or video), and streams a box and a mask per object.

---

## 1. Write the Concept Prompt

The prompt goes in an `input_text` part as a short noun phrase. SAM 3.1 matches the concept you name, so concrete wording controls the result.

- **Name one concept with a noun phrase:** `"pallet"`, `"forklift"`, `"person"`, `"box"`. Commands (*"segment the bus"*) and questions (*"where is the bus?"*) are unreliable and often return nothing. Name the object; don't instruct the model.
- **One concept per request:** There is no multi-concept syntax. Comma, semicolon, and plus lists (`"bus, bicycle"`) return zero matches. To segment several concepts, send one request each.
- **Add attributes to narrow:** `"square"` matches every square; `"red square"` matches only the red ones.
- **Formatting is ignored:** Plurals, case, trailing punctuation, and whitespace do not change what the prompt matches.
- **Skip negation, boolean OR, and spatial phrasing:** *"person not wearing a hat"* and *"cat or dog"* return inconsistent results. Segment the plain concept, then filter by position or attribute in code using the returned box coordinates.
- **Keep it short:** A concise noun phrase works best. Padding with filler degrades matching.
- **SAM 3.1 locates objects; it does not reason about a scene:** For scene understanding, use the **Muse Spark** / **Muse Wonder** model lines, or a combination: use Muse Spark to identify objects/scene activity via *Image Understanding*, then pass the concept to SAM 3.1 for the segmentation.
- **Zero matches is a normal result:** If a concept is not present, the stream completes with `response.completed` and an empty `output_text`.

---

## 2. Prompt with Boxes and Points

Use box and point prompts when you already know where an object is (e.g., a user clicked it on screen, or another model found it).

The prompt goes in the `input_text` part with coordinate tokens:
```text
<0f>1<|box;x1=444;y1=626;x2=675;y2=849;w=1800;h=1200|>,2<|point;x=1430;y=700;w=1800;h=1200|>-<|point;x=1300;y=450;w=1800;h=1200|>
```

### Syntax Rules:
- **Frame marker:** `<Nf>` opens prompts for frame `N`. An image uses `<0f>` only.
- **Objects:** After the marker come comma-separated objects (`id` followed by tokens).
- **Box:** `<|box;x1=..;y1=..;x2=..;y2=..;w=..;h=..|>` marks the whole object.
- **Positive point:** `<|point;x=..;y=..;w=..;h=..|>` marks a pixel that is part of the object.
- **Negative point:** A `-` before a point marks a region that is *not* part of the object (`-<|point;...|>`).
- **Size:** Every token must carry the media's exact pixel width `w` and height `h`.

---

## 3. Request Payloads (Responses API)

### Concept Prompt Example (Image)
```json
{
  "model": "sam-3.1",
  "input": [
    {
      "type": "message",
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "pallet"
        },
        {
          "type": "input_image",
          "image_url": "https://example.com/warehouse.png"
        }
      ]
    }
  ],
  "stream": true,
  "metadata": {
    "include_confidence": "true",
    "mask_encoding": "one_bit"
  }
}
```
> Note: `image_url` can also be a Base64 data URL: `data:image/png;base64,...`.

### Box & Point Prompt Example
```json
{
  "model": "sam-3.1",
  "input": [
    {
      "type": "message",
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "<0f>1<|point;x=585;y=366;w=960;h=540|>"
        },
        {
          "type": "input_image",
          "image_url": "data:image/jpeg;base64,..."
        }
      ]
    }
  ],
  "stream": true
}
```

---

## 4. Confidence & Mask Encoding Metadata

- **`metadata.include_confidence`**: Set to `"true"` (string) to get a confidence score `c` (0.0 to 1.0) on each box and mask token.
- **`metadata.mask_encoding`**:
  - `"lossless"` (default): Returns base85 payload prefixed with `~`.
  - `"one_bit"`: Returns compact one-bit mask prefixed with `!`. Payload is several times smaller and faster.

---

## 5. Limits & Errors

- **Media format:** Images as `image_url` (public URL or base64 data URL), e.g. `data:image/png;base64,...`. Videos as MP4/MOV (H.264, H.265, MPEG-4); VP9 and WebM are NOT accepted.
- **Video:** up to 15,000 frames (~10 min) per request; tracks up to 16 objects per frame; billed per frame with a 50-frame minimum.
- **Upgrade parser before using confidence:** `@meta-sam/parser` >= 0.0.13 / `meta-sam-parser` >= 0.0.6.
- **Common 400 Bad Request Causes:**
  - Combining noun phrase with box/point prompt tokens.
  - Sending tokens with different `w` and `h` or whose aspect ratio doesn't match the image.
  - Sending points outside image bounds.
  - Using negative boxes (only points can have `-`).
  - Requesting more than 16 prompted objects.

