import React, { useState } from "react";
import { formatMetric } from "../utils/formatMetric";
import {
  CheckCircle2,
  Code2,
  Cpu,
  Download,
  FileCode,
  ShieldAlert,
  ShieldCheck,
  Terminal,
} from "lucide-react";
import {
  AgentVerificationTurn,
  Ros2ExportBundle,
  VerifiableSceneResponse,
} from "../types/telemetry";

interface VerifiableAgentLoopPanelProps {
  verifiableResult: VerifiableSceneResponse | null;
  isAnalyzing?: boolean;
  activeExecutionId?: string | null;
  onDownloadRos2File: (filename: string, content: string) => void;
}

export const VerifiableAgentLoopPanel: React.FC<VerifiableAgentLoopPanelProps> = ({
  verifiableResult,
  isAnalyzing = false,
  activeExecutionId = null,
  onDownloadRos2File,
}) => {
  const [selectedTab, setSelectedTab] = useState<"turns" | "ros2" | "sam">("turns");

  const turns: AgentVerificationTurn[] = verifiableResult?.agentTurns || [];
  const ros2: Ros2ExportBundle | undefined = verifiableResult?.ros2Bundle;
  const verificationStatus = isAnalyzing
    ? "RUNNING"
    : !verifiableResult
    ? "IDLE"
    : verifiableResult.verificationStatus ||
      ros2?.verificationStatus ||
      "UNVERIFIED";
  const perceptionStatus = isAnalyzing
    ? "PENDING"
    : !verifiableResult
    ? "IDLE"
    : verifiableResult.perceptionStatus || "UNVERIFIED";
  const imageSource = verifiableResult?.imageSource || "PRESET_SCENE";
  const obstacleSource = verifiableResult?.obstacleSource || "NONE";
  const waypointSource = verifiableResult?.waypointSource || "NONE";
  const displayExecId = verifiableResult?.executionId || activeExecutionId;

  return (
    <div className="bg-slate-900/95 border border-slate-800 rounded-xl overflow-hidden flex flex-col">
      {/* Header & Tabs */}
      <div className="px-4 py-2.5 border-b border-slate-800 bg-slate-950/70 flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Cpu className="w-4 h-4 text-cyan-400" />
          <span className="text-xs font-mono font-bold uppercase tracking-wider text-slate-100">
            VERIFIABLE AGENT TOOL-CALLING LOOP (verify_kinematic_safety)
          </span>
          {displayExecId && (
            <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-slate-900 border border-cyan-500/40 text-cyan-300">
              ID: {displayExecId}
            </span>
          )}
          <span
            className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold uppercase border ${
              verificationStatus === "APPROVED"
                ? "bg-emerald-500/20 border-emerald-500/50 text-emerald-300"
                : verificationStatus === "REJECTED"
                ? "bg-rose-500/20 border-rose-500/50 text-rose-300"
                : verificationStatus === "RUNNING"
                ? "bg-cyan-500/20 border-cyan-500/50 text-cyan-300"
                : verificationStatus === "IDLE"
                ? "bg-slate-800 border-slate-700 text-slate-300"
                : "bg-amber-500/20 border-amber-500/50 text-amber-300"
            }`}
          >
            STATUS: {verificationStatus}
          </span>
          <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-slate-800 border border-slate-700 text-slate-300">
            PERCEPTION: {perceptionStatus} ({obstacleSource})
          </span>
          <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-slate-800 border border-slate-700 text-cyan-300">
            SOURCE: {imageSource} / {waypointSource}
          </span>
          {verifiableResult?.scaleType && (
            <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-slate-800 border border-slate-700 text-amber-300">
              SCALE: {verifiableResult.scaleType}
            </span>
          )}
        </div>

        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setSelectedTab("turns")}
            className={`px-2.5 py-1 rounded text-xs font-mono transition-all ${
              selectedTab === "turns"
                ? "bg-cyan-500/20 border border-cyan-400/60 text-cyan-200"
                : "bg-slate-800/80 text-slate-400 hover:text-slate-200"
            }`}
          >
            Multi-Turn Tool Loop ({turns.length} Turns)
          </button>
          <button
            onClick={() => setSelectedTab("ros2")}
            className={`px-2.5 py-1 rounded text-xs font-mono transition-all ${
              selectedTab === "ros2"
                ? "bg-emerald-500/20 border border-emerald-400/60 text-emerald-200"
                : "bg-slate-800/80 text-slate-400 hover:text-slate-200"
            }`}
          >
            ROS 2 Nav2 Package
          </button>
          <button
            onClick={() => setSelectedTab("sam")}
            className={`px-2.5 py-1 rounded text-xs font-mono transition-all ${
              selectedTab === "sam"
                ? "bg-purple-500/20 border border-purple-400/60 text-purple-200"
                : "bg-slate-800/80 text-slate-400 hover:text-slate-200"
            }`}
          >
            SAM 3.1 Wire Tokens
          </button>
        </div>
      </div>

      {/* Tab Content */}
      <div className="p-3.5">
        {selectedTab === "turns" && (
          <>
            {isAnalyzing ? (
              <div className="rounded-xl border border-cyan-500/40 bg-cyan-950/20 p-4 font-mono text-xs text-cyan-200 flex items-center justify-between gap-3">
                <div className="flex items-center gap-2.5">
                  <div className="w-4 h-4 border-2 border-cyan-400 border-t-transparent rounded-full animate-spin shrink-0" />
                  <div>
                    <div className="font-bold uppercase">
                      PIPELINE RUNNING — EXECUTION ID: {displayExecId || "PENDING"}
                    </div>
                    <div className="text-cyan-300/90 mt-0.5">
                      Executing SAM 3.1 segmentation (/v1/responses), Muse Spark grounding (/v1/chat/completions), and multi-turn verify_kinematic_safety tool loop...
                    </div>
                  </div>
                </div>
                <span className="px-2.5 py-1 rounded bg-cyan-500/20 border border-cyan-500/40 text-cyan-200 text-[11px] shrink-0">
                  RUNNING / PENDING
                </span>
              </div>
            ) : !verifiableResult ? (
              <div className="rounded-xl border border-slate-700 bg-slate-950/60 p-4 font-mono text-xs text-slate-300 flex items-center justify-between gap-3">
                <div className="flex items-center gap-2.5">
                  <Cpu className="w-5 h-5 text-cyan-400 shrink-0" />
                  <div>
                    <div className="font-bold uppercase text-slate-100">
                      PIPELINE IDLE — PRESET SELECTED & CALIBRATED
                    </div>
                    <div className="text-slate-400 mt-0.5">
                      Click &quot;Run Verifiable Pipeline&quot; or &quot;Red-Team Inject&quot; to start SAM 3.1 perception and multi-turn KinematicSafetyKernel verification.
                    </div>
                  </div>
                </div>
                <span className="px-2.5 py-1 rounded bg-slate-800 border border-slate-700 text-slate-300 text-[11px] shrink-0">
                  IDLE
                </span>
              </div>
            ) : turns.length === 0 ? (
              <div className="rounded-xl border border-amber-500/40 bg-amber-950/20 p-4 font-mono text-xs text-amber-200 flex items-center justify-between gap-3">
                <div className="flex items-center gap-2.5">
                  <ShieldAlert className="w-5 h-5 text-amber-400 shrink-0" />
                  <div>
                    <div className="font-bold uppercase">
                      VERIFICATION HALTED — STATUS: {verificationStatus} (PERCEPTION: {perceptionStatus})
                    </div>
                    <div className="text-amber-300/90 mt-0.5">
                      No trajectory was approved and zero ROS 2 Nav2 poses were exported. Ensure valid obstacle detections or backend connectivity before executing autonomous navigation.
                    </div>
                  </div>
                </div>
                <span className="px-2.5 py-1 rounded bg-amber-500/20 border border-amber-500/40 text-amber-200 text-[11px] shrink-0">
                  0 Poses Approved
                </span>
              </div>
            ) : (
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
                {turns.map((turn) => {
                  const approved = turn.kernelVerdict?.approved;
                  return (
                    <div
                      key={turn.turnNumber}
                      className={`rounded-xl border p-3.5 flex flex-col gap-2.5 ${
                        approved
                          ? "bg-emerald-950/20 border-emerald-500/40"
                          : "bg-rose-950/20 border-rose-500/40"
                      }`}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          {approved ? (
                            <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                          ) : (
                            <ShieldAlert className="w-4 h-4 text-rose-400 shrink-0" />
                          )}
                          <span className="text-xs font-mono font-bold uppercase text-slate-100">
                            TURN {turn.turnNumber}: {turn.modelUsed} → {turn.toolName}()
                          </span>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-slate-900 border border-slate-700 text-cyan-300">
                            {turn.waypointSource || "UNKNOWN"}
                          </span>
                          <span
                            className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold uppercase ${
                              approved
                                ? "bg-emerald-500/20 text-emerald-300 border border-emerald-500/40"
                                : "bg-rose-500/20 text-rose-300 border border-rose-500/40"
                            }`}
                          >
                            {approved ? "✅ APPROVED" : "❌ REJECTED"}
                          </span>
                        </div>
                      </div>

                      <p className="text-xs text-slate-300 leading-relaxed">
                        {turn.reasoning}
                      </p>

                      {/* Proposed Metric Waypoints */}
                      <div className="bg-slate-950/80 border border-slate-800 rounded-lg p-2 font-mono text-[11px] text-slate-300">
                        <div className="text-[10px] text-slate-400 mb-1 flex items-center justify-between">
                          <span>tool_call_id: {turn.toolCallId}</span>
                          <span>Latency: {formatMetric(turn.latencyMs, 2, " ms")}</span>
                        </div>
                        <div className="flex flex-wrap gap-1.5">
                          {turn.proposedWaypoints.map((wp) => (
                            <span
                              key={wp.id}
                              className="px-2 py-0.5 rounded bg-slate-900 border border-slate-700 text-cyan-300"
                            >
                              {wp.id}: ({formatMetric(wp.xMeters, 2, "m")}, {formatMetric(wp.yMeters, 2, "m")}) @{" "}
                              {formatMetric(wp.targetSpeedMs, 1, "m/s")}
                            </span>
                          ))}
                        </div>
                      </div>

                      {/* Formal Kinematic Safety Kernel Verdict / Counterexample */}
                      <div
                        className={`rounded-lg p-2.5 font-mono text-[11px] border ${
                          approved
                            ? "bg-emerald-950/40 border-emerald-700/50 text-emerald-200"
                            : "bg-rose-950/40 border-rose-700/50 text-rose-200"
                        }`}
                      >
                        <div className="font-bold mb-1 flex items-center justify-between">
                          <span>
                            role="tool" → KinematicSafetyKernel Verdict:
                          </span>
                          <span>
                            minClearance={formatMetric(turn.minClearanceMeters, 2, "m")} | lateralReq=
                            {formatMetric(turn.kernelVerdict?.lateralClearanceRequiredMeters, 2, "m")} | longitudinalReq=
                            {formatMetric(turn.kernelVerdict?.requiredClearanceMeters, 2, "m")}
                          </span>
                        </div>
                        <div className="text-[11px] leading-snug opacity-95">
                          {turn.counterexampleMessage}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}

        {selectedTab === "ros2" && ros2 && (
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-3">
            {/* map.yaml */}
            <div className="bg-slate-950/80 border border-slate-800 rounded-xl p-3 flex flex-col justify-between gap-2">
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-mono font-bold text-emerald-300 flex items-center gap-1.5">
                    <FileCode className="w-3.5 h-3.5" />
                    map.yaml (Nav2 Map Server)
                  </span>
                  <button
                    onClick={() => onDownloadRos2File("map.yaml", ros2.mapYaml)}
                    className="px-2 py-1 rounded bg-emerald-500/20 border border-emerald-500/40 text-emerald-200 text-[11px] font-mono flex items-center gap-1 hover:bg-emerald-500/30"
                  >
                    <Download className="w-3 h-3" />
                    Download
                  </button>
                </div>
                <pre className="text-[11px] font-mono text-slate-300 bg-slate-900/90 p-2.5 rounded-lg overflow-x-auto max-h-40">
                  {ros2.mapYaml}
                </pre>
              </div>
            </div>

            {/* map.pgm */}
            <div className="bg-slate-950/80 border border-slate-800 rounded-xl p-3 flex flex-col justify-between gap-2">
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-mono font-bold text-cyan-300 flex items-center gap-1.5">
                    <Code2 className="w-3.5 h-3.5" />
                    map.pgm ({ros2.gridWidthCells}×{ros2.gridHeightCells} Occupancy Grid)
                  </span>
                  <button
                    onClick={() => onDownloadRos2File("map.pgm", ros2.mapPgmAscii)}
                    className="px-2 py-1 rounded bg-cyan-500/20 border border-cyan-500/40 text-cyan-200 text-[11px] font-mono flex items-center gap-1 hover:bg-cyan-500/30"
                  >
                    <Download className="w-3 h-3" />
                    Download
                  </button>
                </div>
                <pre className="text-[11px] font-mono text-slate-300 bg-slate-900/90 p-2.5 rounded-lg overflow-x-auto max-h-40">
                  {ros2.mapPgmAscii.slice(0, 420)}...
                </pre>
              </div>
            </div>

            {/* nav2_verified_waypoints.json */}
            <div className="bg-slate-950/80 border border-slate-800 rounded-xl p-3 flex flex-col justify-between gap-2">
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-mono font-bold text-purple-300 flex items-center gap-1.5">
                    <ShieldCheck className="w-3.5 h-3.5" />
                    nav2_verified_waypoints.json
                  </span>
                  <button
                    onClick={() =>
                      onDownloadRos2File(
                        "nav2_verified_waypoints.json",
                        ros2.nav2WaypointsJson
                      )
                    }
                    className="px-2 py-1 rounded bg-purple-500/20 border border-purple-500/40 text-purple-200 text-[11px] font-mono flex items-center gap-1 hover:bg-purple-500/30"
                  >
                    <Download className="w-3 h-3" />
                    Download
                  </button>
                </div>
                <pre className="text-[11px] font-mono text-slate-300 bg-slate-900/90 p-2.5 rounded-lg overflow-x-auto max-h-40">
                  {ros2.nav2WaypointsJson}
                </pre>
              </div>
            </div>
          </div>
        )}

        {selectedTab === "sam" && (
          <div className="flex flex-col gap-2">
            <div className="text-xs text-slate-400 font-mono flex items-center gap-2">
              <Terminal className="w-3.5 h-3.5 text-purple-400" />
              Official SAM 3.1 (/v1/responses) Wire Tokens & Metric Homography Footprints:
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5">
              {(verifiableResult?.obstacles || []).map((obs) => (
                <div
                  key={obs.id}
                  className="bg-slate-950/90 border border-slate-800 rounded-lg p-2.5 font-mono text-xs"
                >
                  <div className="flex items-center justify-between text-purple-300 font-bold mb-1">
                    <span>
                      {obs.id.toUpperCase()}: {obs.label}
                    </span>
                    <span>
                      Center: ({formatMetric(obs.centerXMeters, 2, "m")},{" "}
                      {formatMetric(obs.centerYMeters, 2, "m")})
                    </span>
                  </div>
                  <div className="text-[11px] text-slate-400 break-all bg-slate-900 p-2 rounded border border-slate-800">
                    {obs.samMaskToken}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

