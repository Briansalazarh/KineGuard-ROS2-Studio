import React from "react";
import {
  Activity,
  Bot,
  CheckCircle2,
  Cpu,
  Gauge,
  ShieldAlert,
  Wifi,
} from "lucide-react";
import { SystemMetrics } from "../types/telemetry";
import { formatMetric } from "../utils/formatMetric";

interface TelemetryMetricsBarProps {
  metrics: SystemMetrics | null;
  connected: boolean;
  streamMode: string;
}

export const TelemetryMetricsBar: React.FC<TelemetryMetricsBarProps> = ({
  metrics,
  connected,
  streamMode,
}) => {
  const activeNodes = formatMetric(metrics?.activeNodes, 0);
  const closedLoopLatency = formatMetric(metrics?.closedLoopLatencyMs, 1, " ms");
  const kinematicLatency = formatMetric(metrics?.kinematicCheckLatencyMs, 2, " ms");
  const collisionRate = formatMetric(metrics?.collisionRatePct, 2, "%");
  const interceptions = formatMetric(metrics?.guardrailInterceptions, 0);
  const throughputHz = formatMetric(metrics?.telemetryHz, 0, "Hz");

  return (
    <header className="w-full bg-[#070c18] border-b border-slate-800/90 px-4 py-2.5 shadow-neon">
      <div className="max-w-[1800px] mx-auto flex flex-wrap items-center justify-between gap-3">
        {/* Brand Identity & Studio Title */}
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center w-9 h-9 rounded-md bg-cyan-500/15 border border-cyan-400/50 text-cyan-300 shadow-neon">
            <Cpu className="w-5 h-5" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-base font-mono font-bold tracking-wider text-white">
                KineGuard ROS2 Studio
              </h1>
              <span className="px-1.5 py-0.5 text-[10px] font-mono font-semibold rounded bg-cyan-500/15 text-cyan-300 border border-cyan-500/30">
                VERIFIABLE PHYSICAL AI v2.0
              </span>
              <span className="hidden sm:inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-mono rounded bg-emerald-500/15 text-emerald-300 border border-emerald-500/30">
                <Wifi className="w-3 h-3" />
                {connected ? streamMode : "SIM SSE OFFLINE"}
              </span>
            </div>
            <p className="text-[11px] font-mono text-slate-400">
              Metric Homography &amp; Verifiable ROS 2 Nav2 Compiler // Meta Model API (SAM 3.1 • Muse Spark 1.3 • Muse Wonder 1.0 • Muse Image 1.0 • Muse Voice 1.0)
            </p>
          </div>
        </div>

        {/* High-Density Robotics Telemetry Counters */}
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2 flex-1 max-w-4xl">
          {/* Active Nodes (SSE simulation, not a KineGuard scene result) */}
          <div className="px-3 py-1.5 rounded bg-[#0a1222] border border-slate-800/90">
            <div className="flex items-center justify-between text-[10px] font-mono text-slate-400">
              <span>AMR FLEET · SIM SSE</span>
              <Bot className="w-3.5 h-3.5 text-cyan-400" />
            </div>
            <div className="text-sm font-mono font-bold text-cyan-300 mt-0.5">
              {activeNodes} / 6 ONLINE
            </div>
          </div>

          {/* Closed-Loop Latency */}
          <div className="px-3 py-1.5 rounded bg-[#0a1222] border border-slate-800/90">
            <div className="flex items-center justify-between text-[10px] font-mono text-slate-400">
              <span>SIM LOOP LATENCY</span>
              <Gauge className="w-3.5 h-3.5 text-emerald-400" />
            </div>
            <div className="text-sm font-mono font-bold text-emerald-300 mt-0.5">
              {closedLoopLatency}
            </div>
          </div>

          {/* Kinematic Safety Check Latency */}
          <div className="px-3 py-1.5 rounded bg-[#0a1222] border border-slate-800/90">
            <div className="flex items-center justify-between text-[10px] font-mono text-slate-400">
              <span>SIM VERIFIER</span>
              <Activity className="w-3.5 h-3.5 text-purple-400" />
            </div>
            <div className="text-sm font-mono font-bold text-purple-300 mt-0.5">
              {kinematicLatency}
            </div>
          </div>

          {/* Collision Rate */}
          <div className="px-3 py-1.5 rounded bg-[#0a1222] border border-slate-800/90">
            <div className="flex items-center justify-between text-[10px] font-mono text-slate-400">
              <span>SIM COLLISION RATE</span>
              <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
            </div>
            <div className="text-sm font-mono font-bold text-emerald-400 mt-0.5">
              {collisionRate}
            </div>
          </div>

          {/* Guardrail Interceptions */}
          <div className="px-3 py-1.5 rounded bg-[#0a1222] border border-slate-800/90">
            <div className="flex items-center justify-between text-[10px] font-mono text-slate-400">
              <span>SIM INTERCEPTIONS</span>
              <ShieldAlert className="w-3.5 h-3.5 text-amber-400" />
            </div>
            <div className="text-sm font-mono font-bold text-amber-300 mt-0.5">
              {interceptions} COUNTEREXAMPLES
            </div>
          </div>

          {/* Stream Throughput */}
          <div className="px-3 py-1.5 rounded bg-[#0a1222] border border-slate-800/90">
            <div className="flex items-center justify-between text-[10px] font-mono text-slate-400">
              <span>SIM TELEMETRY</span>
              <span className="w-2 h-2 rounded-full bg-cyan-400 animate-ping" />
            </div>
            <div className="text-sm font-mono font-bold text-slate-100 mt-0.5">
              {throughputHz}
            </div>
          </div>
        </div>
      </div>
    </header>
  );
};
