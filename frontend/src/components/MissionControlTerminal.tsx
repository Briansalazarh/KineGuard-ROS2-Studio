import React, { useEffect, useRef, useState } from "react";
import { formatMetric } from "../utils/formatMetric";
import { Cpu, ShieldCheck, Terminal } from "lucide-react";
import { AgentLogEntry } from "../types/telemetry";

interface MissionControlTerminalProps {
  logs: AgentLogEntry[];
  activeExecutionId?: string | null;
}

const TAG_BADGE_STYLES: Record<string, string> = {
  HOMOGRAPHY: "bg-cyan-500/15 text-cyan-300 border-cyan-500/40",
  MUSE_VOICE: "bg-emerald-500/15 text-emerald-300 border-emerald-500/40",
  MUSE_IMAGE: "bg-amber-500/15 text-amber-300 border-amber-500/40",
  "SAM 3.1": "bg-purple-500/15 text-purple-300 border-purple-500/40",
  MUSE_WONDER: "bg-blue-500/15 text-blue-300 border-blue-500/40",
  KINEMATIC_GUARD: "bg-rose-500/20 text-rose-300 border-rose-500/60 font-bold",
  ROS2_EXPORT: "bg-teal-500/15 text-teal-300 border-teal-500/40",
  STATUS: "bg-emerald-500/15 text-emerald-300 border-emerald-500/40",
};

export const MissionControlTerminal: React.FC<MissionControlTerminalProps> = ({
  logs,
  activeExecutionId = null,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  useEffect(() => {
    if (autoScroll && scrollContainerRef.current) {
      scrollContainerRef.current.scrollTop = scrollContainerRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  const formatTimestamp = (epochMs: number) => {
    const d = new Date(epochMs);
    const hh = String(d.getHours()).padStart(2, "0");
    const mm = String(d.getMinutes()).padStart(2, "0");
    const ss = String(d.getSeconds()).padStart(2, "0");
    const ms = String(d.getMilliseconds()).padStart(3, "0");
    return `${hh}:${mm}:${ss}.${ms}`;
  };

  return (
    <div className="flex flex-col h-[310px] max-h-[310px] bg-[#070c17] border border-slate-800/90 rounded-xl overflow-hidden shadow-neon">
      {/* Terminal Top Header */}
      <div className="flex items-center justify-between px-3.5 py-2 bg-[#0a1120] border-b border-slate-800/80 shrink-0">
        <div className="flex items-center gap-2">
          <Terminal className="w-4 h-4 text-emerald-400" />
          <span className="text-xs font-mono font-bold tracking-wider text-slate-100">
            KINEGUARD ROS2 STUDIO // VERIFIABLE PIPELINE STREAM
          </span>
        </div>

        <div className="flex items-center gap-2">
          {activeExecutionId && (
            <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-slate-900 border border-cyan-500/40 text-cyan-300">
              EXEC: {activeExecutionId}
            </span>
          )}
          <button
            type="button"
            onClick={() => setAutoScroll((v) => !v)}
            className={`px-2 py-0.5 rounded text-[10px] font-mono border transition ${
              autoScroll
                ? "bg-emerald-500/15 border-emerald-500/40 text-emerald-300"
                : "bg-slate-900 border-slate-700 text-slate-400"
            }`}
          >
            {autoScroll ? "AUTO-SCROLL ON" : "PAUSED"}
          </button>
          <span className="flex items-center gap-1 text-[10px] font-mono text-cyan-300 px-2 py-0.5 rounded bg-cyan-500/10 border border-cyan-500/30">
            <Cpu className="w-3 h-3" />
            FORMAL VERIFIER
          </span>
        </div>
      </div>

      {/* Streaming Log Console */}
      <div
        ref={scrollContainerRef}
        className="flex-1 max-h-[310px] p-3 overflow-y-auto space-y-1.5 font-mono text-xs bg-[#04070d]"
      >
        {logs.map((entry) => {
          const badgeStyle =
            TAG_BADGE_STYLES[entry.tag] ||
            "bg-slate-800 text-slate-300 border-slate-700";
          const isIntercept =
            entry.tag === "KINEMATIC_GUARD" && entry.level === "INTERCEPT";
          const isError =
            entry.level === "ERROR" || entry.level === "CRITICAL";

          return (
            <div
              key={`${entry.id}-${entry.executionId || "sys"}`}
              className={`flex items-start gap-2 px-2.5 py-1.5 rounded border transition ${
                isError
                  ? "bg-rose-950/50 border-rose-500/70 text-rose-100"
                  : isIntercept
                  ? "bg-rose-950/35 border-rose-500/50 text-rose-100"
                  : "bg-[#080e1a]/90 border-slate-800/60 text-slate-200"
              }`}
            >
              <span className="text-[10px] text-slate-500 shrink-0 mt-0.5">
                {formatTimestamp(entry.timestamp)}
              </span>

              {entry.executionId && (
                <span className="px-1.5 py-0.2 text-[10px] rounded border shrink-0 bg-slate-900/90 border-slate-700 text-cyan-300">
                  [{entry.executionId}]
                </span>
              )}

              <span
                className={`px-1.5 py-0.2 text-[10px] rounded border shrink-0 ${badgeStyle}`}
              >
                [{entry.tag}]:
              </span>

              <span className="flex-1 leading-relaxed break-words">
                {entry.message}
              </span>

              {entry.latencyMs > 0 && (
                <span className="text-[10px] text-slate-400 shrink-0 bg-slate-900/90 px-1.5 py-0.5 rounded border border-slate-800">
                  {formatMetric(entry.latencyMs, 2, "ms")}
                </span>
              )}
            </div>
          );
        })}
      </div>

      {/* Bottom Invariant Verification Footer */}
      <div className="flex items-center justify-between px-3.5 py-1.5 bg-[#080d19] border-t border-slate-800/80 text-[11px] font-mono text-slate-400">
        <span className="flex items-center gap-1.5 text-emerald-400">
          <ShieldCheck className="w-3.5 h-3.5" />
          HOMOGRAPHY → SAM_3.1 → MUSE_SPARK → VERIFY_KINEMATIC_SAFETY → ROS2_NAV2
        </span>
        <span>KERNEL: AUTORIDAD FINAL</span>
      </div>
    </div>
  );
};
