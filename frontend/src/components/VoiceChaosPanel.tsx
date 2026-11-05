import React, { useRef, useState } from "react";
import {
  Download,
  Flame,
  Gauge,
  Mic,
  MicOff,
  Play,
  Sliders,
  Sparkles,
  Volume2,
} from "lucide-react";
import { encodeAudioBufferToWav24kHzMono } from "../utils/realScenePresets";
import { ScaleType } from "../types/telemetry";
import {
  lateralClearanceMeters,
  requiredClearanceMeters,
  stoppingDistanceMeters,
} from "../utils/kinematics";

interface VoiceChaosPanelProps {
  conceptPrompt: string;
  onConceptPromptChange: (val: string) => void;
  operatorCommand: string;
  onOperatorCommandChange: (val: string) => void;
  reasoningModel: string;
  onReasoningModelChange: (val: string) => void;
  velocityMs: number;
  onVelocityChange: (val: number) => void;
  frictionMu: number;
  onFrictionChange: (val: number) => void;
  corridorWidthMeters: number;
  corridorLengthMeters: number;
  onCorridorWidthChange: (val: number) => void;
  onCorridorLengthChange: (val: number) => void;
  scaleType: ScaleType;
  onScaleTypeChange: (val: ScaleType) => void;
  imageSource: "PRESET_SCENE" | "USER_UPLOAD";
  executionMode?: string;
  onRunVerifiablePipeline: () => void;
  onRunRedTeamScenario: () => void;
  onTranscribeVoice: (wavBlob: Blob | null, hint: string) => void;
  onExportRos2All: () => void;
  isBusy: boolean;
}

const SAM_CONCEPT_PRESETS = ["box", "pallet", "cone", "container", "cart"];

export const VoiceChaosPanel: React.FC<VoiceChaosPanelProps> = ({
  conceptPrompt,
  onConceptPromptChange,
  operatorCommand,
  onOperatorCommandChange,
  reasoningModel,
  onReasoningModelChange,
  velocityMs,
  onVelocityChange,
  frictionMu,
  onFrictionChange,
  corridorWidthMeters,
  corridorLengthMeters,
  onCorridorWidthChange,
  onCorridorLengthChange,
  scaleType,
  onScaleTypeChange,
  imageSource,
  executionMode,
  onRunVerifiablePipeline,
  onRunRedTeamScenario,
  onTranscribeVoice,
  onExportRos2All,
  isBusy,
}) => {
  const [isRecording, setIsRecording] = useState(false);
  const [recordingStatus, setRecordingStatus] = useState<string>(
    "Ready for 24kHz 16-bit Mono PCM WAV capture"
  );
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);

  const dStop = stoppingDistanceMeters(velocityMs, frictionMu);
  const dMin = requiredClearanceMeters(velocityMs, frictionMu);
  const dLateral = lateralClearanceMeters();

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const recorder = new MediaRecorder(stream);
      chunksRef.current = [];

      recorder.ondataavailable = (ev) => {
        if (ev.data.size > 0) {
          chunksRef.current.push(ev.data);
        }
      };

      recorder.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        try {
          const rawBlob = new Blob(chunksRef.current);
          const arrayBuf = await rawBlob.arrayBuffer();
          const audioCtx = new AudioContext();
          const decoded = await audioCtx.decodeAudioData(arrayBuf);
          const wav24kBlob = encodeAudioBufferToWav24kHzMono(decoded);
          await audioCtx.close();

          setRecordingStatus(
            `Encoded ${(wav24kBlob.size / 1024).toFixed(1)} KB 24kHz PCM WAV → Transcribing...`
          );
          onTranscribeVoice(wav24kBlob, operatorCommand);
        } catch {
          setRecordingStatus("Sent voice command directive to Muse Voice Transcribe 1.0");
          onTranscribeVoice(null, operatorCommand);
        }
      };

      mediaRecorderRef.current = recorder;
      recorder.start();
      setIsRecording(true);
      setRecordingStatus("Recording microphone audio (24kHz mono PCM target)...");
    } catch {
      setRecordingStatus("Mic unavailable — dispatching synthetic 24kHz WAV to Muse Voice 1.0");
      onTranscribeVoice(null, operatorCommand);
    }
  };

  const stopRecording = () => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== "inactive") {
      mediaRecorderRef.current.stop();
    }
    setIsRecording(false);
  };

  return (
    <div className="bg-slate-900/95 border border-slate-800 rounded-xl p-3.5 flex flex-col gap-3.5">
      {/* Section 1: Header */}
      <div className="flex items-center justify-between border-b border-slate-800 pb-2.5">
        <div className="flex items-center gap-2">
          <Sliders className="w-4 h-4 text-cyan-400" />
          <span className="text-xs font-mono font-bold uppercase tracking-wider text-slate-100">
            PHYSICAL KINEMATICS & META MODEL API CONTROLS
          </span>
        </div>
        <span className="px-2 py-0.5 rounded bg-cyan-500/15 border border-cyan-500/40 text-cyan-300 text-[10px] font-mono">
          {executionMode ? `Meta: ${executionMode}` : "Meta: sin ejecución"}
        </span>
      </div>

      {/* Section 2: Velocity & Friction Sliders with Live Braking Formula */}
      <div className="bg-slate-950/80 border border-slate-800 rounded-xl p-3 flex flex-col gap-2.5">
        <div className="flex items-center justify-between text-xs font-mono">
          <span className="text-slate-300 flex items-center gap-1.5">
            <Gauge className="w-3.5 h-3.5 text-cyan-400" />
            AMR Target Speed (v):
          </span>
          <span className="text-cyan-300 font-bold">{velocityMs.toFixed(2)} m/s</span>
        </div>
        <input
          type="range"
          min={0.6}
          max={2.6}
          step={0.05}
          value={velocityMs}
          onChange={(e) => onVelocityChange(parseFloat(e.target.value))}
          className="w-full accent-cyan-400 cursor-pointer"
        />

        <div className="flex items-center justify-between text-xs font-mono mt-1">
          <span className="text-slate-300">Floor Friction Coeff (μ):</span>
          <span className="text-amber-300 font-bold">
            {frictionMu.toFixed(2)} ({frictionMu < 0.5 ? "Wet/Epoxy" : "Dry Concrete"})
          </span>
        </div>
        <input
          type="range"
          min={0.25}
          max={0.85}
          step={0.02}
          value={frictionMu}
          onChange={(e) => onFrictionChange(parseFloat(e.target.value))}
          className="w-full accent-amber-400 cursor-pointer"
        />

        <div className="mt-1 pt-2 border-t border-slate-800/80 grid grid-cols-2 gap-2 text-[11px] font-mono">
          <div className="bg-slate-900 px-2.5 py-1.5 rounded border border-slate-800">
            <div className="text-slate-400 text-[10px]">Stopping Distance d_stop</div>
            <div className="text-cyan-300 font-bold">{dStop.toFixed(2)} meters</div>
          </div>
          <div className="bg-slate-900 px-2.5 py-1.5 rounded border border-slate-800">
            <div className="text-slate-400 text-[10px]">Required Clearance d_req (lateral {dLateral.toFixed(2)}m)</div>
            <div className="text-rose-300 font-bold">{dMin.toFixed(2)} meters</div>
          </div>
        </div>
        <div className="text-[10px] font-mono text-slate-500">
          Réplica exacta del kernel: a_eff=min(3.5, μ·g), d_stop=v·0.15+v²/(2·a_eff).
        </div>
      </div>

      {/* Corridor Dimensions & Metric Scale (required for uploaded photos) */}
      <div className="bg-slate-950/80 border border-slate-800 rounded-xl p-3 flex flex-col gap-2.5">
        <div className="flex items-center justify-between text-xs font-mono">
          <span className="text-slate-300">Corredor (m) — {imageSource === "USER_UPLOAD" ? "foto subida" : "preset"}</span>
          <span className="text-[10px] text-cyan-300">{scaleType}</span>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs font-mono">
          <label className="flex flex-col gap-1 text-slate-400">
            Ancho (m)
            <input type="number" min={1} max={20} step={0.1} value={corridorWidthMeters}
              onChange={(e) => onCorridorWidthChange(parseFloat(e.target.value))}
              className="bg-slate-900 border border-slate-700 rounded px-2 py-1 text-slate-100" />
          </label>
          <label className="flex flex-col gap-1 text-slate-400">
            Largo (m)
            <input type="number" min={1} max={30} step={0.1} value={corridorLengthMeters}
              onChange={(e) => onCorridorLengthChange(parseFloat(e.target.value))}
              className="bg-slate-900 border border-slate-700 rounded px-2 py-1 text-slate-100" />
          </label>
        </div>
        <select value={scaleType} onChange={(e) => onScaleTypeChange(e.target.value as ScaleType)}
          className="bg-slate-900 border border-slate-700 rounded px-2 py-1 text-xs font-mono text-cyan-300">
          <option value="MEASURED_REFERENCE">Escala real — medida con referencia</option>
          <option value="NOMINAL_ASSUMED">Escala nominal — dimensiones asumidas</option>
          <option value="NOMINAL_PRESET">Escala nominal — geometría del preset</option>
          <option value="UNKNOWN">Escala desconocida — sin referencia</option>
        </select>
        {scaleType === "UNKNOWN" && (
          <div className="text-[11px] font-mono text-amber-300">
            Sin escala: el resultado será UNVERIFIED y ROS 2 exportará 0 poses.
          </div>
        )}
      </div>

      {/* Section 3: SAM 3.1 Concept Noun & Reasoning Model Selector */}
      <div className="bg-slate-950/80 border border-slate-800 rounded-xl p-3 flex flex-col gap-2.5">
        <div className="flex items-center justify-between">
          <span className="text-xs font-mono text-slate-300">
            SAM 3.1 Single Concept Noun:
          </span>
          <span className="text-[10px] font-mono text-purple-300">POST /v1/responses</span>
        </div>
        <div className="flex flex-wrap gap-1.5">
          {SAM_CONCEPT_PRESETS.map((noun) => (
            <button
              key={noun}
              onClick={() => onConceptPromptChange(noun)}
              className={`px-2 py-0.5 rounded text-xs font-mono border transition-all ${
                conceptPrompt === noun
                  ? "bg-purple-500/25 border-purple-400 text-purple-200"
                  : "bg-slate-900 border-slate-700 text-slate-400 hover:text-slate-200"
              }`}
            >
              "{noun}"
            </button>
          ))}
        </div>

        <div className="flex items-center justify-between mt-1">
          <span className="text-xs font-mono text-slate-300"> Tool-Calling Planner:</span>
          <select
            value={reasoningModel}
            onChange={(e) => onReasoningModelChange(e.target.value)}
            className="bg-slate-900 border border-slate-700 rounded px-2 py-1 text-xs font-mono text-cyan-300"
          >
            <option value="muse-spark-1.3">muse-spark-1.3 (Fast Agent)</option>
            <option value="muse-wonder-1.0">muse-wonder-1.0 (Frontier Reasoning)</option>
          </select>
        </div>
      </div>

      {/* Section 4: Muse Voice Transcribe 24kHz Recorder & Operator Directive */}
      <div className="bg-slate-950/80 border border-slate-800 rounded-xl p-3 flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <span className="text-xs font-mono text-slate-300 flex items-center gap-1.5">
            <Volume2 className="w-3.5 h-3.5 text-emerald-400" />
            Muse Voice Transcribe (24kHz WAV):
          </span>
          <button
            onClick={isRecording ? stopRecording : startRecording}
            disabled={isBusy}
            className={`px-2.5 py-1 rounded-lg text-xs font-mono font-semibold flex items-center gap-1.5 transition-all ${
              isRecording
                ? "bg-rose-600 text-white animate-pulse"
                : "bg-emerald-500/20 border border-emerald-500/50 text-emerald-200 hover:bg-emerald-500/30"
            }`}
          >
            {isRecording ? (
              <>
                <MicOff className="w-3.5 h-3.5" />
                Stop & Transcribe
              </>
            ) : (
              <>
                <Mic className="w-3.5 h-3.5" />
                Record 24kHz WAV
              </>
            )}
          </button>
        </div>

        <textarea
          rows={2}
          value={operatorCommand}
          onChange={(e) => onOperatorCommandChange(e.target.value)}
          className="w-full bg-slate-900 border border-slate-700 rounded-lg p-2 text-xs text-slate-200 font-mono focus:outline-none focus:border-cyan-500"
          placeholder="Operator navigation command..."
        />
        <div className="text-[10px] font-mono text-slate-400 truncate">
          {recordingStatus}
        </div>
      </div>

      {/* Section 5: Primary Action Triggers */}
      <div className="flex flex-col gap-2">
        <button
          onClick={onRunVerifiablePipeline}
          disabled={isBusy}
          className="w-full py-2.5 px-3 rounded-xl bg-gradient-to-r from-cyan-600 to-emerald-600 hover:from-cyan-500 hover:to-emerald-500 text-white font-mono text-xs font-bold uppercase tracking-wider flex items-center justify-center gap-2 shadow-lg transition-all disabled:opacity-50"
        >
          <Play className="w-4 h-4" />
          Run Full Verifiable Pipeline (SAM 3.1 + Tool Loop)
        </button>

        <button
          onClick={onRunRedTeamScenario}
          disabled={isBusy}
          className="w-full py-2 px-3 rounded-xl bg-rose-500/20 hover:bg-rose-500/30 border border-rose-500/50 text-rose-200 font-mono text-xs font-semibold flex items-center justify-center gap-2 transition-all disabled:opacity-50"
        >
          <Flame className="w-4 h-4 text-rose-400" />
          Adversarial Red-Team Hazard (Muse Image 1.0)
        </button>

        <button
          onClick={onExportRos2All}
          className="w-full py-2 px-3 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-200 font-mono text-xs font-semibold flex items-center justify-center gap-2 transition-all"
        >
          <Download className="w-4 h-4 text-cyan-400" />
          Export ROS 2 Nav2 Package (.pgm + .yaml + .json)
        </button>
      </div>
    </div>
  );
};
