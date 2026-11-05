export type OperationalState = "NOMINAL" | "AVOIDING" | "EMERGENCY_STOP";

export type HazardType =
  | "THERMAL_RUNAWAY"
  | "STRUCTURAL_COLLAPSE"
  | "CORRIDOR_EVACUATION"
  | "FREIGHT_REROUTE_ZONE";

export interface SpatialCoordinate {
  x: number;
  y: number;
}

export interface RobotNode {
  id: string;
  callsign: string;
  payloadClass: string;
  position: SpatialCoordinate;
  targetWaypoint: SpatialCoordinate;
  proposedWaypoint: SpatialCoordinate;
  vx: number;
  vy: number;
  speed: number;
  maxSpeed: number;
  headingRad: number;
  radius: number;
  safetyBuffer: number;
  stoppingDistance: number;
  batteryPct: number;
  thermalC: number;
  state: OperationalState;
  kinematicInterceptActive: boolean;
  lastInterceptReason: string;
  lastInterceptTimestamp: number;
  trajectoryHistory: SpatialCoordinate[];
  patrolWaypoints: SpatialCoordinate[];
  currentWaypointIndex: number;
}

export interface VoronoiCell {
  robotId: string;
  generatorSite: SpatialCoordinate;
  rawVertices: SpatialCoordinate[];
  bufferedVertices: SpatialCoordinate[];
  areaSqUnits: number;
  minClearance: number;
}

export interface HazardZone {
  id: string;
  label: string;
  sector: string;
  type: HazardType;
  center: SpatialCoordinate;
  effectiveRadius: number;
  severity: number;
  peakThermalC: number;
  polygonHull: SpatialCoordinate[];
  samMaskId: string;
  samConfidenceIou: number;
  museImagePrompt: string;
  museImageOverlayDataUri: string;
  createdAtEpochMs: number;
  expiresAtEpochMs: number;
  active: boolean;
}

export interface AgentLogEntry {
  id: string;
  executionId?: string;
  timestamp: number;
  tag: string;
  level: "INFO" | "WARN" | "INTERCEPT" | "CRITICAL" | "SUCCESS" | "ERROR" | string;
  message: string;
  latencyMs: number;
}

export interface SystemMetrics {
  activeNodes: number;
  activeHazards: number;
  closedLoopLatencyMs: number;
  kinematicCheckLatencyMs: number;
  collisionRatePct: number;
  guardrailInterceptions: number;
  totalFramesProcessed: number;
  telemetryHz: number;
  minFleetSeparationMeters: number;
  metaIntegrationMode: string;
  physicalStabilityNominal: boolean;
}

export interface TelemetryFrame {
  frameId: number;
  timestamp: number;
  gridWidth: number;
  gridHeight: number;
  robots: RobotNode[];
  voronoiCells: VoronoiCell[];
  hazards: HazardZone[];
  recentLogs: AgentLogEntry[];
  metrics: SystemMetrics;
}

export interface KinematicInterceptRecord {
  robotId: string;
  intercepted: boolean;
  reason: string;
  agentProposedVector: SpatialCoordinate;
  kernelDeflectedVector: SpatialCoordinate;
  stoppingDistanceMeters: number;
  verificationLatencyMs: number;
}

export interface ClosedLoopExecutionResponse {
  executionId: string;
  timestamp: number;
  voiceStage: {
    requestId: string;
    model: string;
    transcript: string;
    confidence: number;
    detectedHazardType: string;
    targetSector: string;
    inferredEpicenter: SpatialCoordinate;
    latencyMs: number;
    fallbackMock: boolean;
  };
  imageStage: {
    generationId: string;
    model: string;
    synthesizedPrompt: string;
    overlayDataUri: string;
    peakThermalCelsius: number;
    opacityIndex: number;
    anomalyCenter: SpatialCoordinate;
    anomalyRadius: number;
    latencyMs: number;
    fallbackMock: boolean;
  };
  samStage: {
    maskId: string;
    model: string;
    vertexCount: number;
    iouScore: number;
    stabilityScore: number;
    convexHullVertices: SpatialCoordinate[];
    latencyMs: number;
    fallbackMock: boolean;
  };
  wonderStage: {
    decisionId: string;
    primaryModel: string;
    partnerModel: string;
    executiveSummary: string;
    latencyMs: number;
    fallbackMock: boolean;
  };
  kinematicVerdicts: KinematicInterceptRecord[];
  activeHazard: HazardZone;
  pipelineLogs: AgentLogEntry[];
  totalClosedLoopLatencyMs: number;
}

export interface ChaosInjectionRequest {
  presetId?: string;
  commandText?: string;
  hazardType?: string;
  sector?: string;
  centerX?: number;
  centerY?: number;
  radius?: number;
  severity?: number;
}

export interface VoiceCommandRequest {
  audioBase64?: string;
  transcriptHint?: string;
  sectorHint?: string;
  targetX?: number;
  targetY?: number;
}

// =========================================================================
// KineGuard ROS2 Studio — Metric Homography, SAM 3.1, Tool-Calling & Nav2
// =========================================================================

export interface BoundingBox {
  xMin: number;
  yMin: number;
  xMax: number;
  yMax: number;
  label: string;
  confidence: number;
  samRawToken?: string;
}

export interface MetricWaypoint {
  id: string;
  xMeters: number;
  yMeters: number;
  uPixel: number;
  vPixel: number;
  targetSpeedMs: number;
}

export interface MetricObstacle {
  id: string;
  label: string;
  confidence: number;
  pixelBox: BoundingBox;
  pixelPolygon: SpatialCoordinate[];
  metricPolygon: SpatialCoordinate[];
  centerXMeters: number;
  centerYMeters: number;
  radiusMeters: number;
  inflatedRadiusMeters: number;
  samMaskToken: string;
}

export interface VerificationReport {
  approved: boolean;
  verificationStatus?: string;
  verificationHash?: string;
  minClearanceMeters: number;
  lateralClearanceRequiredMeters?: number;
  requiredClearanceMeters: number;
  stoppingDistanceMeters: number;
  reactionDistanceMeters: number;
  effectiveDecelerationMps2?: number;
  violatingSegmentId?: string;
  violatingObstacleId?: string;
  counterexampleMessage: string;
  verificationLatencyMs: number;
  evaluatedWaypoints?: MetricWaypoint[];
}

export interface AgentVerificationTurn {
  turnNumber: number;
  modelUsed: string;
  toolName: string;
  toolCallId: string;
  waypointSource?: "LLM_TOOL_CALL" | "DETERMINISTIC_FALLBACK" | "MOCK_SCRIPTED" | string;
  reasoning: string;
  toolCallArgumentsJson: string;
  requestMessagesJson?: string;
  proposedWaypoints: MetricWaypoint[];
  kernelVerdict: VerificationReport;
  counterexampleMessage: string;
  minClearanceMeters: number;
  latencyMs: number;
}

export type ScaleType =
  | "MEASURED_REFERENCE"
  | "NOMINAL_PRESET"
  | "NOMINAL_ASSUMED"
  | "UNKNOWN";

export interface Ros2ExportBundle {
  sceneId?: string;
  executionId?: string;
  verificationStatus?: string;
  verificationHash?: string;
  scaleType?: ScaleType | string;
  mapPgmAscii: string;
  mapYaml: string;
  nav2CostmapParamsYaml?: string;
  nav2WaypointsJson: string;
  gridWidthCells: number;
  gridHeightCells: number;
  resolutionMetersPerPixel: number;
}

export interface AnalyzeSceneRequest {
  executionId?: string;
  sceneId?: string;
  sceneTitle?: string;
  imageSource?: "PRESET_SCENE" | "USER_UPLOAD";
  conceptPrompt?: string;
  imageBase64?: string;
  imageWidth?: number;
  imageHeight?: number;
  calibrationPoints?: SpatialCoordinate[];
  corridorWidthMeters?: number;
  corridorLengthMeters?: number;
  velocityMs?: number;
  frictionMu?: number;
  voiceCommandHint?: string;
  reasoningModel?: string;
  redTeamPrompt?: string;
  presetObstacles?: BoundingBox[];
  scaleType?: ScaleType | string;
}

export interface VerifiableSceneResponse {
  executionId: string;
  timestamp: number;
  sceneId: string;
  sceneTitle: string;
  imageSource?: "PRESET_SCENE" | "USER_UPLOAD" | string;
  obstacleSource?: "SAM_LIVE" | "PRESET_DEMO" | "RED_TEAM_SYNTHETIC" | "NONE" | string;
  perceptionStatus?: "DETECTED" | "NO_DETECTIONS" | "INVALID_DETECTION" | "PERCEPTION_ERROR" | string;
  verificationStatus?: "APPROVED" | "REJECTED" | "UNVERIFIED" | string;
  verificationHash?: string;
  waypointSource?: "LLM_TOOL_CALL" | "DETERMINISTIC_FALLBACK" | "MOCK_SCRIPTED" | "NONE" | string;
  scaleType?: ScaleType | string;
  executionMode?: "LIVE_META_API" | "OFFLINE_DETERMINISTIC" | "FRONTEND_OFFLINE_ERROR" | string;
  conceptPrompt: string;
  voiceTranscript: string;
  activeImageDataUri?: string;
  redTeamApplied: boolean;
  redTeamPrompt?: string;
  corridorWidthMeters: number;
  corridorLengthMeters: number;
  velocityMs: number;
  frictionMu: number;
  stoppingDistanceMeters: number;
  requiredClearanceMeters: number;
  homographyMatrix: number[];
  calibrationPoints: SpatialCoordinate[];
  obstacles: MetricObstacle[];
  agentTurns: AgentVerificationTurn[];
  rejectedWaypoints: MetricWaypoint[];
  verifiedWaypoints: MetricWaypoint[];
  ros2Bundle: Ros2ExportBundle;
  pipelineLogs: AgentLogEntry[];
  totalLatencyMs: number;
  samLatencyMs?: number;
  groundingLatencyMs?: number;
  turn1LatencyMs?: number;
  turn2LatencyMs?: number;
  rawSamOutput?: string;
  rawGroundingOutput?: string;
  rejectedDetectionsCount?: number;
  liveMetaApiCalls: number;
  attemptedMetaApiCalls?: number;
  successfulMetaApiCalls?: number;
  failedMetaApiCalls?: number;
}

export interface MuseVoiceTranscribeResponse {
  requestId: string;
  model: string;
  transcript: string;
  confidence: number;
  detectedHazardType: string;
  targetSector: string;
  inferredEpicenter: SpatialCoordinate;
  latencyMs: number;
  fallbackMock: boolean;
}
