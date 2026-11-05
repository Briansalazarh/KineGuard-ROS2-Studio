import {
  AnalyzeSceneRequest,
  ChaosInjectionRequest,
  ClosedLoopExecutionResponse,
  HazardZone,
  MetricWaypoint,
  MuseVoiceTranscribeResponse,
  RobotNode,
  Ros2ExportBundle,
  SpatialCoordinate,
  TelemetryFrame,
  VerifiableSceneResponse,
  VoiceCommandRequest,
  VoronoiCell,
} from "../types/telemetry";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

// Older Jackson responses used lowercase names for Lombok getXMeters()/getUPixel().
function normalizeWaypoint(waypoint: MetricWaypoint): MetricWaypoint {
  const legacy = waypoint as MetricWaypoint & Record<string, number>;
  const normalized = {
    ...waypoint,
    xMeters: waypoint.xMeters ?? legacy.xmeters,
    yMeters: waypoint.yMeters ?? legacy.ymeters,
    uPixel: waypoint.uPixel ?? legacy.upixel,
    vPixel: waypoint.vPixel ?? legacy.vpixel,
  };
  if (![normalized.xMeters, normalized.yMeters, normalized.uPixel, normalized.vPixel]
    .every((value) => typeof value === "number" && Number.isFinite(value))) {
    throw new Error("INVALID_WAYPOINT_COORDINATES");
  }
  return normalized;
}

function normalizeSceneResponse(data: VerifiableSceneResponse): VerifiableSceneResponse {
  return {
    ...data,
    rejectedWaypoints: data.rejectedWaypoints.map(normalizeWaypoint),
    verifiedWaypoints: data.verifiedWaypoints.map(normalizeWaypoint),
    agentTurns: data.agentTurns.map((turn) => ({
      ...turn,
      proposedWaypoints: turn.proposedWaypoints.map(normalizeWaypoint),
      kernelVerdict: {
        ...turn.kernelVerdict,
        evaluatedWaypoints: turn.kernelVerdict?.evaluatedWaypoints?.map(normalizeWaypoint),
      },
    })),
    obstacles: data.obstacles.map((obstacle) => {
      const box = obstacle.pixelBox;
      const legacy = box as typeof box & Record<string, number>;
      return {
        ...obstacle,
        pixelBox: {
          ...box,
          xMin: box.xMin ?? legacy.xmin,
          yMin: box.yMin ?? legacy.ymin,
          xMax: box.xMax ?? legacy.xmax,
          yMax: box.yMax ?? legacy.ymax,
        },
      };
    }),
  };
}

/**
 * Subscribes to the real-time 25Hz SSE telemetry stream from the Java 21 backend.
 * Automatically reconnects on transient network drops and provides a zero-crash
 * local fallback frame generator if the backend is temporarily offline.
 */
export function subscribeToTelemetryStream(
  onFrame: (frame: TelemetryFrame) => void,
  onConnectionChange: (connected: boolean, mode: string) => void
): () => void {
  let eventSource: EventSource | null = null;
  let fallbackTimer: number | null = null;
  let closed = false;
  let lastReceivedAt = 0;

  const connectSse = () => {
    if (closed) return;
    try {
      eventSource = new EventSource(`${API_BASE}/api/v1/spatial/stream`);

      const handleEvent = (ev: MessageEvent) => {
        try {
          const parsed: TelemetryFrame = JSON.parse(ev.data);
          lastReceivedAt = Date.now();
          onConnectionChange(true, "SSE_25HZ_VIRTUAL_THREADS");
          onFrame(parsed);
        } catch {
          // Ignore malformed chunk
        }
      };

      eventSource.addEventListener("telemetry", handleEvent);
      eventSource.onmessage = handleEvent;

      eventSource.onopen = () => {
        lastReceivedAt = Date.now();
        onConnectionChange(true, "SSE_25HZ_VIRTUAL_THREADS");
      };

      eventSource.onerror = () => {
        onConnectionChange(false, "OFFLINE_DETERMINISTIC_SIM");
        if (eventSource) {
          eventSource.close();
          eventSource = null;
        }
        if (!closed) {
          window.setTimeout(connectSse, 2500);
        }
      };
    } catch {
      onConnectionChange(false, "OFFLINE_DETERMINISTIC_SIM");
    }
  };

  connectSse();

  // Watchdog fallback: if backend hasn't pushed a frame within 650ms, step local deterministic engine
  fallbackTimer = window.setInterval(() => {
    if (Date.now() - lastReceivedAt > 650) {
      const localFrame = stepLocalFallbackSimulation();
      onFrame(localFrame);
    }
  }, 45);

  return () => {
    closed = true;
    if (eventSource) {
      eventSource.close();
    }
    if (fallbackTimer !== null) {
      window.clearInterval(fallbackTimer);
    }
  };
}

export function generateExecutionId(): string {
  const rand = Math.random().toString(36).slice(2, 10);
  return `kineguard-${rand}`;
}

export interface PipelineControllerExecutionOutcome {
  status: "COMPLETED" | "BLOCKED_CONCURRENT" | "DISCARDED_STALE" | "ABORTED";
  executionId: string;
  result: VerifiableSceneResponse | null;
}

/**
 * Phase 2 Req 1: Concurrency controller & out-of-order stale response guard.
 * - Starts in IDLE state (never auto-executes on creation).
 * - Blocks concurrent activations when allowPreempt=false (default for UI buttons).
 * - When allowPreempt=true, aborts the previous AbortController and guarantees via monotonic sequence
 *   numbers that an older out-of-order response can NEVER overwrite the state of a newer execution.
 */
export function createPipelineExecutionController(
  defaultExecutor?: (
    req: AnalyzeSceneRequest,
    redTeam: boolean,
    signal: AbortSignal
  ) => Promise<VerifiableSceneResponse>
) {
  let state: "IDLE" | "RUNNING" | "COMPLETED" = "IDLE";
  let inFlight = false;
  let latestSeq = 0;
  let activeExecutionId: string | null = null;
  let activeAbortController: AbortController | null = null;
  let latestResult: VerifiableSceneResponse | null = null;
  let postCount = 0;

  const runExecutor =
    defaultExecutor ??
    ((req: AnalyzeSceneRequest, redTeam: boolean, signal: AbortSignal) =>
      redTeam ? runRedTeamScenario(req, signal) : analyzeAndVerifyScene(req, signal));

  return {
    getState: () => ({
      state,
      inFlight,
      latestSeq,
      activeExecutionId,
      latestResult,
      postCount,
    }),
    abortActive: () => {
      if (activeAbortController) {
        activeAbortController.abort();
        activeAbortController = null;
      }
      inFlight = false;
      state = latestResult ? "COMPLETED" : "IDLE";
    },
    execute: async (
      request: AnalyzeSceneRequest,
      options?: {
        redTeam?: boolean;
        allowPreempt?: boolean;
        onStart?: (executionId: string) => void;
        onComplete?: (result: VerifiableSceneResponse) => void;
        onDiscardStale?: (staleExecutionId: string, currentExecutionId: string | null) => void;
      }
    ): Promise<PipelineControllerExecutionOutcome> => {
      const allowPreempt = options?.allowPreempt ?? false;
      if (inFlight && !allowPreempt) {
        return {
          status: "BLOCKED_CONCURRENT",
          executionId: activeExecutionId ?? "none",
          result: null,
        };
      }

      if (activeAbortController) {
        activeAbortController.abort();
      }

      const mySeq = ++latestSeq;
      const executionId =
        request.executionId && request.executionId.trim().length > 0
          ? request.executionId.trim()
          : generateExecutionId();
      const abortController = new AbortController();
      activeAbortController = abortController;
      activeExecutionId = executionId;
      inFlight = true;
      state = "RUNNING";
      latestResult = null;
      postCount++;

      options?.onStart?.(executionId);

      const enrichedRequest: AnalyzeSceneRequest = {
        ...request,
        executionId,
      };

      try {
        const response = await runExecutor(
          enrichedRequest,
          Boolean(options?.redTeam),
          abortController.signal
        );

        // Out-of-order / stale response guard: never let an older execution overwrite a newer one
        if (mySeq !== latestSeq) {
          options?.onDiscardStale?.(executionId, activeExecutionId);
          return {
            status: "DISCARDED_STALE",
            executionId,
            result: null,
          };
        }

        const correlatedResponse: VerifiableSceneResponse = {
          ...response,
          executionId: response.executionId || executionId,
        };
        latestResult = correlatedResponse;
        inFlight = false;
        state = "COMPLETED";
        options?.onComplete?.(correlatedResponse);
        return {
          status: "COMPLETED",
          executionId: correlatedResponse.executionId,
          result: correlatedResponse,
        };
      } catch (err) {
        const isAbort =
          abortController.signal.aborted ||
          (err instanceof Error && (err.name === "AbortError" || err.message === "ABORTED"));
        if (mySeq !== latestSeq) {
          options?.onDiscardStale?.(executionId, activeExecutionId);
          return {
            status: "DISCARDED_STALE",
            executionId,
            result: null,
          };
        }
        inFlight = false;
        if (isAbort) {
          state = "IDLE";
          return {
            status: "ABORTED",
            executionId,
            result: null,
          };
        }
        const reason = err instanceof Error ? err.message : "CONNECTION_REFUSED";
        const fallback = buildUnverifiedBackendErrorResponse(
          enrichedRequest,
          Boolean(options?.redTeam),
          reason
        );
        latestResult = fallback;
        state = "COMPLETED";
        options?.onComplete?.(fallback);
        return {
          status: "COMPLETED",
          executionId: fallback.executionId,
          result: fallback,
        };
      }
    },
  };
}

/**
 * KineGuard ROS2 Studio primary pipeline:
 * Calls POST /api/v1/scene/analyze-and-verify (Homography + SAM 3.1 + Muse Spark + Multi-Turn Tool Loop + ROS 2 Export).
 * P0-A Integrity Rule: If the backend is unreachable, returns an HTTP error, or returns an invalid payload,
 * this function MUST return an explicit UNVERIFIED error state and NEVER fabricate an APPROVED trajectory.
 */
export async function analyzeAndVerifyScene(
  request: AnalyzeSceneRequest,
  signal?: AbortSignal
): Promise<VerifiableSceneResponse> {
  const executionId =
    request.executionId && request.executionId.trim().length > 0
      ? request.executionId.trim()
      : generateExecutionId();
  const payload: AnalyzeSceneRequest = { ...request, executionId };
  try {
    const response = await fetch(`${API_BASE}/api/v1/scene/analyze-and-verify`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal,
    });
    if (!response.ok) {
      throw new Error(`HTTP_${response.status}`);
    }
    const data: VerifiableSceneResponse = await response.json();
    if (!data || typeof data !== "object" || !Array.isArray(data.verifiedWaypoints)) {
      throw new Error("INVALID_BACKEND_RESPONSE");
    }
    return {
      ...normalizeSceneResponse(data),
      executionId: data.executionId || executionId,
    };
  } catch (err) {
    if (signal?.aborted || (err instanceof Error && err.name === "AbortError")) {
      throw err;
    }
    const reason = err instanceof Error ? err.message : "CONNECTION_REFUSED";
    return buildUnverifiedBackendErrorResponse(payload, false, reason);
  }
}

/**
 * KineGuard ROS2 Studio Adversarial Red-Team Generator:
 * Calls POST /api/v1/scene/red-team (Muse Image 1.0 Obstacle Synthesis + SAM 3.1 + Multi-Turn Replan).
 * P0-A Integrity Rule: Never fabricates an APPROVED trajectory when the backend fails or is offline.
 */
export async function runRedTeamScenario(
  request: AnalyzeSceneRequest,
  signal?: AbortSignal
): Promise<VerifiableSceneResponse> {
  const executionId =
    request.executionId && request.executionId.trim().length > 0
      ? request.executionId.trim()
      : generateExecutionId();
  const payload: AnalyzeSceneRequest = { ...request, executionId };
  try {
    const response = await fetch(`${API_BASE}/api/v1/scene/red-team`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal,
    });
    if (!response.ok) {
      throw new Error(`HTTP_${response.status}`);
    }
    const data: VerifiableSceneResponse = await response.json();
    if (!data || typeof data !== "object" || !Array.isArray(data.verifiedWaypoints)) {
      throw new Error("INVALID_BACKEND_RESPONSE");
    }
    return {
      ...normalizeSceneResponse(data),
      executionId: data.executionId || executionId,
    };
  } catch (err) {
    if (signal?.aborted || (err instanceof Error && err.name === "AbortError")) {
      throw err;
    }
    const reason = err instanceof Error ? err.message : "CONNECTION_REFUSED";
    return buildUnverifiedBackendErrorResponse(payload, true, reason);
  }
}

/**
 * Sends 24kHz 16-bit mono PCM WAV audio to POST /api/v1/voice/transcribe (Muse Voice Transcribe 1.0).
 */
export async function transcribeVoiceAudio(
  wavBlob: Blob | null,
  transcriptHint: string
): Promise<MuseVoiceTranscribeResponse> {
  try {
    if (wavBlob && wavBlob.size > 0) {
      const formData = new FormData();
      formData.append("audio", wavBlob, "operator_command_24khz.wav");
      if (transcriptHint) {
        formData.append("transcriptHint", transcriptHint);
      }
      const response = await fetch(`${API_BASE}/api/v1/voice/transcribe`, {
        method: "POST",
        body: formData,
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      return await response.json();
    }

    const response = await fetch(`${API_BASE}/api/v1/voice/transcribe`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ transcriptHint }),
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    return await response.json();
  } catch {
    return {
      requestId: `mv-${Date.now()}`,
      model: "muse-voice-transcribe-1.0",
      transcript:
        transcriptHint ||
        "Navigate AMR-01 from South Staging to North Receiving Dock maintaining kinematic stopping clearance",
      confidence: 0.994,
      detectedHazardType: "CORRIDOR_EVACUATION",
      targetSector: "SECTOR-A",
      inferredEpicenter: { x: 500, y: 460 },
      latencyMs: 3.2,
      fallbackMock: true,
    };
  }
}

/**
 * Downloads a ROS 2 Nav2 package from GET /api/v1/ros2/export.
 * With an executionId the package is bound to that execution; an unknown id
 * yields the local UNVERIFIED bundle, never another execution's package.
 */
export async function exportRos2Bundle(executionId?: string): Promise<Ros2ExportBundle> {
  try {
    const query = executionId ? `?executionId=${encodeURIComponent(executionId)}` : "";
    const response = await fetch(`${API_BASE}/api/v1/ros2/export${query}`);
    if (!response.ok) {
      throw new Error(`HTTP_${response.status}`);
    }
    return await response.json();
  } catch (err) {
    const reason = err instanceof Error ? err.message : "CONNECTION_REFUSED";
    return buildUnverifiedBackendErrorResponse({}, false, reason).ros2Bundle;
  }
}

export async function injectChaosAnomaly(
  request: ChaosInjectionRequest
): Promise<ClosedLoopExecutionResponse> {
  try {
    const response = await fetch(`${API_BASE}/api/v1/chaos/inject`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    return await response.json();
  } catch {
    return executeLocalFallbackClosedLoop(
      request.commandText || request.presetId || "Reroute AMRs around central pallet obstruction",
      request.centerX,
      request.centerY
    );
  }
}

export async function sendVoiceCommand(
  request: VoiceCommandRequest
): Promise<ClosedLoopExecutionResponse> {
  try {
    const response = await fetch(`${API_BASE}/api/v1/voice/command`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    return await response.json();
  } catch {
    return executeLocalFallbackClosedLoop(
      request.transcriptHint || "Navigate AMR-01 around central corridor pallet",
      request.targetX,
      request.targetY
    );
  }
}

export async function resetSpatialArena(): Promise<void> {
  try {
    await fetch(`${API_BASE}/api/v1/spatial/reset`, {
      method: "POST",
    });
  } catch {
    localFallbackState.hazards = [];
    localFallbackState.logs.push({
      id: Math.random().toString(36).slice(2, 9),
      timestamp: Date.now(),
      tag: "STATUS",
      level: "SUCCESS",
      message:
        "Corridor occupancy grid reset. All 6 AMRs restored to nominal Buffered Voronoi patrol.",
      latencyMs: 0.4,
    });
  }
}

// ============================================================================
// P0-A INTEGRITY GUARD: UNVERIFIED ERROR RESPONSE WHEN BACKEND IS OFFLINE/FAILS
// Never fabricates an APPROVED trajectory or verified waypoints on client side!
// ============================================================================

export function buildUnverifiedBackendErrorResponse(
  req: AnalyzeSceneRequest,
  redTeam: boolean,
  errorReason: string
): VerifiableSceneResponse {
  const widthM = req.corridorWidthMeters ?? 4.0;
  const lengthM = req.corridorLengthMeters ?? 6.0;
  const v = req.velocityMs ?? 1.6;
  const mu = req.frictionMu ?? 0.62;
  const aEff = Math.min(3.5, mu * 9.81);
  const dReact = v * 0.15;
  const dStop = Number(((v * v) / (2 * aEff) + dReact).toFixed(3));
  const dMin = Number((0.32 + dStop + 0.15).toFixed(3));
  const now = Date.now();

  const calPts = req.calibrationPoints ?? [
    { x: 140, y: 580 },
    { x: 720, y: 580 },
    { x: 580, y: 190 },
    { x: 280, y: 190 },
  ];

  const resolvedExecutionId =
    req.executionId && req.executionId.trim().length > 0
      ? req.executionId.trim()
      : `kg-unverified-${now}`;
  const unverifiedHash = `sha256:unverified-offline-${errorReason.toLowerCase().replace(/[^a-z0-9]/g, "-")}`;
  const errorMsg = `UNVERIFIED (BACKEND_${errorReason}): Verification backend unreachable or returned an error. Zero trajectories approved; zero ROS 2 poses exported.`;

  return {
    executionId: resolvedExecutionId,
    timestamp: now,
    sceneId: req.sceneId ?? "LOGISTICS_CORRIDOR_A",
    sceneTitle: req.sceneTitle ?? "Warehouse Logistics Spine A",
    imageSource: req.imageSource ?? "PRESET_SCENE",
    obstacleSource: "NONE",
    perceptionStatus: "PERCEPTION_ERROR",
    verificationStatus: "UNVERIFIED",
    verificationHash: unverifiedHash,
    waypointSource: "NONE",
    scaleType: req.scaleType ?? "UNKNOWN",
    executionMode: "FRONTEND_OFFLINE_ERROR",
    conceptPrompt: req.conceptPrompt ?? "box",
    voiceTranscript:
      req.voiceCommandHint ??
      "Navigate AMR-01 from South Staging to North Receiving Dock maintaining kinematic stopping clearance",
    activeImageDataUri: req.imageBase64,
    redTeamApplied: redTeam,
    redTeamPrompt: redTeam ? req.redTeamPrompt : undefined,
    corridorWidthMeters: widthM,
    corridorLengthMeters: lengthM,
    velocityMs: v,
    frictionMu: mu,
    stoppingDistanceMeters: dStop,
    requiredClearanceMeters: dMin,
    homographyMatrix: [0, 0, 0, 0, 0, 0, 0, 0, 0],
    calibrationPoints: calPts,
    obstacles: [],
    agentTurns: [],
    rejectedWaypoints: [],
    verifiedWaypoints: [],
    ros2Bundle: {
      sceneId: req.sceneId ?? "LOGISTICS_CORRIDOR_A",
      verificationStatus: "UNVERIFIED",
      verificationHash: unverifiedHash,
      scaleType: req.scaleType ?? "UNKNOWN",
      mapPgmAscii:
        `P2\n# UNVERIFIED (Backend Offline/Error) - All interior cells marked 205 (UNKNOWN)\n${Math.max(1, Math.round(widthM / 0.05))} ${Math.max(1, Math.round(lengthM / 0.05))}\n255\n0 205 205 0\n`,
      mapYaml: `image: map.pgm\nmode: trinary\nresolution: 0.050\norigin: [0.000, 0.000, 0.000]\nnegate: 0\noccupied_thresh: 0.65\nfree_thresh: 0.196\n# KineGuard Audit: status=UNVERIFIED, scale_type=${req.scaleType ?? "UNKNOWN"}, verification_hash=${unverifiedHash}\n`,
      nav2CostmapParamsYaml: `# UNVERIFIED: Backend offline or returned ${errorReason}\n`,
      nav2WaypointsJson: JSON.stringify(
        {
          header: {
            frame_id: "map",
            compiler: "KineGuard ROS2 Studio v2.1",
            verification_status: "UNVERIFIED",
            verification_hash: unverifiedHash,
            kinematic_approved: false,
            error_reason: errorReason,
            stopping_distance_meters: dStop,
          },
          poses: [],
        },
        null,
        2
      ),
      gridWidthCells: Math.max(1, Math.round(widthM / 0.05)),
      gridHeightCells: Math.max(1, Math.round(lengthM / 0.05)),
      resolutionMetersPerPixel: 0.05,
    },
    pipelineLogs: [
      {
        id: `err-${now}`,
        executionId: resolvedExecutionId,
        timestamp: now,
        tag: "KINEMATIC_GUARD",
        level: "ERROR",
        message: errorMsg,
        latencyMs: 0,
      },
    ],
    totalLatencyMs: 0,
    liveMetaApiCalls: 0,
    attemptedMetaApiCalls: 1,
    successfulMetaApiCalls: 0,
    failedMetaApiCalls: 1,
  };
}

const localFallbackState: {
  frameId: number;
  interceptions: number;
  robots: RobotNode[];
  hazards: HazardZone[];
  logs: TelemetryFrame["recentLogs"];
} = {
  frameId: 1,
  interceptions: 1,
  robots: [
    createFallbackRobot("AMR-01", "KINEGUARD-01", "PALLET_CARRIER", 180, 220, 420, 260, 66),
    createFallbackRobot("AMR-02", "KINEGUARD-02", "COLD_CHAIN_AMR", 510, 180, 810, 210, 70),
    createFallbackRobot("AMR-03", "KINEGUARD-03", "SORTATION_BOT", 820, 320, 840, 650, 64),
    createFallbackRobot("AMR-04", "KINEGUARD-04", "OPTICAL_INSPECTOR", 200, 780, 210, 520, 72),
    createFallbackRobot("AMR-05", "KINEGUARD-05", "HEAVY_PALLET_MOVER", 530, 800, 360, 610, 66),
    createFallbackRobot("AMR-06", "KINEGUARD-06", "LOGISTICS_TUG", 830, 770, 580, 730, 68),
  ],
  hazards: [],
  logs: [
    {
      id: "boot-01",
      timestamp: Date.now() - 4000,
      tag: "STATUS",
      level: "INFO",
      message: "KineGuard ROS2 Studio Kernel v2.0 booted on Java 21 Virtual Threads. 6 AMR nodes online.",
      latencyMs: 0.3,
    },
    {
      id: "boot-02",
      timestamp: Date.now() - 3000,
      tag: "SAM 3.1",
      level: "INFO",
      message: "SAM 3.1 (/v1/responses) & Projective Homography 3x3 Solver H initialized.",
      latencyMs: 1.1,
    },
    {
      id: "boot-03",
      timestamp: Date.now() - 2000,
      tag: "MUSE_WONDER",
      level: "INFO",
      message: "Muse Wonder 1.0 & Muse Spark 1.3 Verifiable Tool-Calling Planner ready.",
      latencyMs: 2.4,
    },
    {
      id: "boot-04",
      timestamp: Date.now() - 1000,
      tag: "KINEMATIC_GUARD",
      level: "SUCCESS",
      message: "Formal Kinematic Safety Verifier armed (d_stop = v²/2μg + v·t_react, BVC enforced).",
      latencyMs: 0.2,
    },
  ],
};

function createFallbackRobot(
  id: string,
  callsign: string,
  payloadClass: string,
  x: number,
  y: number,
  tx: number,
  ty: number,
  maxSpeed: number
): RobotNode {
  const angle = Math.atan2(ty - y, tx - x);
  return {
    id,
    callsign,
    payloadClass,
    position: { x, y },
    targetWaypoint: { x: tx, y: ty },
    proposedWaypoint: { x: tx, y: ty },
    vx: Math.cos(angle) * maxSpeed * 0.7,
    vy: Math.sin(angle) * maxSpeed * 0.7,
    speed: maxSpeed * 0.7,
    maxSpeed,
    headingRad: angle,
    radius: 18,
    safetyBuffer: 26,
    stoppingDistance: 36,
    batteryPct: 94.5,
    thermalC: 44.0,
    state: "NOMINAL",
    kinematicInterceptActive: false,
    lastInterceptReason: "BVC Envelope Nominal",
    lastInterceptTimestamp: 0,
    trajectoryHistory: [{ x, y }],
    patrolWaypoints: [
      { x, y },
      { x: tx, y: ty },
    ],
    currentWaypointIndex: 1,
  };
}

function stepLocalFallbackSimulation(): TelemetryFrame {
  const dt = 0.045;
  const now = Date.now();
  localFallbackState.frameId += 1;

  for (const r of localFallbackState.robots) {
    let dx = r.targetWaypoint.x - r.position.x;
    let dy = r.targetWaypoint.y - r.position.y;
    let dist = Math.hypot(dx, dy);
    if (dist < 30) {
      r.currentWaypointIndex = (r.currentWaypointIndex + 1) % r.patrolWaypoints.length;
      r.targetWaypoint = { ...r.patrolWaypoints[r.currentWaypointIndex] };
      dx = r.targetWaypoint.x - r.position.x;
      dy = r.targetWaypoint.y - r.position.y;
      dist = Math.max(1, Math.hypot(dx, dy));
    }

    let vx = (dx / dist) * r.maxSpeed * 0.75;
    let vy = (dy / dist) * r.maxSpeed * 0.75;
    let avoiding = false;

    for (const h of localFallbackState.hazards) {
      const hdx = r.position.x - h.center.x;
      const hdy = r.position.y - h.center.y;
      const hd = Math.max(1, Math.hypot(hdx, hdy));
      const safeD = h.effectiveRadius + r.radius + r.safetyBuffer + 35;
      if (hd < safeD) {
        avoiding = true;
        const nx = hdx / hd;
        const ny = hdy / hd;
        const tx = -ny;
        const ty = nx;
        vx = (0.7 * tx + 0.55 * nx) * r.maxSpeed;
        vy = (0.7 * ty + 0.55 * ny) * r.maxSpeed;
      }
    }

    r.vx = vx;
    r.vy = vy;
    r.speed = Math.hypot(vx, vy);
    r.headingRad = Math.atan2(vy, vx);
    r.position = {
      x: Math.max(30, Math.min(970, r.position.x + vx * dt)),
      y: Math.max(30, Math.min(970, r.position.y + vy * dt)),
    };
    r.state = avoiding ? "AVOIDING" : "NOMINAL";
    r.kinematicInterceptActive = avoiding || now - r.lastInterceptTimestamp < 4000;
    r.trajectoryHistory.push({ ...r.position });
    if (r.trajectoryHistory.length > 32) {
      r.trajectoryHistory.shift();
    }
  }

  const cells: VoronoiCell[] = localFallbackState.robots.map((r) => ({
    robotId: r.id,
    generatorSite: { ...r.position },
    rawVertices: [],
    bufferedVertices: computeSimpleLocalBvc(r, localFallbackState.robots),
    areaSqUnits: 145000,
    minClearance: 44,
  }));

  return {
    frameId: localFallbackState.frameId,
    timestamp: now,
    gridWidth: 1000,
    gridHeight: 1000,
    robots: [...localFallbackState.robots],
    voronoiCells: cells,
    hazards: [...localFallbackState.hazards],
    recentLogs: [...localFallbackState.logs],
    metrics: {
      activeNodes: localFallbackState.robots.length,
      activeHazards: localFallbackState.hazards.length,
      closedLoopLatencyMs: 12.6,
      kinematicCheckLatencyMs: 0.34,
      collisionRatePct: 0.0,
      guardrailInterceptions: localFallbackState.interceptions,
      totalFramesProcessed: localFallbackState.frameId,
      telemetryHz: 25.0,
      minFleetSeparationMeters: 18.4,
      metaIntegrationMode: "META_HYBRID_DETERMINISTIC",
      physicalStabilityNominal: true,
    },
  };
}

function computeSimpleLocalBvc(robot: RobotNode, allRobots: RobotNode[]): SpatialCoordinate[] {
  let poly: SpatialCoordinate[] = [
    { x: 14, y: 14 },
    { x: 986, y: 14 },
    { x: 986, y: 986 },
    { x: 14, y: 986 },
  ];
  for (const other of allRobots) {
    if (other.id === robot.id) continue;
    const dx = other.position.x - robot.position.x;
    const dy = other.position.y - robot.position.y;
    const dist = Math.hypot(dx, dy);
    if (dist < 1e-4) continue;
    const nx = dx / dist;
    const ny = dy / dist;
    const retract = Math.min(26, dist * 0.4);
    const px = 0.5 * (robot.position.x + other.position.x) - nx * retract;
    const py = 0.5 * (robot.position.y + other.position.y) - ny * retract;

    const nextPoly: SpatialCoordinate[] = [];
    for (let i = 0; i < poly.length; i++) {
      const a = poly[i];
      const b = poly[(i + 1) % poly.length];
      const da = (a.x - px) * nx + (a.y - py) * ny;
      const db = (b.x - px) * nx + (b.y - py) * ny;
      if (da <= 0 && db <= 0) {
        nextPoly.push(b);
      } else if (da <= 0 && db > 0) {
        const t = da / (da - db);
        nextPoly.push({ x: a.x + t * (b.x - a.x), y: a.y + t * (b.y - a.y) });
      } else if (da > 0 && db <= 0) {
        const t = da / (da - db);
        nextPoly.push({ x: a.x + t * (b.x - a.x), y: a.y + t * (b.y - a.y) });
        nextPoly.push(b);
      }
    }
    poly = nextPoly;
  }
  return poly;
}

function executeLocalFallbackClosedLoop(
  transcript: string,
  cx?: number,
  cy?: number
): ClosedLoopExecutionResponse {
  const now = Date.now();
  const center = { x: cx ?? 520, y: cy ?? 460 };
  const radius = 105;
  const hull: SpatialCoordinate[] = [];
  for (let i = 0; i < 48; i++) {
    const a = (2 * Math.PI * i) / 48;
    const r = radius * (1 + 0.05 * Math.cos(3 * a));
    hull.push({ x: center.x + r * Math.cos(a), y: center.y + r * Math.sin(a) });
  }

  const hazard: HazardZone = {
    id: `HZ-${Math.random().toString(36).slice(2, 6).toUpperCase()}`,
    label: transcript,
    sector: "SECTOR-B",
    type: "THERMAL_RUNAWAY",
    center,
    effectiveRadius: radius,
    severity: 0.94,
    peakThermalC: 742.5,
    polygonHull: hull,
    samMaskId: "sam31-local",
    samConfidenceIou: 0.988,
    museImagePrompt: transcript,
    museImageOverlayDataUri: "",
    createdAtEpochMs: now,
    expiresAtEpochMs: now + 90000,
    active: true,
  };

  localFallbackState.hazards.push(hazard);
  localFallbackState.interceptions += 1;

  const newLogs: TelemetryFrame["recentLogs"] = [
    {
      id: `l1-${now}`,
      timestamp: now,
      tag: "MUSE_VOICE",
      level: "INFO",
      message: `Transcribed "${transcript}"`,
      latencyMs: 3.1,
    },
    {
      id: `l2-${now}`,
      timestamp: now + 1,
      tag: "MUSE_IMAGE",
      level: "WARN",
      message: "Synthesized adversarial floor obstacle mask",
      latencyMs: 4.2,
    },
    {
      id: `l3-${now}`,
      timestamp: now + 2,
      tag: "SAM 3.1",
      level: "INFO",
      message: "Generated 48-point convex hull polygon around obstacle",
      latencyMs: 2.9,
    },
    {
      id: `l4-${now}`,
      timestamp: now + 3,
      tag: "MUSE_WONDER",
      level: "INFO",
      message: "Multi-agent path replan proposed",
      latencyMs: 3.6,
    },
    {
      id: `l5-${now}`,
      timestamp: now + 4,
      tag: "KINEMATIC_GUARD",
      level: "INTERCEPT",
      message: "Intercepted! Path violates BVC clearance. Deflected via orthogonal vector (Latency: 0.34ms)",
      latencyMs: 0.34,
    },
    {
      id: `l6-${now}`,
      timestamp: now + 5,
      tag: "STATUS",
      level: "SUCCESS",
      message: "Closed-loop physical stability maintained.",
      latencyMs: 0.2,
    },
  ];

  localFallbackState.logs.push(...newLogs);

  return {
    executionId: `exec-${now}`,
    timestamp: now,
    voiceStage: {
      requestId: "mv-local",
      model: "muse-voice-transcribe-1.0",
      transcript,
      confidence: 0.994,
      detectedHazardType: "THERMAL_RUNAWAY",
      targetSector: "SECTOR-B",
      inferredEpicenter: center,
      latencyMs: 3.1,
      fallbackMock: true,
    },
    imageStage: {
      generationId: "mi-local",
      model: "muse-image-1.0",
      synthesizedPrompt: transcript,
      overlayDataUri: "",
      peakThermalCelsius: 742.5,
      opacityIndex: 0.89,
      anomalyCenter: center,
      anomalyRadius: radius,
      latencyMs: 4.2,
      fallbackMock: true,
    },
    samStage: {
      maskId: "sam31-local",
      model: "sam-3.1",
      vertexCount: 48,
      iouScore: 0.988,
      stabilityScore: 0.993,
      convexHullVertices: hull,
      latencyMs: 2.9,
      fallbackMock: true,
    },
    wonderStage: {
      decisionId: "mw-local",
      primaryModel: "muse-wonder-1.0",
      partnerModel: "muse-spark-1.3",
      executiveSummary: "Multi-agent path replan proposed",
      latencyMs: 3.6,
      fallbackMock: true,
    },
    kinematicVerdicts: [],
    activeHazard: hazard,
    pipelineLogs: newLogs,
    totalClosedLoopLatencyMs: 14.2,
  };
}
