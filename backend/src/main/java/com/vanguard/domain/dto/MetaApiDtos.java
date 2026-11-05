package com.vanguard.domain.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.vanguard.domain.model.HazardZone;
import com.vanguard.domain.model.SpatialCoordinate;
import com.vanguard.domain.model.TelemetryFrame;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

public final class MetaApiDtos {

    private MetaApiDtos() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MuseVoiceTranscribeRequest {
        private String model;
        private String audioBase64;
        private String language;
        private String domainContext;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MuseVoiceTranscribeResponse {
        private String requestId;
        private String model;
        private String transcript;
        private double confidence;
        private String detectedHazardType;
        private String targetSector;
        private SpatialCoordinate inferredEpicenter;
        private double latencyMs;
        private boolean fallbackMock;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MuseImageChaosRequest {
        private String model;
        private String prompt;
        private String baseFrameBase64;
        private double epicenterX;
        private double epicenterY;
        private double radius;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MuseImageChaosResponse {
        private String generationId;
        private String model;
        private String synthesizedPrompt;
        private String overlayDataUri;
        private double peakThermalCelsius;
        private double opacityIndex;
        private SpatialCoordinate anomalyCenter;
        private double anomalyRadius;
        private double latencyMs;
        private boolean fallbackMock;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sam31SegmentRequest {
        private String model;
        private String imagePayloadBase64;
        private SpatialCoordinate promptPoint;
        private double boundingRadius;
        private int targetPolygonVertices;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sam31SegmentResponse {
        private String maskId;
        private String model;
        private int vertexCount;
        private double iouScore;
        private double stabilityScore;
        private List<SpatialCoordinate> convexHullVertices;
        private double latencyMs;
        private boolean fallbackMock;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProposedRobotTrajectory {
        private String robotId;
        private SpatialCoordinate proposedWaypoint;
        private double proposedVx;
        private double proposedVy;
        private String reasoningNote;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MuseWonderReasonRequest {
        private String model;
        private String partnerEngine;
        private String contextPrompt;
        private List<Map<String, Object>> fleetSnapshot;
        private List<SpatialCoordinate> hazardHull;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MuseWonderReasonResponse {
        private String decisionId;
        private String primaryModel;
        private String partnerModel;
        private String executiveSummary;
        private List<ProposedRobotTrajectory> trajectories;
        private double latencyMs;
        private boolean fallbackMock;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KinematicInterceptRecord {
        private String robotId;
        private boolean intercepted;
        private String reason;
        private SpatialCoordinate agentProposedVector;
        private SpatialCoordinate kernelDeflectedVector;
        private double stoppingDistanceMeters;
        private double verificationLatencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClosedLoopExecutionResponse {
        private String executionId;
        private long timestamp;
        private MuseVoiceTranscribeResponse voiceStage;
        private MuseImageChaosResponse imageStage;
        private Sam31SegmentResponse samStage;
        private MuseWonderReasonResponse wonderStage;
        private List<KinematicInterceptRecord> kinematicVerdicts;
        private HazardZone activeHazard;
        private List<TelemetryFrame.AgentLogEntry> pipelineLogs;
        private double totalClosedLoopLatencyMs;
    }

    // =========================================================================
    // KineGuard ROS2 Studio — Metric Homography, SAM 3.1, Tool-Calling & Nav2
    // =========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    // Lombok getXMin() otherwise becomes "xmin" under Jackson's bean naming.
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
            getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE,
            setterVisibility = JsonAutoDetect.Visibility.NONE)
    public static class BoundingBox {
        private double xMin; // Normalized 0-1000 or pixel coordinate
        private double yMin;
        private double xMax;
        private double yMax;
        private String label;
        private double confidence;
        private String samRawToken;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    // Preserve the field names used by the frontend and tool-call contract.
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
            getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE,
            setterVisibility = JsonAutoDetect.Visibility.NONE)
    public static class MetricWaypoint {
        private String id;
        private double xMeters;
        private double yMeters;
        private double uPixel;
        private double vPixel;
        private double targetSpeedMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricObstacle {
        private String id;
        private String label;
        private double confidence;
        private BoundingBox pixelBox;
        private List<SpatialCoordinate> pixelPolygon;
        private List<SpatialCoordinate> metricPolygon;
        private double centerXMeters;
        private double centerYMeters;
        private double radiusMeters;
        private double inflatedRadiusMeters;
        private String samMaskToken;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerificationReport {
        private boolean approved;
        private String verificationStatus; // "APPROVED", "REJECTED", or "UNVERIFIED"
        private String verificationHash;   // SHA-256 over full physical + calibration + trajectory state
        private double minClearanceMeters;
        private double lateralClearanceRequiredMeters;
        private double requiredClearanceMeters;
        private double stoppingDistanceMeters;
        private double reactionDistanceMeters;
        private double effectiveDecelerationMps2;
        private String violatingSegmentId;
        private String violatingObstacleId;
        private String counterexampleMessage;
        private double verificationLatencyMs;
        private List<MetricWaypoint> evaluatedWaypoints;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentVerificationTurn {
        private int turnNumber;
        private String modelUsed;
        private String toolName;
        private String toolCallId;
        private String waypointSource; // "LLM_TOOL_CALL", "DETERMINISTIC_FALLBACK", or "MOCK_SCRIPTED"
        private String reasoning;
        private String toolCallArgumentsJson;
        private String requestMessagesJson;
        private List<MetricWaypoint> proposedWaypoints;
        private VerificationReport kernelVerdict;
        private String counterexampleMessage;
        private double minClearanceMeters;
        private double latencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerceptionPipelineResult {
        private String perceptionStatus; // "DETECTED", "NO_DETECTIONS", "INVALID_DETECTION", or "PERCEPTION_ERROR"
        private String obstacleSource;   // "SAM_LIVE", "PRESET_DEMO", or "NONE"
        private List<MetricObstacle> obstacles;
        private int attemptedApiCalls;
        private int successfulApiCalls;
        private int failedApiCalls;
        private double latencyMs;
        private double samLatencyMs;
        private double groundingLatencyMs;
        private String rawSamOutput;
        private String rawGroundingOutput;
        private int rejectedDetectionsCount;
        private List<String> diagnosticNotes;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Ros2ExportBundle {
        private String sceneId;
        private String executionId;
        private String verificationStatus;
        private String verificationHash;
        private String scaleType;
        private String mapPgmAscii;
        private String mapYaml;
        private String nav2CostmapParamsYaml;
        private String nav2WaypointsJson;
        private int gridWidthCells;
        private int gridHeightCells;
        private double resolutionMetersPerPixel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnalyzeSceneRequest {
        private String executionId;
        private String sceneId;
        private String sceneTitle;
        private String imageSource; // "PRESET_SCENE" or "USER_UPLOAD"
        private String conceptPrompt;
        private String imageBase64;
        private Integer imageWidth;
        private Integer imageHeight;
        private List<SpatialCoordinate> calibrationPoints;
        private Double corridorWidthMeters;
        private Double corridorLengthMeters;
        private Double velocityMs;
        private Double frictionMu;
        private String voiceCommandHint;
        private String reasoningModel;
        private String redTeamPrompt;
        private List<BoundingBox> presetObstacles;
        private String scaleType; // MEASURED_REFERENCE, NOMINAL_PRESET, NOMINAL_ASSUMED, or UNKNOWN
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifiableSceneResponse {
        private String executionId;
        private long timestamp;
        private String sceneId;
        private String sceneTitle;
        private String imageSource;        // "PRESET_SCENE" or "USER_UPLOAD"
        private String obstacleSource;     // "SAM_LIVE", "PRESET_DEMO", "RED_TEAM_SYNTHETIC", or "NONE"
        private String perceptionStatus;   // "DETECTED", "NO_DETECTIONS", "INVALID_DETECTION", or "PERCEPTION_ERROR"
        private String verificationStatus; // "APPROVED", "REJECTED", or "UNVERIFIED"
        private String verificationHash;
        private String waypointSource;     // "LLM_TOOL_CALL", "DETERMINISTIC_FALLBACK", "MOCK_SCRIPTED", or "NONE"
        private String scaleType;          // "MEASURED_REFERENCE", "NOMINAL_PRESET", "NOMINAL_ASSUMED", or "UNKNOWN"
        private String executionMode;      // "LIVE_META_API" or "OFFLINE_DETERMINISTIC"
        private String conceptPrompt;
        private String voiceTranscript;
        private String activeImageDataUri;
        private boolean redTeamApplied;
        private String redTeamPrompt;
        private double corridorWidthMeters;
        private double corridorLengthMeters;
        private double velocityMs;
        private double frictionMu;
        private double stoppingDistanceMeters;
        private double requiredClearanceMeters;
        private List<Double> homographyMatrix;
        private List<SpatialCoordinate> calibrationPoints;
        private List<MetricObstacle> obstacles;
        private List<AgentVerificationTurn> agentTurns;
        private List<MetricWaypoint> rejectedWaypoints;
        private List<MetricWaypoint> verifiedWaypoints;
        private Ros2ExportBundle ros2Bundle;
        private List<TelemetryFrame.AgentLogEntry> pipelineLogs;
        private double totalLatencyMs;
        private double samLatencyMs;
        private double groundingLatencyMs;
        private double turn1LatencyMs;
        private double turn2LatencyMs;
        private String rawSamOutput;
        private String rawGroundingOutput;
        private int rejectedDetectionsCount;
        private int liveMetaApiCalls;
        private int attemptedMetaApiCalls;
        private int successfulMetaApiCalls;
        private int failedMetaApiCalls;
    }
}
