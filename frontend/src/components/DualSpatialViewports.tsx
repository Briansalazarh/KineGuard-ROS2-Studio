import React, { useEffect, useRef, useState } from "react";
import { formatMetric } from "../utils/formatMetric";
import {
  Camera,
  CheckCircle2,
  Compass,
  Crosshair,
  Eye,
  Grid,
  Layers,
  ShieldAlert,
  Sparkles,
  Upload,
} from "lucide-react";
import {
  SpatialCoordinate,
  VerifiableSceneResponse,
} from "../types/telemetry";
import {
  ROBOT_RADIUS_METERS,
  requiredClearanceMeters,
  stoppingDistanceMeters,
} from "../utils/kinematics";
import { RealScenePreset } from "../utils/realScenePresets";

interface DualSpatialViewportsProps {
  presets: RealScenePreset[];
  activePreset: RealScenePreset;
  onSelectPreset: (preset: RealScenePreset) => void;
  sceneDataUri: string;
  onCustomImageUpload: (dataUri: string) => void;
  calibrationPoints: SpatialCoordinate[];
  onCalibrationChange: (pts: SpatialCoordinate[]) => void;
  verifiableResult: VerifiableSceneResponse | null;
  velocityMs: number;
  frictionMu: number;
  corridorWidthMeters?: number;
  corridorLengthMeters?: number;
  imageSource?: "PRESET_SCENE" | "USER_UPLOAD";
  isAnalyzing: boolean;
  activeExecutionId?: string | null;
}

export const DualSpatialViewports: React.FC<DualSpatialViewportsProps> = ({
  presets,
  activePreset,
  onSelectPreset,
  sceneDataUri,
  onCustomImageUpload,
  calibrationPoints,
  onCalibrationChange,
  verifiableResult,
  velocityMs,
  frictionMu,
  corridorWidthMeters,
  corridorLengthMeters,
  imageSource,
  isAnalyzing,
  activeExecutionId,
}) => {
  const leftCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const rightCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const loadedImageRef = useRef<HTMLImageElement | null>(null);

  const [draggingCornerIdx, setDraggingCornerIdx] = useState<number | null>(null);
  const [showSamMasks, setShowSamMasks] = useState(true);
  const [showHomographyGrid, setShowHomographyGrid] = useState(true);
  const [showRejectedTurn1, setShowRejectedTurn1] = useState(true);

  // Load current scene image into an HTMLImageElement for 60FPS canvas rendering
  useEffect(() => {
    const src = verifiableResult?.activeImageDataUri || sceneDataUri;
    if (!src) return;
    const img = new Image();
    img.onload = () => {
      loadedImageRef.current = img;
    };
    img.src = src;
  }, [sceneDataUri, verifiableResult?.activeImageDataUri]);

  // 60FPS synchronized dual-viewport render loop
  useEffect(() => {
    let animId = 0;

    const render = (timeMs: number) => {
      renderLeftPerspectiveCanvas(timeMs);
      renderRightMetricNav2Canvas(timeMs);
      animId = requestAnimationFrame(render);
    };

    animId = requestAnimationFrame(render);
    return () => cancelAnimationFrame(animId);
  });

  const renderLeftPerspectiveCanvas = (timeMs: number) => {
    const canvas = leftCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const W = canvas.width;
    const H = canvas.height;
    ctx.clearRect(0, 0, W, H);

    // 1. Draw real camera perspective frame
    if (loadedImageRef.current) {
      ctx.drawImage(loadedImageRef.current, 0, 0, W, H);
    } else {
      ctx.fillStyle = "#0f172a";
      ctx.fillRect(0, 0, W, H);
    }

    // 2. Draw 4-point Homography Calibration Floor Polygon
    if (showHomographyGrid && calibrationPoints.length === 4) {
      const [p0, p1, p2, p3] = calibrationPoints;
      ctx.save();
      ctx.beginPath();
      ctx.moveTo(p0.x, p0.y);
      ctx.lineTo(p1.x, p1.y);
      ctx.lineTo(p2.x, p2.y);
      ctx.lineTo(p3.x, p3.y);
      ctx.closePath();
      ctx.fillStyle = "rgba(6, 182, 212, 0.08)";
      ctx.fill();
      ctx.strokeStyle = "rgba(34, 211, 238, 0.75)";
      ctx.lineWidth = 2;
      ctx.setLineDash([6, 4]);
      ctx.stroke();
      ctx.setLineDash([]);

      // Perspective homography subdivision lines
      ctx.strokeStyle = "rgba(34, 211, 238, 0.22)";
      ctx.lineWidth = 1;
      for (let i = 1; i <= 3; i++) {
        const t = i / 4;
        const bx = p0.x + (p1.x - p0.x) * t;
        const by = p0.y + (p1.y - p0.y) * t;
        const tx = p3.x + (p2.x - p3.x) * t;
        const ty = p3.y + (p2.y - p3.y) * t;
        ctx.beginPath();
        ctx.moveTo(bx, by);
        ctx.lineTo(tx, ty);
        ctx.stroke();
      }
      ctx.restore();
    }

    // 3. Draw SAM 3.1 Segmentation Masks & Grounded Boxes
    const obstacles = verifiableResult?.obstacles || [];
    if (showSamMasks) {
      for (const obs of obstacles) {
        const b = obs.pixelBox;
        const bw = b.xMax - b.xMin;
        const bh = b.yMax - b.yMin;
        const isAdversarial = obs.id.includes("rt") || obs.label.toLowerCase().includes("adversarial");

        ctx.save();
        // Translucent SAM 3.1 segmentation mask overlay
        ctx.fillStyle = isAdversarial
          ? "rgba(244, 63, 94, 0.28)"
          : "rgba(168, 85, 247, 0.24)";
        ctx.fillRect(b.xMin, b.yMin, bw, bh);

        // Ground contact footprint highlight
        const footTop = b.yMax - Math.max(18, bh * 0.32);
        ctx.fillStyle = isAdversarial
          ? "rgba(244, 63, 94, 0.42)"
          : "rgba(249, 115, 22, 0.36)";
        ctx.fillRect(b.xMin, footTop, bw, b.yMax - footTop);

        // Bounding box border
        ctx.strokeStyle = isAdversarial ? "#fb7185" : "#c084fc";
        ctx.lineWidth = 2;
        ctx.strokeRect(b.xMin, b.yMin, bw, bh);

        // SAM 3.1 label pill
        const badgeText = `${obs.label} (${formatMetric(obs.confidence * 100, 1, "%")})`;
        ctx.font = "600 11px Inter, system-ui, sans-serif";
        const textWidth = ctx.measureText(badgeText).width;
        ctx.fillStyle = "rgba(15, 23, 42, 0.92)";
        ctx.fillRect(b.xMin, Math.max(4, b.yMin - 22), textWidth + 14, 20);
        ctx.strokeStyle = isAdversarial ? "#fb7185" : "#c084fc";
        ctx.strokeRect(b.xMin, Math.max(4, b.yMin - 22), textWidth + 14, 20);
        ctx.fillStyle = "#f8fafc";
        ctx.fillText(badgeText, b.xMin + 7, Math.max(18, b.yMin - 8));
        ctx.restore();
      }
    }

    // 4. Draw Turn 1 REJECTED Trajectory (Red Dashed)
    const rejectedWps = verifiableResult?.rejectedWaypoints || [];
    if (showRejectedTurn1 && rejectedWps.length >= 2) {
      ctx.save();
      ctx.strokeStyle = "rgba(244, 63, 94, 0.9)";
      ctx.lineWidth = 3;
      ctx.setLineDash([7, 5]);
      ctx.beginPath();
      rejectedWps.forEach((wp, i) => {
        if (i === 0) ctx.moveTo(wp.uPixel, wp.vPixel);
        else ctx.lineTo(wp.uPixel, wp.vPixel);
      });
      ctx.stroke();
      ctx.setLineDash([]);

      // Draw Red X at Turn 1 violation midpoint
      const vWp = rejectedWps[1];
      ctx.fillStyle = "#e11d48";
      ctx.beginPath();
      ctx.arc(vWp.uPixel, vWp.vPixel, 9, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#ffffff";
      ctx.font = "700 10px JetBrains Mono, monospace";
      ctx.fillText("✕ T1 REJECTED", vWp.uPixel + 13, vWp.vPixel + 4);
      ctx.restore();
    }

    // 5. Draw Turn 2 APPROVED Trajectory (Glowing Emerald/Cyan) & Animated AMR
    // Animate only an explicitly APPROVED route, never a rejected payload's waypoints.
    const verifiedWps = verifiableResult?.verifiedWaypoints || [];
    if (verifiableResult?.verificationStatus === "APPROVED" && verifiedWps.length >= 2) {
      ctx.save();
      ctx.shadowColor = "rgba(16, 185, 129, 0.7)";
      ctx.shadowBlur = 10;
      ctx.strokeStyle = "#10b981";
      ctx.lineWidth = 4;
      ctx.beginPath();
      verifiedWps.forEach((wp, i) => {
        if (i === 0) ctx.moveTo(wp.uPixel, wp.vPixel);
        else ctx.lineTo(wp.uPixel, wp.vPixel);
      });
      ctx.stroke();
      ctx.shadowBlur = 0;

      // Draw waypoint nodes W0..W3
      verifiedWps.forEach((wp) => {
        ctx.fillStyle = "#059669";
        ctx.strokeStyle = "#a7f3d0";
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(wp.uPixel, wp.vPixel, 6, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = "#ecfdf5";
        ctx.font = "700 10px JetBrains Mono, monospace";
        ctx.fillText(
          `${wp.id} (${formatMetric(wp.xMeters, 1, "m")},${formatMetric(wp.yMeters, 1, "m")})`,
          wp.uPixel - 36,
          wp.vPixel - 10
        );
      });

      // Animate AMR-01 along the verified trajectory
      const animPos = interpolateAlongWaypoints(verifiedWps, timeMs);
      ctx.fillStyle = "#06b6d4";
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      ctx.arc(animPos.u, animPos.v, 11, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = "#0f172a";
      ctx.font = "800 9px JetBrains Mono, monospace";
      ctx.fillText("AMR", animPos.u - 10, animPos.v + 3);
      ctx.restore();
    }

    // 6. Draw Draggable Calibration Handles P0..P3
    if (showHomographyGrid) {
      const labelW = corridorWidthMeters ?? activePreset.corridorWidthMeters;
      const labelL = corridorLengthMeters ?? activePreset.corridorLengthMeters;
      const cornerLabels = [
        "P0 (0m, 0m)",
        `P1 (${labelW}m, 0m)`,
        `P2 (${labelW}m, ${labelL}m)`,
        `P3 (0m, ${labelL}m)`,
      ];
      calibrationPoints.forEach((pt, idx) => {
        ctx.save();
        ctx.fillStyle = draggingCornerIdx === idx ? "#facc15" : "#22d3ee";
        ctx.strokeStyle = "#0f172a";
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(pt.x, pt.y, 7, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = "rgba(15, 23, 42, 0.86)";
        ctx.fillRect(pt.x + 9, pt.y - 10, 92, 17);
        ctx.fillStyle = "#67e8f9";
        ctx.font = "600 10px JetBrains Mono, monospace";
        ctx.fillText(cornerLabels[idx], pt.x + 13, pt.y + 2);
        ctx.restore();
      });
    }
  };

  const renderRightMetricNav2Canvas = (timeMs: number) => {
    const canvas = rightCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const W = canvas.width;
    const H = canvas.height;
    ctx.clearRect(0, 0, W, H);

    // Background Nav2 map frame
    ctx.fillStyle = "#090f1d";
    ctx.fillRect(0, 0, W, H);

    const widthM = verifiableResult?.corridorWidthMeters || corridorWidthMeters || activePreset.corridorWidthMeters;
    const lengthM = verifiableResult?.corridorLengthMeters || corridorLengthMeters || activePreset.corridorLengthMeters;

    const padX = 58;
    const padY = 44;
    const plotW = W - padX * 2;
    const plotH = H - padY * 2;
    const scale = Math.min(plotW / widthM, plotH / lengthM);
    const originX = (W - widthM * scale) * 0.5;
    const originY = H - padY; // yMeters = 0 at bottom, yMeters = lengthM at top

    const toCanvas = (xM: number, yM: number) => ({
      x: originX + xM * scale,
      y: originY - yM * scale,
    });

    // 1. Draw Nav2 Free Navigable Corridor Floor
    ctx.fillStyle = "#111c30";
    ctx.strokeStyle = "#334155";
    ctx.lineWidth = 2;
    ctx.fillRect(originX, originY - lengthM * scale, widthM * scale, lengthM * scale);
    ctx.strokeRect(originX, originY - lengthM * scale, widthM * scale, lengthM * scale);

    // 2. Draw 0.5m Metric Grid Lines & Axis Labels
    ctx.strokeStyle = "rgba(148, 163, 184, 0.14)";
    ctx.lineWidth = 1;
    ctx.fillStyle = "#94a3b8";
    ctx.font = "500 10px JetBrains Mono, monospace";

    for (let xm = 0; xm <= widthM + 0.01; xm += 0.5) {
      const p = toCanvas(xm, 0);
      ctx.beginPath();
      ctx.moveTo(p.x, originY);
      ctx.lineTo(p.x, originY - lengthM * scale);
      ctx.stroke();
      if (Math.abs(xm - Math.round(xm)) < 0.01) {
        ctx.fillText(`${xm.toFixed(1)}m`, p.x - 14, originY + 16);
      }
    }

    for (let ym = 0; ym <= lengthM + 0.01; ym += 0.5) {
      const p = toCanvas(0, ym);
      ctx.beginPath();
      ctx.moveTo(originX, p.y);
      ctx.lineTo(originX + widthM * scale, p.y);
      ctx.stroke();
      if (Math.abs(ym - Math.round(ym)) < 0.01) {
        ctx.fillText(`${ym.toFixed(1)}m`, originX - 36, p.y + 3);
      }
    }

    // 3. Draw Obstacles + Kinematic Braking Inflation Zones (d_stop = v^2 / 2ug + v*t_react)
    const obstacles = verifiableResult?.obstacles || [];
    for (const obs of obstacles) {
      const c = toCanvas(obs.centerXMeters, obs.centerYMeters);
      const rCore = obs.radiusMeters * scale;
      const rInflated = obs.inflatedRadiusMeters * scale;

      ctx.save();
      // Kinematic braking inflation zone (Nav2 InflationLayer, not a PGM value)
      ctx.fillStyle = "rgba(244, 63, 94, 0.16)";
      ctx.strokeStyle = "rgba(251, 113, 133, 0.65)";
      ctx.lineWidth = 1.5;
      ctx.setLineDash([5, 4]);
      ctx.beginPath();
      ctx.arc(c.x, c.y, rInflated, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.setLineDash([]);

      // Occupied core footprint (Nav2 lethal obstacle = 0)
      ctx.fillStyle = "rgba(225, 29, 72, 0.78)";
      ctx.strokeStyle = "#fda4af";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(c.x, c.y, rCore, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = "#f8fafc";
      ctx.font = "600 10px JetBrains Mono, monospace";
      ctx.fillText(
        `${obs.id.toUpperCase()} (${formatMetric(obs.centerXMeters, 2, "m")}, ${formatMetric(obs.centerYMeters, 2, "m")})`,
        c.x - 45,
        c.y - rCore - 6
      );
      ctx.restore();
    }

    // 4. Draw Turn 1 REJECTED Trajectory in Metric Space
    const rejectedWps = verifiableResult?.rejectedWaypoints || [];
    if (showRejectedTurn1 && rejectedWps.length >= 2) {
      ctx.save();
      ctx.strokeStyle = "rgba(244, 63, 94, 0.88)";
      ctx.lineWidth = 2.5;
      ctx.setLineDash([6, 4]);
      ctx.beginPath();
      rejectedWps.forEach((wp, i) => {
        const pt = toCanvas(wp.xMeters, wp.yMeters);
        if (i === 0) ctx.moveTo(pt.x, pt.y);
        else ctx.lineTo(pt.x, pt.y);
      });
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();
    }

    // 5. Draw Turn 2 APPROVED Nav2 Trajectory & Animated AMR with Dynamic Braking Ring
    const verifiedWps = verifiableResult?.verifiedWaypoints || [];
    if (verifiableResult?.verificationStatus === "APPROVED" && verifiedWps.length >= 2) {
      ctx.save();
      ctx.shadowColor = "rgba(16, 185, 129, 0.65)";
      ctx.shadowBlur = 8;
      ctx.strokeStyle = "#10b981";
      ctx.lineWidth = 3.5;
      ctx.beginPath();
      verifiedWps.forEach((wp, i) => {
        const pt = toCanvas(wp.xMeters, wp.yMeters);
        if (i === 0) ctx.moveTo(pt.x, pt.y);
        else ctx.lineTo(pt.x, pt.y);
      });
      ctx.stroke();
      ctx.shadowBlur = 0;

      verifiedWps.forEach((wp) => {
        const pt = toCanvas(wp.xMeters, wp.yMeters);
        ctx.fillStyle = "#10b981";
        ctx.strokeStyle = "#052e16";
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(pt.x, pt.y, 5.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = "#a7f3d0";
        ctx.font = "600 10px JetBrains Mono, monospace";
        ctx.fillText(
          `${wp.id}(${formatMetric(wp.xMeters, 2, "m")},${formatMetric(wp.yMeters, 2, "m")})`,
          pt.x + 8,
          pt.y + 3
        );
      });

      // Animate AMR in metric top-down view
      const animPos = interpolateAlongWaypoints(verifiedWps, timeMs);
      const robotPt = toCanvas(animPos.xM, animPos.yM);
      const dStop =
        verifiableResult?.stoppingDistanceMeters ??
        stoppingDistanceMeters(velocityMs, frictionMu);
      const dReqHalo =
        verifiableResult?.requiredClearanceMeters ??
        requiredClearanceMeters(velocityMs, frictionMu);

      // Required-clearance halo (kernel d_req envelope)
      ctx.strokeStyle = "rgba(34, 211, 238, 0.65)";
      ctx.fillStyle = "rgba(6, 182, 212, 0.14)";
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(robotPt.x, robotPt.y, dReqHalo * scale, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();

      // Physical AMR footprint (kernel robot radius)
      ctx.fillStyle = "#06b6d4";
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(robotPt.x, robotPt.y, Math.max(8, ROBOT_RADIUS_METERS * scale), 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.restore();
    }

    // 6. Top-right metric physics legend badge
    const dStop =
      verifiableResult?.stoppingDistanceMeters ??
      stoppingDistanceMeters(velocityMs, frictionMu);
    const dReq = verifiableResult?.requiredClearanceMeters ?? requiredClearanceMeters(velocityMs, frictionMu);

    ctx.fillStyle = "rgba(15, 23, 42, 0.9)";
    ctx.strokeStyle = "rgba(51, 65, 85, 0.9)";
    ctx.lineWidth = 1;
    ctx.fillRect(W - 238, 12, 226, 62);
    ctx.strokeRect(W - 238, 12, 226, 62);

    ctx.fillStyle = "#38bdf8";
    ctx.font = "700 10px JetBrains Mono, monospace";
    ctx.fillText(`d_stop = v·0.15+v²/(2·a_eff) = ${formatMetric(dStop, 2, "m")}`, W - 228, 30);
    ctx.fillStyle = "#fb7185";
    ctx.fillText(`Required Clearance d_min = ${formatMetric(dReq, 2, "m")}`, W - 228, 47);
    ctx.fillStyle = "#34d399";
    ctx.fillText(`Nav2 Grid: 0.05m/cell (${widthM}m × ${lengthM}m)`, W - 228, 64);
  };

  // Pointer dragging for the 4 floor homography calibration nodes on the left canvas
  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = leftCanvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    const x = (e.clientX - rect.left) * scaleX;
    const y = (e.clientY - rect.top) * scaleY;

    const hitIdx = calibrationPoints.findIndex(
      (pt) => Math.hypot(pt.x - x, pt.y - y) <= 18
    );
    if (hitIdx !== -1) {
      setDraggingCornerIdx(hitIdx);
      e.currentTarget.setPointerCapture(e.pointerId);
    }
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (draggingCornerIdx === null) return;
    const canvas = leftCanvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    const x = Math.max(20, Math.min(canvas.width - 20, (e.clientX - rect.left) * scaleX));
    const y = Math.max(80, Math.min(canvas.height - 20, (e.clientY - rect.top) * scaleY));

    const next = calibrationPoints.map((pt, i) =>
      i === draggingCornerIdx ? { x: Math.round(x), y: Math.round(y) } : pt
    );
    onCalibrationChange(next);
  };

  const handlePointerUp = () => {
    setDraggingCornerIdx(null);
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === "string") {
        onCustomImageUpload(reader.result);
      }
    };
    reader.readAsDataURL(file);
  };

  return (
    <div className="flex flex-col gap-3">
      {/* Top Scene Selector & Overlay Controls */}
      <div className="flex flex-wrap items-center justify-between gap-2 bg-slate-900/90 border border-slate-800 rounded-xl px-3.5 py-2.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[11px] font-mono uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
            <Camera className="w-3.5 h-3.5 text-cyan-400" />
            Real-World Corridor Feed:
          </span>
          {presets.map((preset) => {
            const active = preset.id === activePreset.id;
            return (
              <button
                key={preset.id}
                onClick={() => onSelectPreset(preset)}
                disabled={isAnalyzing}
                className={`px-2.5 py-1 rounded-lg text-xs font-medium transition-all border disabled:opacity-50 ${
                  active
                    ? "bg-cyan-500/20 border-cyan-400 text-cyan-200 shadow-sm"
                    : "bg-slate-800/70 border-slate-700 text-slate-300 hover:bg-slate-800"
                }`}
              >
                {preset.title}
              </button>
            );
          })}

          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            onChange={handleFileUpload}
            className="hidden"
          />
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={isAnalyzing}
            className="px-2.5 py-1 rounded-lg text-xs font-medium bg-slate-800/80 border border-slate-700 text-slate-200 hover:border-cyan-500/50 disabled:opacity-50 flex items-center gap-1.5"
          >
            <Upload className="w-3.5 h-3.5 text-cyan-400" />
            Upload Photo
          </button>
        </div>

        {/* Layer Toggles */}
        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowSamMasks((v) => !v)}
            className={`px-2.5 py-1 rounded-md text-[11px] font-mono border flex items-center gap-1 ${
              showSamMasks
                ? "bg-purple-500/20 border-purple-400/60 text-purple-200"
                : "bg-slate-800 border-slate-700 text-slate-400"
            }`}
          >
            <Eye className="w-3 h-3" />
            SAM 3.1 Masks
          </button>
          <button
            onClick={() => setShowHomographyGrid((v) => !v)}
            className={`px-2.5 py-1 rounded-md text-[11px] font-mono border flex items-center gap-1 ${
              showHomographyGrid
                ? "bg-cyan-500/20 border-cyan-400/60 text-cyan-200"
                : "bg-slate-800 border-slate-700 text-slate-400"
            }`}
          >
            <Crosshair className="w-3 h-3" />
            4-Pt Homography
          </button>
          <button
            onClick={() => setShowRejectedTurn1((v) => !v)}
            className={`px-2.5 py-1 rounded-md text-[11px] font-mono border flex items-center gap-1 ${
              showRejectedTurn1
                ? "bg-rose-500/20 border-rose-400/60 text-rose-200"
                : "bg-slate-800 border-slate-700 text-slate-400"
            }`}
          >
            <Layers className="w-3 h-3" />
            Turn 1 Rejected Path
          </button>
        </div>
      </div>

      {/* Synchronized Dual Viewports */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-3">
        {/* LEFT VIEWPORT: REAL CAMERA PERSPECTIVE + SAM 3.1 & HOMOGRAPHY */}
        <div className="bg-slate-900/95 border border-slate-800 rounded-xl overflow-hidden flex flex-col">
          <div className="px-3.5 py-2 border-b border-slate-800 flex items-center justify-between bg-slate-950/60">
            <div className="flex items-center gap-2">
              <Sparkles className="w-4 h-4 text-purple-400" />
              <span className="text-xs font-mono font-semibold uppercase tracking-wider text-slate-200">
                {(imageSource ?? verifiableResult?.imageSource) === "USER_UPLOAD"
                  ? "USER PHOTO + SAM 3.1 & HOMOGRAPHY"
                  : "PRESET SCENE (CANVAS) + SAM 3.1 & HOMOGRAPHY"}
              </span>
            </div>
            <span className="text-[11px] font-mono text-cyan-300 bg-cyan-950/60 border border-cyan-800/60 px-2 py-0.5 rounded">
              Drag P0–P3 Floor Nodes
            </span>
          </div>

          <div className="relative aspect-[860/640] w-full bg-slate-950">
            <canvas
              ref={leftCanvasRef}
              width={860}
              height={640}
              onPointerDown={handlePointerDown}
              onPointerMove={handlePointerMove}
              onPointerUp={handlePointerUp}
              className="w-full h-full object-contain cursor-crosshair touch-none"
            />
            {isAnalyzing && (
              <div className="absolute inset-0 bg-slate-950/65 backdrop-blur-xs flex items-center justify-center">
                <div className="px-4 py-2.5 rounded-xl bg-slate-900 border border-cyan-500/50 text-cyan-300 font-mono text-xs flex items-center gap-2.5 shadow-lg">
                  <div className="w-3.5 h-3.5 border-2 border-cyan-400 border-t-transparent rounded-full animate-spin" />
                  <span>
                    Running{" "}
                    {activeExecutionId ? `[${activeExecutionId}] ` : ""}
                    SAM 3.1 + Muse Spark + KinematicSafetyKernel Tool Loop...
                  </span>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* RIGHT VIEWPORT: METRIC TOP-DOWN NAV2 OCCUPANCY GRID (METERS) */}
        <div className="bg-slate-900/95 border border-slate-800 rounded-xl overflow-hidden flex flex-col">
          <div className="px-3.5 py-2 border-b border-slate-800 flex items-center justify-between bg-slate-950/60">
            <div className="flex items-center gap-2">
              <Grid className="w-4 h-4 text-emerald-400" />
              <span className="text-xs font-mono font-semibold uppercase tracking-wider text-slate-200">
                METRIC TOP-DOWN NAV2 OCCUPANCY GRID (METERS)
              </span>
            </div>
            <div className="flex items-center gap-2 text-[11px] font-mono">
              <span className="text-rose-400 flex items-center gap-1">
                <ShieldAlert className="w-3.5 h-3.5" />
                T1 Rejected
              </span>
              {verifiableResult?.verificationStatus === "APPROVED" &&
                verifiableResult.verifiedWaypoints.length > 0 && (
                  <span className="text-emerald-400 flex items-center gap-1">
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    T2 Verified
                  </span>
                )}
            </div>
          </div>

          <div className="relative aspect-[860/640] w-full bg-slate-950">
            <canvas
              ref={rightCanvasRef}
              width={860}
              height={640}
              className="w-full h-full object-contain"
            />
          </div>
        </div>
      </div>

      {/* Bottom Homography & Physical Invariant Summary Strip */}
      <div className="bg-slate-900/80 border border-slate-800/90 rounded-xl px-3.5 py-2 flex flex-wrap items-center justify-between gap-2 text-xs font-mono">
        <div className="flex items-center gap-2 text-slate-300">
          <Compass className="w-4 h-4 text-cyan-400" />
          <span>
            Homography 3×3 Matrix H:{" "}
            <span className="text-cyan-300">
              {verifiableResult?.homographyMatrix
                ? `[${verifiableResult.homographyMatrix
                    .slice(0, 3)
                    .map((n) => formatMetric(n, 4))
                    .join(", ")}, ...]`
                : "— (sin ejecución)"}
            </span>
          </span>
          {(verifiableResult?.executionId || activeExecutionId) && (
            <span className="px-1.5 py-0.5 rounded bg-slate-800 border border-slate-700 text-[10px] text-cyan-300">
              execId: {verifiableResult?.executionId || activeExecutionId}
            </span>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-4 text-slate-300">
          <span>
            SAM 3.1 Concept:{" "}
            <strong className="text-purple-300">
              "{verifiableResult?.conceptPrompt || activePreset.conceptPrompt}"
            </strong>
          </span>
          <span>
            Obstacles Projected:{" "}
            <strong className="text-amber-300">
              {isAnalyzing
                ? "PENDING"
                : verifiableResult
                ? verifiableResult.obstacles.length
                : "IDLE"}
            </strong>
          </span>
          <span>
            Verification:{" "}
            {isAnalyzing ? (
              <strong className="text-cyan-400">
                RUNNING / PENDING ({activeExecutionId || "in-flight"})
              </strong>
            ) : !verifiableResult ? (
              <strong className="text-slate-400">
                IDLE (Ready — Click Run Verifiable Pipeline)
              </strong>
            ) : (
              <>
                <strong
                  className={
                    verifiableResult.verificationStatus === "APPROVED"
                      ? "text-emerald-400"
                      : verifiableResult.verificationStatus === "REJECTED"
                      ? "text-rose-400"
                      : "text-amber-400"
                  }
                >
                  {verifiableResult.verificationStatus || "UNVERIFIED"}
                </strong>{" "}
                {verifiableResult.agentTurns?.[1]
                  ? `(Min Clearance: ${formatMetric(verifiableResult.agentTurns[1].minClearanceMeters, 2, "m")})`
                  : `(${verifiableResult.verifiedWaypoints?.length ?? 0} Poses Approved)`}
              </>
            )}
          </span>
        </div>
      </div>
    </div>
  );
};

/** Piecewise-linear along the verified polyline, paced by metric segment length. */
export function interpolateAlongWaypoints(
  waypoints: { uPixel: number; vPixel: number; xMeters: number; yMeters: number }[],
  timeMs: number
) {
  if (waypoints.length === 0) {
    return { u: 430, v: 500, xM: 2.0, yM: 1.0 };
  }
  if (waypoints.length === 1) {
    return {
      u: waypoints[0].uPixel,
      v: waypoints[0].vPixel,
      xM: waypoints[0].xMeters,
      yM: waypoints[0].yMeters,
    };
  }
  const segLens: number[] = [];
  let totalLen = 0;
  for (let i = 1; i < waypoints.length; i++) {
    const len = Math.hypot(
      waypoints[i].xMeters - waypoints[i - 1].xMeters,
      waypoints[i].yMeters - waypoints[i - 1].yMeters
    );
    segLens.push(len);
    totalLen += len;
  }
  const cycleDurationMs = 5500;
  const cycleT = ((timeMs % cycleDurationMs) + cycleDurationMs) % cycleDurationMs / cycleDurationMs;
  let target = cycleT * totalLen;
  let segIdx = segLens.length - 1;
  let t = 1;
  for (let i = 0; i < segLens.length; i++) {
    if (target <= segLens[i] || i === segLens.length - 1) {
      segIdx = i;
      t = segLens[i] > 1e-9 ? Math.min(1, target / segLens[i]) : 0;
      break;
    }
    target -= segLens[i];
  }
  const a = waypoints[segIdx];
  const b = waypoints[segIdx + 1];

  return {
    u: a.uPixel + (b.uPixel - a.uPixel) * t,
    v: a.vPixel + (b.vPixel - a.vPixel) * t,
    xM: a.xMeters + (b.xMeters - a.xMeters) * t,
    yM: a.yMeters + (b.yMeters - a.yMeters) * t,
  };
}

