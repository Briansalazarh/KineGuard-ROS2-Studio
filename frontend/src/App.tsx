import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { DualSpatialViewports } from "./components/DualSpatialViewports";
import { MissionControlTerminal } from "./components/MissionControlTerminal";
import { TelemetryMetricsBar } from "./components/TelemetryMetricsBar";
import { VerifiableAgentLoopPanel } from "./components/VerifiableAgentLoopPanel";
import { PanelErrorBoundary } from "./components/PanelErrorBoundary";
import { VoiceChaosPanel } from "./components/VoiceChaosPanel";
import {
  createPipelineExecutionController,
  exportRos2Bundle,
  subscribeToTelemetryStream,
  transcribeVoiceAudio,
} from "./services/api";
import {
  AgentLogEntry,
  ScaleType,
  SpatialCoordinate,
  TelemetryFrame,
  VerifiableSceneResponse,
} from "./types/telemetry";
import {
  REAL_SCENE_PRESETS,
  RealScenePreset,
  renderScenePresetToDataUri,
} from "./utils/realScenePresets";

export const App: React.FC = () => {
  const [frame, setFrame] = useState<TelemetryFrame | null>(null);
  const [connected, setConnected] = useState<boolean>(false);
  const [streamMode, setStreamMode] = useState<string>("INITIALIZING");

  const [activePreset, setActivePreset] = useState<RealScenePreset>(
    REAL_SCENE_PRESETS[0]
  );
  const [imageSource, setImageSource] = useState<"PRESET_SCENE" | "USER_UPLOAD">(
    "PRESET_SCENE"
  );
  const [sceneDataUri, setSceneDataUri] = useState<string>("");
  const [calibrationPoints, setCalibrationPoints] = useState<SpatialCoordinate[]>(
    REAL_SCENE_PRESETS[0].calibrationPoints
  );
  const [conceptPrompt, setConceptPrompt] = useState<string>(
    REAL_SCENE_PRESETS[0].conceptPrompt
  );
  const [operatorCommand, setOperatorCommand] = useState<string>(
    REAL_SCENE_PRESETS[0].defaultCommand
  );
  const [reasoningModel, setReasoningModel] = useState<string>("muse-spark-1.3");
  const [velocityMs, setVelocityMs] = useState<number>(
    REAL_SCENE_PRESETS[0].defaultVelocityMs
  );
  const [frictionMu, setFrictionMu] = useState<number>(
    REAL_SCENE_PRESETS[0].defaultFrictionMu
  );
  const [corridorWidthMeters, setCorridorWidthMeters] = useState<number>(
    REAL_SCENE_PRESETS[0].corridorWidthMeters
  );
  const [corridorLengthMeters, setCorridorLengthMeters] = useState<number>(
    REAL_SCENE_PRESETS[0].corridorLengthMeters
  );
  const [scaleType, setScaleType] = useState<ScaleType>("NOMINAL_PRESET");

  const [verifiableResult, setVerifiableResult] =
    useState<VerifiableSceneResponse | null>(null);
  const [isBusy, setIsBusy] = useState<boolean>(false);
  const [activeExecutionId, setActiveExecutionId] = useState<string | null>(null);
  const [localPipelineLogs, setLocalPipelineLogs] = useState<AgentLogEntry[]>([]);

  const pipelineControllerRef = useRef(createPipelineExecutionController());

  // Subscribe to 25Hz SSE telemetry stream
  useEffect(() => {
    const unsubscribe = subscribeToTelemetryStream(
      (incomingFrame) => {
        setFrame(incomingFrame);
      },
      (isOnline, mode) => {
        setConnected(isOnline);
        setStreamMode(mode);
      }
    );
    return () => {
      unsubscribe();
      pipelineControllerRef.current.abortActive();
    };
  }, []);

  const executePipelineForState = useCallback(
    async (
      preset: RealScenePreset,
      imgSource: "PRESET_SCENE" | "USER_UPLOAD",
      imgUri: string,
      calPts: SpatialCoordinate[],
      concept: string,
      cmd: string,
      model: string,
      vel: number,
      mu: number,
      redTeam: boolean,
      widthM: number,
      lengthM: number,
      scale: ScaleType
    ) => {
      // Phase 2 Req 1: Block concurrent activations while an execution is already in-flight
      if (pipelineControllerRef.current.getState().inFlight) {
        return;
      }

      // P0-B Integrity Rule: User-uploaded photos NEVER inherit presetObstacles from preloaded scenes
      const isUserUpload = imgSource === "USER_UPLOAD";
      const req = {
        sceneId: isUserUpload ? "USER_CUSTOM_SCENE" : preset.id,
        sceneTitle: isUserUpload ? "Operator Uploaded Corridor Photo" : preset.title,
        imageSource: imgSource,
        conceptPrompt: concept,
        imageBase64: imgUri,
        imageWidth: 860,
        imageHeight: 640,
        calibrationPoints: calPts,
        corridorWidthMeters: widthM,
        corridorLengthMeters: lengthM,
        scaleType: scale,
        velocityMs: vel,
        frictionMu: mu,
        voiceCommandHint: cmd,
        reasoningModel: model,
        redTeamPrompt: preset.redTeamPrompt,
        presetObstacles: isUserUpload ? [] : preset.presetObstacles,
      };

      await pipelineControllerRef.current.execute(req, {
        redTeam,
        allowPreempt: false,
        onStart: (execId) => {
          setIsBusy(true);
          setActiveExecutionId(execId);
          // Clear previous result so stale metrics from an earlier run are never displayed while RUNNING
          setVerifiableResult(null);
        },
        onComplete: (res) => {
          setVerifiableResult(res);
          setActiveExecutionId(res.executionId);
          setIsBusy(false);
          if (res.pipelineLogs && res.pipelineLogs.length > 0) {
            setLocalPipelineLogs((prev) => [...prev.slice(-30), ...res.pipelineLogs]);
          }
        },
      });
    },
    []
  );

  // Phase 2 Req 1: Initial load starts in IDLE state with preset 0 selected and rendered to canvas DataURI.
  // Never automatically executes the expensive Meta API pipeline on mount (preserving React.StrictMode safety).
  useEffect(() => {
    const initialPreset = REAL_SCENE_PRESETS[0];
    const uri = renderScenePresetToDataUri(initialPreset, false);
    setSceneDataUri(uri);
    setImageSource("PRESET_SCENE");
  }, []);

  const handleSelectPreset = (preset: RealScenePreset) => {
    if (isBusy) return;
    setActivePreset(preset);
    setImageSource("PRESET_SCENE");
    setCalibrationPoints(preset.calibrationPoints);
    setConceptPrompt(preset.conceptPrompt);
    setOperatorCommand(preset.defaultCommand);
    setVelocityMs(preset.defaultVelocityMs);
    setFrictionMu(preset.defaultFrictionMu);
    setCorridorWidthMeters(preset.corridorWidthMeters);
    setCorridorLengthMeters(preset.corridorLengthMeters);
    setScaleType("NOMINAL_PRESET");

    const uri = renderScenePresetToDataUri(preset, false);
    setSceneDataUri(uri);
    // Reset to IDLE for the newly selected preset; execution starts only via explicit user action
    setVerifiableResult(null);
  };

  const handleCustomImageUpload = (dataUri: string) => {
    if (isBusy) return;
    setImageSource("USER_UPLOAD");
    setSceneDataUri(dataUri);
    // A photo has no measured scale until the operator supplies corridor dimensions.
    setScaleType("UNKNOWN");
    // Reset to IDLE for the newly uploaded photo so the operator can calibrate or run explicitly
    setVerifiableResult(null);
  };

  const handleRunVerifiablePipeline = () => {
    if (isBusy) return;
    const uri = sceneDataUri || renderScenePresetToDataUri(activePreset, false);
    executePipelineForState(
      activePreset,
      imageSource,
      uri,
      calibrationPoints,
      conceptPrompt,
      operatorCommand,
      reasoningModel,
      velocityMs,
      frictionMu,
      false,
      corridorWidthMeters,
      corridorLengthMeters,
      scaleType
    );
  };

  const handleRunRedTeamScenario = () => {
    if (isBusy) return;
    const redTeamUri =
      imageSource === "USER_UPLOAD" && sceneDataUri
        ? sceneDataUri
        : renderScenePresetToDataUri(activePreset, true);
    setSceneDataUri(redTeamUri);
    executePipelineForState(
      activePreset,
      imageSource,
      redTeamUri,
      calibrationPoints,
      conceptPrompt,
      operatorCommand,
      reasoningModel,
      velocityMs,
      frictionMu,
      true,
      corridorWidthMeters,
      corridorLengthMeters,
      scaleType
    );
  };

  const handleTranscribeVoice = async (wavBlob: Blob | null, hint: string) => {
    if (isBusy || pipelineControllerRef.current.getState().inFlight) return;
    const voiceRes = await transcribeVoiceAudio(wavBlob, hint);
    const nextCmd = voiceRes.transcript || hint;
    setOperatorCommand(nextCmd);
    const uri = sceneDataUri || renderScenePresetToDataUri(activePreset, false);
    await executePipelineForState(
      activePreset,
      imageSource,
      uri,
      calibrationPoints,
      conceptPrompt,
      nextCmd,
      reasoningModel,
      velocityMs,
      frictionMu,
      false,
      corridorWidthMeters,
      corridorLengthMeters,
      scaleType
    );
  };

  const downloadTextFile = (filename: string, content: string) => {
    const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const handleExportRos2All = async () => {
    const bundle =
      verifiableResult?.ros2Bundle ||
      (await exportRos2Bundle(activeExecutionId ?? undefined));
    if (!bundle) return;
    downloadTextFile("map.yaml", bundle.mapYaml);
    downloadTextFile("map.pgm", bundle.mapPgmAscii);
    downloadTextFile("nav2_verified_waypoints.json", bundle.nav2WaypointsJson);
  };

  // Phase 2 Req 5: Merge SSE telemetry logs with localPipelineLogs so HTTP/Meta API error logs are never hidden.
  // SSE frames broadcast every execution's logs: never attribute another execution's results here.
  const combinedLogs = useMemo(() => {
    const belongsToActive = (entry: AgentLogEntry) =>
      !activeExecutionId ||
      !entry.executionId ||
      entry.executionId === activeExecutionId ||
      entry.executionId.startsWith("SYS-");
    const sseLogs = (frame?.recentLogs ?? []).filter(belongsToActive);
    const ownLogs = localPipelineLogs.filter(belongsToActive);
    if (sseLogs.length === 0) return ownLogs;
    if (ownLogs.length === 0) return sseLogs;
    const byKey = new Map<string, AgentLogEntry>();
    for (const entry of [...sseLogs, ...ownLogs]) {
      const key = `${entry.executionId || "sys"}:${entry.tag}:${entry.message}`;
      byKey.set(key, entry);
    }
    return Array.from(byKey.values())
      .sort((a, b) => a.timestamp - b.timestamp)
      .slice(-45);
  }, [frame?.recentLogs, localPipelineLogs, activeExecutionId]);

  return (
    <div className="min-h-screen flex flex-col bg-[#05080f] text-slate-100">
      {/* Top KineGuard ROS2 Studio Telemetry Bar */}
      <TelemetryMetricsBar
        metrics={frame?.metrics ?? null}
        connected={connected}
        streamMode={streamMode}
      />

      {/* Main High-Density Studio Workspace */}
      <main className="flex-1 max-w-[1800px] w-full mx-auto p-3.5 grid grid-cols-1 xl:grid-cols-12 gap-3.5 items-start">
        {/* Left/Center Column (8 cols): Synchronized Dual Spatial Viewports + Verifiable Agent Tool Loop Inspector */}
        <section className="xl:col-span-8 flex flex-col gap-3.5">
          <PanelErrorBoundary panelName="Spatial viewports" resetKey={activeExecutionId}>
            <DualSpatialViewports
              presets={REAL_SCENE_PRESETS}
              activePreset={activePreset}
              onSelectPreset={handleSelectPreset}
              sceneDataUri={sceneDataUri}
              onCustomImageUpload={handleCustomImageUpload}
              calibrationPoints={calibrationPoints}
              onCalibrationChange={setCalibrationPoints}
              verifiableResult={verifiableResult}
              velocityMs={velocityMs}
              frictionMu={frictionMu}
              corridorWidthMeters={corridorWidthMeters}
              corridorLengthMeters={corridorLengthMeters}
              imageSource={imageSource}
              isAnalyzing={isBusy}
              activeExecutionId={activeExecutionId}
            />
          </PanelErrorBoundary>

          <PanelErrorBoundary panelName="Verifiable agent loop" resetKey={activeExecutionId}>
            <VerifiableAgentLoopPanel
              verifiableResult={verifiableResult}
              isAnalyzing={isBusy}
              activeExecutionId={activeExecutionId}
              onDownloadRos2File={downloadTextFile}
            />
          </PanelErrorBoundary>
        </section>

        {/* Right Column (4 cols): Physical Kinematics, Muse Voice 24kHz Recorder, Red-Team & Live Terminal */}
        <aside className="xl:col-span-4 flex flex-col gap-3.5">
          <VoiceChaosPanel
            conceptPrompt={conceptPrompt}
            onConceptPromptChange={setConceptPrompt}
            operatorCommand={operatorCommand}
            onOperatorCommandChange={setOperatorCommand}
            reasoningModel={reasoningModel}
            onReasoningModelChange={setReasoningModel}
            velocityMs={velocityMs}
            onVelocityChange={setVelocityMs}
            frictionMu={frictionMu}
            onFrictionChange={setFrictionMu}
            corridorWidthMeters={corridorWidthMeters}
            corridorLengthMeters={corridorLengthMeters}
            onCorridorWidthChange={setCorridorWidthMeters}
            onCorridorLengthChange={setCorridorLengthMeters}
            scaleType={scaleType}
            onScaleTypeChange={setScaleType}
            imageSource={imageSource}
            executionMode={verifiableResult?.executionMode}
            onRunVerifiablePipeline={handleRunVerifiablePipeline}
            onRunRedTeamScenario={handleRunRedTeamScenario}
            onTranscribeVoice={handleTranscribeVoice}
            onExportRos2All={handleExportRos2All}
            isBusy={isBusy}
          />

          <MissionControlTerminal
            logs={combinedLogs}
            activeExecutionId={activeExecutionId}
          />
        </aside>
      </main>
    </div>
  );
};

export default App;
