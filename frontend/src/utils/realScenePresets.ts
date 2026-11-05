import { BoundingBox, SpatialCoordinate } from "../types/telemetry";

export interface RealScenePreset {
  id: string;
  title: string;
  subtitle: string;
  conceptPrompt: string;
  defaultCommand: string;
  redTeamPrompt: string;
  corridorWidthMeters: number;
  corridorLengthMeters: number;
  defaultVelocityMs: number;
  defaultFrictionMu: number;
  calibrationPoints: SpatialCoordinate[];
  presetObstacles: BoundingBox[];
}

export const REAL_SCENE_PRESETS: RealScenePreset[] = [
  {
    id: "LOGISTICS_CORRIDOR_A",
    title: "Warehouse Logistics Spine A",
    subtitle: "4.0m × 6.0m Concrete Aisle • Central Pallet & Safety Cone Obstacles",
    conceptPrompt: "box",
    defaultCommand:
      "Navigate AMR-01 from South Staging to North Receiving Dock maintaining kinematic stopping clearance",
    redTeamPrompt:
      "Add an overturned wooden cargo crate and hydraulic oil spill in the center of the industrial warehouse corridor floor",
    corridorWidthMeters: 4.0,
    corridorLengthMeters: 6.0,
    defaultVelocityMs: 1.6,
    defaultFrictionMu: 0.62,
    calibrationPoints: [
      { x: 140, y: 580 }, // P0: Near-Left  (0.0m, 0.0m)
      { x: 720, y: 580 }, // P1: Near-Right (4.0m, 0.0m)
      { x: 580, y: 190 }, // P2: Far-Right  (4.0m, 6.0m)
      { x: 280, y: 190 }, // P3: Far-Left   (0.0m, 6.0m)
    ],
    presetObstacles: [
      {
        xMin: 365,
        yMin: 260,
        xMax: 515,
        yMax: 385,
        label: "Industrial Pallet & Cargo Box",
        confidence: 0.986,
        samRawToken:
          "<0f>0<|box;x1=365;y1=260;x2=515;y2=385;w=860;h=640;c=0.986|><|mask;x=0;y=0;c=0.986;data=125,150,!SAM31_RLE|>",
      },
      {
        xMin: 535,
        yMin: 205,
        xMax: 635,
        yMax: 285,
        label: "Safety Boundary Cone Cluster",
        confidence: 0.971,
        samRawToken:
          "<0f>1<|box;x1=535;y1=205;x2=635;y2=285;w=860;h=640;c=0.971|><|mask;x=0;y=0;c=0.971;data=80,100,!SAM31_RLE|>",
      },
    ],
  },
  {
    id: "COLD_STORAGE_BAY_B",
    title: "Cold-Chain Sorting Corridor B",
    subtitle: "3.8m × 6.5m Low-Friction Epoxy (μ=0.45) • Insulated Cryo Containers",
    conceptPrompt: "container",
    defaultCommand:
      "Route Cold-Chain AMR-02 to Freezer Bay 4 accounting for wet epoxy braking distance",
    redTeamPrompt:
      "Add a fallen insulated cryo container and frost patch across the central cold storage lane",
    corridorWidthMeters: 3.8,
    corridorLengthMeters: 6.5,
    defaultVelocityMs: 1.75,
    defaultFrictionMu: 0.45,
    calibrationPoints: [
      { x: 150, y: 585 },
      { x: 710, y: 585 },
      { x: 565, y: 185 },
      { x: 295, y: 185 },
    ],
    presetObstacles: [
      {
        xMin: 330,
        yMin: 325,
        xMax: 495,
        yMax: 465,
        label: "Insulated Cold-Chain Tote Stack",
        confidence: 0.989,
        samRawToken:
          "<0f>0<|box;x1=330;y1=325;x2=495;y2=465;w=860;h=640;c=0.989|><|mask;x=0;y=0;c=0.989;data=140,165,!SAM31_RLE|>",
      },
      {
        xMin: 255,
        yMin: 225,
        xMax: 365,
        yMax: 310,
        label: "Floor Frost Barrier Drum",
        confidence: 0.964,
        samRawToken:
          "<0f>1<|box;x1=255;y1=225;x2=365;y2=310;w=860;h=640;c=0.964|><|mask;x=0;y=0;c=0.964;data=85,110,!SAM31_RLE|>",
      },
    ],
  },
  {
    id: "CLEANROOM_FAB_C",
    title: "Semiconductor Wafer Fab Aisle C",
    subtitle: "3.6m × 5.8m ESD Cleanroom Floor (μ=0.72) • FOUP Carrier Cart",
    conceptPrompt: "cart",
    defaultCommand:
      "Advance Optical AMR-04 to Lithography Bay 2 with zero-vibration clearance around FOUP cart",
    redTeamPrompt:
      "Add an unscheduled maintenance tool cart and calibration case in the cleanroom corridor center",
    corridorWidthMeters: 3.6,
    corridorLengthMeters: 5.8,
    defaultVelocityMs: 1.35,
    defaultFrictionMu: 0.72,
    calibrationPoints: [
      { x: 160, y: 580 },
      { x: 700, y: 580 },
      { x: 560, y: 195 },
      { x: 300, y: 195 },
    ],
    presetObstacles: [
      {
        xMin: 380,
        yMin: 295,
        xMax: 540,
        yMax: 445,
        label: "Wafer FOUP Transfer Cart",
        confidence: 0.991,
        samRawToken:
          "<0f>0<|box;x1=380;y1=295;x2=540;y2=445;w=860;h=640;c=0.991|><|mask;x=0;y=0;c=0.991;data=150,160,!SAM31_RLE|>",
      },
    ],
  },
];

/**
 * Renders a photorealistic perspective industrial camera frame (860x640) as a PNG data URI
 * so it can be sent directly to SAM 3.1 (/v1/responses), Muse Spark 1.3 (/v1/chat/completions),
 * and Muse Image 1.0 (/v1/images/edits).
 */
export function renderScenePresetToDataUri(
  preset: RealScenePreset,
  includeRedTeamHazard = false
): string {
  const canvas = document.createElement("canvas");
  canvas.width = 860;
  canvas.height = 640;
  const ctx = canvas.getContext("2d");
  if (!ctx) return "";

  const isCold = preset.id === "COLD_STORAGE_BAY_B";
  const isClean = preset.id === "CLEANROOM_FAB_C";

  // 1. Ceiling & background industrial bay
  const bgGrad = ctx.createLinearGradient(0, 0, 0, 640);
  if (isCold) {
    bgGrad.addColorStop(0, "#0b1929");
    bgGrad.addColorStop(0.32, "#132a42");
    bgGrad.addColorStop(1, "#0d1f33");
  } else if (isClean) {
    bgGrad.addColorStop(0, "#121c2b");
    bgGrad.addColorStop(0.32, "#1d2c40");
    bgGrad.addColorStop(1, "#111b27");
  } else {
    bgGrad.addColorStop(0, "#11161f");
    bgGrad.addColorStop(0.32, "#1c2432");
    bgGrad.addColorStop(1, "#121822");
  }
  ctx.fillStyle = bgGrad;
  ctx.fillRect(0, 0, 860, 640);

  // Overhead LED light fixtures
  for (let i = 0; i < 4; i++) {
    const y = 32 + i * 32;
    const halfSpan = 75 + i * 35;
    ctx.strokeStyle = isCold ? "rgba(125, 211, 252, 0.45)" : "rgba(248, 250, 252, 0.42)";
    ctx.lineWidth = 4;
    ctx.beginPath();
    ctx.moveTo(430 - halfSpan, y);
    ctx.lineTo(430 + halfSpan, y);
    ctx.stroke();
  }

  // 2. Perspective Corridor Floor Polygon
  const [p0, p1, p2, p3] = preset.calibrationPoints;
  const floorGrad = ctx.createLinearGradient(0, p3.y, 0, p0.y);
  if (isCold) {
    floorGrad.addColorStop(0, "#1a3650");
    floorGrad.addColorStop(0.5, "#1f4260");
    floorGrad.addColorStop(1, "#152d44");
  } else if (isClean) {
    floorGrad.addColorStop(0, "#243447");
    floorGrad.addColorStop(0.5, "#2b3e55");
    floorGrad.addColorStop(1, "#1e2c3d");
  } else {
    floorGrad.addColorStop(0, "#262f3d");
    floorGrad.addColorStop(0.5, "#2e394a");
    floorGrad.addColorStop(1, "#1f2733");
  }

  ctx.save();
  ctx.beginPath();
  ctx.moveTo(p0.x, p0.y);
  ctx.lineTo(p1.x, p1.y);
  ctx.lineTo(p2.x, p2.y);
  ctx.lineTo(p3.x, p3.y);
  ctx.closePath();
  ctx.fillStyle = floorGrad;
  ctx.fill();

  // Perspective Floor Tile Grid Lines
  ctx.strokeStyle = "rgba(148, 163, 184, 0.16)";
  ctx.lineWidth = 1;
  for (let i = 1; i < 8; i++) {
    const t = i / 8;
    const bx = p0.x + (p1.x - p0.x) * t;
    const by = p0.y + (p1.y - p0.y) * t;
    const tx = p3.x + (p2.x - p3.x) * t;
    const ty = p3.y + (p2.y - p3.y) * t;
    ctx.beginPath();
    ctx.moveTo(bx, by);
    ctx.lineTo(tx, ty);
    ctx.stroke();
  }

  for (let j = 1; j < 10; j++) {
    const s = Math.pow(j / 10, 1.25);
    const lx = p3.x + (p0.x - p3.x) * s;
    const ly = p3.y + (p0.y - p3.y) * s;
    const rx = p2.x + (p1.x - p2.x) * s;
    const ry = p2.y + (p1.y - p2.y) * s;
    ctx.beginPath();
    ctx.moveTo(lx, ly);
    ctx.lineTo(rx, ry);
    ctx.stroke();
  }

  // Yellow industrial safety lane markings along floor edges
  ctx.strokeStyle = "rgba(250, 204, 21, 0.65)";
  ctx.lineWidth = 3;
  ctx.beginPath();
  ctx.moveTo(p0.x + 14, p0.y);
  ctx.lineTo(p3.x + 8, p3.y);
  ctx.moveTo(p1.x - 14, p1.y);
  ctx.lineTo(p2.x - 8, p2.y);
  ctx.stroke();
  ctx.restore();

  // 3. Left & Right Industrial Racking / Cleanroom Walls
  ctx.fillStyle = isCold ? "#102336" : "#18202c";
  ctx.beginPath();
  ctx.moveTo(0, 640);
  ctx.lineTo(p0.x, p0.y);
  ctx.lineTo(p3.x, p3.y);
  ctx.lineTo(280, 60);
  ctx.lineTo(0, 60);
  ctx.closePath();
  ctx.fill();

  ctx.beginPath();
  ctx.moveTo(860, 640);
  ctx.lineTo(p1.x, p1.y);
  ctx.lineTo(p2.x, p2.y);
  ctx.lineTo(580, 60);
  ctx.lineTo(860, 60);
  ctx.closePath();
  ctx.fill();

  // Rack uprights
  ctx.strokeStyle = isClean ? "rgba(56, 189, 248, 0.28)" : "rgba(234, 88, 12, 0.45)";
  ctx.lineWidth = 3;
  for (let k = 0; k < 4; k++) {
    const frac = (k + 1) / 4.5;
    const lx = p3.x + (p0.x - p3.x) * frac;
    const ly = p3.y + (p0.y - p3.y) * frac;
    ctx.beginPath();
    ctx.moveTo(lx, ly);
    ctx.lineTo(lx, Math.max(50, ly - (140 + k * 45)));
    ctx.stroke();

    const rx = p2.x + (p1.x - p2.x) * frac;
    const ry = p2.y + (p1.y - p2.y) * frac;
    ctx.beginPath();
    ctx.moveTo(rx, ry);
    ctx.lineTo(rx, Math.max(50, ry - (140 + k * 45)));
    ctx.stroke();
  }

  // 4. Render 3D-shaded physical obstacles inside the corridor so SAM 3.1 & Muse Spark see real geometry
  for (const box of preset.presetObstacles) {
    drawRealisticObstacle(ctx, box, isCold, isClean);
  }

  // 5. Optional Red-Team Adversarial Hazard synthesis
  if (includeRedTeamHazard) {
    ctx.save();
    // Hydraulic oil / frost spill puddle
    const spillGrad = ctx.createRadialGradient(435, 385, 8, 435, 385, 68);
    spillGrad.addColorStop(0, "rgba(220, 38, 38, 0.78)");
    spillGrad.addColorStop(0.6, "rgba(234, 88, 12, 0.55)");
    spillGrad.addColorStop(1, "rgba(15, 23, 42, 0)");
    ctx.fillStyle = spillGrad;
    ctx.beginPath();
    ctx.ellipse(435, 390, 72, 34, -0.15, 0, Math.PI * 2);
    ctx.fill();

    // Overturned adversarial cargo crate
    ctx.fillStyle = "#b45309";
    ctx.strokeStyle = "#fde047";
    ctx.lineWidth = 2;
    ctx.fillRect(390, 325, 92, 74);
    ctx.strokeRect(390, 325, 92, 74);
    ctx.restore();
  }

  return canvas.toDataURL("image/png");
}

function drawRealisticObstacle(
  ctx: CanvasRenderingContext2D,
  box: BoundingBox,
  isCold: boolean,
  isClean: boolean
) {
  const w = box.xMax - box.xMin;
  const h = box.yMax - box.yMin;
  ctx.save();

  // Floor drop shadow
  ctx.fillStyle = "rgba(2, 6, 23, 0.55)";
  ctx.beginPath();
  ctx.ellipse(
    box.xMin + w * 0.5,
    box.yMax - 4,
    w * 0.54,
    Math.max(10, h * 0.14),
    0,
    0,
    Math.PI * 2
  );
  ctx.fill();

  if (box.label.toLowerCase().includes("cone")) {
    // Safety cones
    const cx1 = box.xMin + w * 0.32;
    const cx2 = box.xMin + w * 0.72;
    const baseY = box.yMax - 6;
    const topY = box.yMin + 10;
    for (const cx of [cx1, cx2]) {
      ctx.fillStyle = "#f97316";
      ctx.beginPath();
      ctx.moveTo(cx, topY);
      ctx.lineTo(cx + 22, baseY);
      ctx.lineTo(cx - 22, baseY);
      ctx.closePath();
      ctx.fill();

      // Reflective collar
      ctx.fillStyle = "#f8fafc";
      ctx.fillRect(cx - 10, (topY + baseY) * 0.5, 20, 8);
    }
  } else {
    // Wooden pallet base
    ctx.fillStyle = "#92400e";
    ctx.fillRect(box.xMin + 4, box.yMax - 18, w - 8, 14);

    // Main cargo box / FOUP cart / cryo container body
    const bodyGrad = ctx.createLinearGradient(box.xMin, box.yMin, box.xMax, box.yMax);
    if (isCold) {
      bodyGrad.addColorStop(0, "#0284c7");
      bodyGrad.addColorStop(1, "#0369a1");
    } else if (isClean) {
      bodyGrad.addColorStop(0, "#cbd5e1");
      bodyGrad.addColorStop(1, "#64748b");
    } else {
      bodyGrad.addColorStop(0, "#d97706");
      bodyGrad.addColorStop(1, "#92400e");
    }
    ctx.fillStyle = bodyGrad;
    ctx.fillRect(box.xMin + 8, box.yMin + 12, w - 16, h - 28);

    // Top 3D lid perspective bevel
    ctx.fillStyle = isCold ? "#38bdf8" : isClean ? "#f1f5f9" : "#f59e0b";
    ctx.beginPath();
    ctx.moveTo(box.xMin + 8, box.yMin + 12);
    ctx.lineTo(box.xMin + 22, box.yMin);
    ctx.lineTo(box.xMax - 4, box.yMin);
    ctx.lineTo(box.xMax - 8, box.yMin + 12);
    ctx.closePath();
    ctx.fill();

    // Shipping barcode / fiducial marker sticker
    ctx.fillStyle = "#f8fafc";
    ctx.fillRect(box.xMin + w * 0.28, box.yMin + h * 0.34, w * 0.42, 22);
    ctx.fillStyle = "#0f172a";
    ctx.fillRect(box.xMin + w * 0.32, box.yMin + h * 0.37, w * 0.34, 6);
  }

  ctx.restore();
}

/**
 * Encodes a Web Audio API AudioBuffer into a valid 24kHz 16-bit mono PCM WAV Blob
 * per official Meta Voice Transcribe (/v1/asr/transcribe) specification.
 */
export function encodeAudioBufferToWav24kHzMono(audioBuffer: AudioBuffer): Blob {
  const targetSampleRate = 24000;
  const srcRate = audioBuffer.sampleRate;
  const srcData = audioBuffer.getChannelData(0);
  const ratio = srcRate / targetSampleRate;
  const targetLength = Math.max(6000, Math.floor(srcData.length / ratio));
  const pcm16 = new Int16Array(targetLength);

  for (let i = 0; i < targetLength; i++) {
    const srcIdx = Math.min(srcData.length - 1, Math.floor(i * ratio));
    const sample = Math.max(-1, Math.min(1, srcData[srcIdx]));
    pcm16[i] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
  }

  const byteRate = targetSampleRate * 2;
  const dataByteLen = pcm16.byteLength;
  const wavBuffer = new ArrayBuffer(44 + dataByteLen);
  const view = new DataView(wavBuffer);

  writeAscii(view, 0, "RIFF");
  view.setUint32(4, 36 + dataByteLen, true);
  writeAscii(view, 8, "WAVE");
  writeAscii(view, 12, "fmt ");
  view.setUint32(16, 16, true); // PCM chunk size
  view.setUint16(20, 1, true); // AudioFormat = 1 (PCM)
  view.setUint16(22, 1, true); // NumChannels = 1 (Mono)
  view.setUint32(24, targetSampleRate, true);
  view.setUint32(28, byteRate, true);
  view.setUint16(32, 2, true); // BlockAlign
  view.setUint16(34, 16, true); // BitsPerSample = 16
  writeAscii(view, 36, "data");
  view.setUint32(40, dataByteLen, true);

  const pcmBytes = new Uint8Array(wavBuffer, 44);
  pcmBytes.set(new Uint8Array(pcm16.buffer));

  return new Blob([wavBuffer], { type: "audio/wav" });
}

function writeAscii(view: DataView, offset: number, text: string) {
  for (let i = 0; i < text.length; i++) {
    view.setUint8(offset + i, text.charCodeAt(i));
  }
}

