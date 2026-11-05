# Reading SAM 3.1 Segmentation Output

> **Official Documentation Reference:** [Meta Model API - Reading Segmentation](https://dev.meta.ai/docs/sam/reading-segmentation)

Segmentation output arrives as special-token text in one `output_text` lane: one line per frame, with boxes and masks inline.

---

## 1. Line Structure & Grammar

The output nests top-down:
1. The `output_text` lane holds one line per frame.
2. A line is a frame marker `<Nf>` followed by comma-separated records.
3. A record is an object ID, then one box token, then one mask token.
4. Every record pairs exactly one box with exactly one mask, box first.

### Wire Format Grammar:
```text
line       ::= FRAME record ( "," record )*
record     ::= OBJECT_ID box mask
box        ::= "<|box;x1=" INT ";y1=" INT ";x2=" INT ";y2=" INT ";w=" INT ";h=" INT [ CONF ] "|>"
mask       ::= "<|mask;x=0;y=0" [ CONF ] ";data=" H "," W "," ENC PAYLOAD "|>"
CONF       ::= ";c=" DECIMAL ; confidence, 0 to 1; only when include_confidence="true"
FRAME      ::= "<" INT "f>"  ; frame index (e.g. <0f>)
OBJECT_ID  ::= INT           ; stable object ID across frames
INT        ::= [0-9]+
H          ::= INT           ; mask raster height (rows)
W          ::= INT           ; mask raster width (columns)
ENC        ::= "~"           ; lossless default
             | "!"           ; one_bit compact
PAYLOAD    ::= <base85 bytes>; Base85-encoded (NOT Base64)
```

---

## 2. Parsing Records

### Example Line (Single Image with 2 Objects):
```text
<0f>0<|box;x1=211;y1=228;x2=270;y2=254;w=320;h=334;c=0.98|><|mask;x=0;y=0;c=0.98;data=27,60,~PAYLOAD...|>,1<|box;x1=155;y1=228;x2=202;y2=254;w=320;h=334|><|mask;x=0;y=0;data=27,48,!PAYLOAD...|>
```

### Components:
- **Frame Index:** `<0f>` indicates the first/only image frame.
- **Object ID:** Leading integer before the box token (`0`, `1`, etc.). Stable across video frames.
- **Bounding Box (`<|box;...|>`):**
  - `x1, y1`: Top-left corner in source image pixels.
  - `x2, y2`: Bottom-right corner (inclusive) in source image pixels.
  - `w, h`: Dimensions of the source media in pixels.
  - `c`: Model confidence (0.0 to 1.0) if requested.
- **Segmentation Mask (`<|mask;...|>`):**
  - `x, y`: Mask origin (always `0, 0`).
  - `H, W`: Dimensions of the mask raster (height rows × width columns).
  - `ENC`: `~` for lossless, `!` for one_bit.
  - `PAYLOAD`: Base85 encoded binary raster bytes.

---

## 3. Decoding the Mask to Raster / Polygon

To use the mask in an application:
1. In TypeScript/JavaScript:
   ```typescript
   import { decodeMaskToRaster } from "@meta-sam/parser";
   const raster = decodeMaskToRaster(record.mask);
   // Returns row-major Uint8Array of 0 or 1, length H * W.
   ```
2. In Python: the official package is `meta-sam-parser` (>= 0.0.6 required when `include_confidence` is on). The exact Python function name is documented in the SAM "Client libraries" page (not captured yet — do not assume it).
3. To extract contour polygons or convex hulls for robot path planning, iterate over the raster rows/cols or trace the boundary of pixels where value equals `1`.

