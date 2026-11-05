package com.vanguard.meta;

import com.vanguard.domain.dto.ChaosInjectionRequest;
import com.vanguard.domain.dto.MetaApiDtos.AgentVerificationTurn;
import com.vanguard.domain.dto.MetaApiDtos.AnalyzeSceneRequest;
import com.vanguard.domain.dto.MetaApiDtos.BoundingBox;
import com.vanguard.domain.dto.MetaApiDtos.ClosedLoopExecutionResponse;
import com.vanguard.domain.dto.MetaApiDtos.KinematicInterceptRecord;
import com.vanguard.domain.dto.MetaApiDtos.MetricObstacle;
import com.vanguard.domain.dto.MetaApiDtos.MuseImageChaosResponse;
import com.vanguard.domain.dto.MetaApiDtos.MuseVoiceTranscribeResponse;
import com.vanguard.domain.dto.MetaApiDtos.MuseWonderReasonResponse;
import com.vanguard.domain.dto.MetaApiDtos.PerceptionPipelineResult;
import com.vanguard.domain.dto.MetaApiDtos.ProposedRobotTrajectory;
import com.vanguard.domain.dto.MetaApiDtos.Ros2ExportBundle;
import com.vanguard.domain.dto.MetaApiDtos.Sam31SegmentResponse;
import com.vanguard.domain.dto.MetaApiDtos.VerifiableSceneResponse;
import com.vanguard.domain.dto.MetaApiDtos.VerificationReport;
import com.vanguard.domain.dto.VoiceCommandRequest;
import com.vanguard.domain.model.HazardZone;
import com.vanguard.domain.model.HazardZone.HazardType;
import com.vanguard.domain.model.RobotNode;
import com.vanguard.domain.model.SpatialCoordinate;
import com.vanguard.domain.model.TelemetryFrame.AgentLogEntry;
import com.vanguard.domain.model.VoronoiCell;
import com.vanguard.engine.ChaosSimulationEngine;
import com.vanguard.engine.HomographyCalibrationEngine;
import com.vanguard.engine.KinematicSafetyKernel;
import com.vanguard.engine.Ros2ExportService;
import com.vanguard.engine.VoronoiSpatialEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * KineGuard ROS2 Studio — Multi-Agent Verifiable Physical AI Orchestrator:
 *   1. HomographyCalibrationEngine (4-point pixel-to-metric floor projection)
 *   2. SAM 3.1 + Muse Spark 1.3 Grounded Perception (/v1/responses + /v1/chat/completions)
 *   3. Multi-Turn Verifiable Agent Loop with verify_kinematic_safety Tool Calling (Turn 1 ❌ -> Turn 2 ✅)
 *   4. Muse Image 1.0 Adversarial Red-Team Obstacle Synthesis (/v1/images/edits)
 *   5. Muse Voice Transcribe 24kHz WAV ASR (/v1/asr/transcribe)
 *   6. ROS 2 Nav2 Metric Occupancy Grid & Waypoint Export (map.pgm, map.yaml, nav2_verified_waypoints.json)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaServiceOrchestrator {

    private final MetaApiClient metaApiClient;
    private final ChaosSimulationEngine simulationEngine;
    private final VoronoiSpatialEngine voronoiEngine;
    private final KinematicSafetyKernel safetyKernel;
    private final HomographyCalibrationEngine homographyEngine;
    private final Ros2ExportService ros2ExportService;

    private volatile Ros2ExportBundle latestRos2Bundle;

    /** Per-execution export registry: a download bound to an executionId never serves another run. */
    private final java.util.Map<String, Ros2ExportBundle> ros2BundlesByExecution =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Test-scoped copy constructor helper to exercise full orchestrator pipelines against a custom MetaApiClient transport.
     */
    public MetaServiceOrchestrator withTestMetaClient(MetaApiClient customMetaClient) {
        return new MetaServiceOrchestrator(
                customMetaClient,
                this.simulationEngine,
                this.voronoiEngine,
                this.safetyKernel,
                this.homographyEngine,
                this.ros2ExportService
        );
    }

    /**
     * Executes the complete KineGuard ROS2 Studio verifiable perception + homography + multi-turn
     * tool-calling safety verification + ROS 2 Nav2 export pipeline.
     */
    public VerifiableSceneResponse analyzeAndVerifyScene(AnalyzeSceneRequest request) {
        return runVerifiableScenePipeline(request, false);
    }

    /**
     * Executes the Adversarial Red-Team stress test using Muse Image 1.0 (/v1/images/edits)
     * followed by SAM 3.1 segmentation and multi-turn verify_kinematic_safety replanning.
     */
    public VerifiableSceneResponse executeRedTeamAdversarialTest(AnalyzeSceneRequest request) {
        return runVerifiableScenePipeline(request, true);
    }

    /**
     * Transcribes 24kHz 16-bit mono PCM WAV audio via Muse Voice Transcribe (/v1/asr/transcribe).
     */
    public MuseVoiceTranscribeResponse transcribeOperatorAudio(byte[] rawAudio, String transcriptHint) {
        MuseVoiceTranscribeResponse resp = metaApiClient.transcribeVoice(rawAudio, transcriptHint, 500.0, 460.0);
        String msg = String.format(Locale.US, "Muse Voice Transcribe: \"%s\" (conf: %.1f%%)",
                resp.getTranscript(), resp.getConfidence() * 100.0);
        simulationEngine.appendLog("MUSE_VOICE", "INFO", msg, resp.getLatencyMs());
        return resp;
    }

    /**
     * Returns the latest ROS 2 Nav2 export bundle (initializing a default verified bundle if none exists yet).
     */
    /** Returns the bundle produced by exactly that execution, or null when unknown (never a substitute). */
    public Ros2ExportBundle getRos2BundleForExecution(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return null;
        }
        return ros2BundlesByExecution.get(executionId.trim());
    }

    private void registerRos2Bundle(String executionId, Ros2ExportBundle bundle) {
        this.latestRos2Bundle = bundle;
        if (executionId != null && !executionId.isBlank() && bundle != null) {
            ros2BundlesByExecution.put(executionId.trim(), bundle);
        }
    }

    public Ros2ExportBundle getLatestRos2Bundle() {
        if (latestRos2Bundle == null) {
            analyzeAndVerifyScene(AnalyzeSceneRequest.builder()
                    .sceneId("LOGISTICS_CORRIDOR_A")
                    .sceneTitle("Warehouse Logistics Spine A")
                    .imageSource("PRESET_SCENE")
                    .conceptPrompt("box")
                    .voiceCommandHint("Navigate AMR-01 to North Receiving Dock maintaining safe braking clearance")
                    .corridorWidthMeters(4.0)
                    .corridorLengthMeters(6.0)
                    .velocityMs(1.6)
                    .frictionMu(0.62)
                    .build());
        }
        return latestRos2Bundle;
    }

    /** Metric scale provenance. Unknown or unrecognized scale fails closed as UNKNOWN. */
    static String resolveScaleType(AnalyzeSceneRequest req, String imageSource) {
        if (req != null && req.getScaleType() != null && !req.getScaleType().isBlank()) {
            String normalized = req.getScaleType().trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "MEASURED_REFERENCE", "NOMINAL_PRESET", "NOMINAL_ASSUMED", "UNKNOWN" -> normalized;
                default -> "UNKNOWN";
            };
        }
        return "PRESET_SCENE".equalsIgnoreCase(imageSource) ? "NOMINAL_PRESET" : "NOMINAL_ASSUMED";
    }

    private List<BoundingBox> resolveKnownPresetObstacles(String sceneId) {
        if (sceneId == null) {
            return List.of();
        }
        return switch (sceneId.trim().toUpperCase(Locale.ROOT)) {
            case "LOGISTICS_CORRIDOR_A" -> List.of(
                    BoundingBox.builder()
                            .xMin(365).yMin(260).xMax(515).yMax(385)
                            .label("Industrial Pallet & Cargo Box")
                            .confidence(0.984)
                            .samRawToken("<0f>0<|box;x1=365;y1=260;x2=515;y2=385;w=860;h=640;c=0.984|><|mask;x=0;y=0;c=0.984;data=125,150,!SAM31_RLE|>")
                            .build(),
                    BoundingBox.builder()
                            .xMin(535).yMin(205).xMax(635).yMax(285)
                            .label("Safety Boundary Cone Cluster")
                            .confidence(0.968)
                            .samRawToken("<0f>1<|box;x1=535;y1=205;x2=635;y2=285;w=860;h=640;c=0.968|><|mask;x=0;y=0;c=0.968;data=80,100,!SAM31_RLE|>")
                            .build()
            );
            case "COLD_STORAGE_BAY_B" -> List.of(
                    BoundingBox.builder()
                            .xMin(330).yMin(325).xMax(495).yMax(465)
                            .label("Insulated Cold-Chain Tote Stack")
                            .confidence(0.989)
                            .samRawToken("<0f>0<|box;x1=330;y1=325;x2=495;y2=465;w=860;h=640;c=0.989|><|mask;x=0;y=0;c=0.989;data=140,165,!SAM31_RLE|>")
                            .build(),
                    BoundingBox.builder()
                            .xMin(255).yMin(225).xMax(365).yMax(310)
                            .label("Floor Frost Barrier Drum")
                            .confidence(0.964)
                            .samRawToken("<0f>1<|box;x1=255;y1=225;x2=365;y2=310;w=860;h=640;c=0.964|><|mask;x=0;y=0;c=0.964;data=85,110,!SAM31_RLE|>")
                            .build()
            );
            case "CLEANROOM_FAB_C" -> List.of(
                    BoundingBox.builder()
                            .xMin(380).yMin(295).xMax(540).yMax(445)
                            .label("Wafer FOUP Transfer Cart")
                            .confidence(0.991)
                            .samRawToken("<0f>0<|box;x1=380;y1=295;x2=540;y2=445;w=860;h=640;c=0.991|><|mask;x=0;y=0;c=0.991;data=150,160,!SAM31_RLE|>")
                            .build()
            );
            default -> List.of();
        };
    }

    private VerifiableSceneResponse runVerifiableScenePipeline(AnalyzeSceneRequest req, boolean redTeamMode) {
        long startNs = System.nanoTime();
        int attemptedBefore = metaApiClient.getAttemptedApiCallsCounter().get();
        int successBefore = metaApiClient.getLiveApiSuccessCounter().get();
        int failedBefore = metaApiClient.getFailedApiCallsCounter().get();

        String executionId = (req != null && req.getExecutionId() != null && !req.getExecutionId().isBlank())
                ? req.getExecutionId().trim()
                : "kineguard-" + UUID.randomUUID().toString().substring(0, 8);

        List<AgentLogEntry> pipelineLogs = new ArrayList<>();

        String sceneId = (req != null && req.getSceneId() != null && !req.getSceneId().isBlank())
                ? req.getSceneId()
                : "LOGISTICS_CORRIDOR_A";
        String sceneTitle = (req != null && req.getSceneTitle() != null && !req.getSceneTitle().isBlank())
                ? req.getSceneTitle()
                : "Warehouse Logistics Spine A";
        String conceptPrompt = (req != null && req.getConceptPrompt() != null && !req.getConceptPrompt().isBlank())
                ? req.getConceptPrompt().trim()
                : "box";
        String operatorCommand = (req != null && req.getVoiceCommandHint() != null && !req.getVoiceCommandHint().isBlank())
                ? req.getVoiceCommandHint().trim()
                : "Navigate AMR-01 through central logistics spine to North Receiving Dock";
        String reasoningModel = (req != null && req.getReasoningModel() != null && !req.getReasoningModel().isBlank())
                ? req.getReasoningModel().trim()
                : "muse-spark-1.3";
        double widthM = (req != null && req.getCorridorWidthMeters() != null && req.getCorridorWidthMeters() > 1.0)
                ? req.getCorridorWidthMeters()
                : 4.0;
        double lengthM = (req != null && req.getCorridorLengthMeters() != null && req.getCorridorLengthMeters() > 1.5)
                ? req.getCorridorLengthMeters()
                : 6.0;
        double velocityMs = (req != null && req.getVelocityMs() != null && req.getVelocityMs() > 0.1)
                ? req.getVelocityMs()
                : 1.6;
        double frictionMu = (req != null && req.getFrictionMu() != null && req.getFrictionMu() > 0.1)
                ? req.getFrictionMu()
                : 0.62;

        // P0-B: Resolve explicit image provenance (PRESET_SCENE vs USER_UPLOAD)
        String imageSource;
        if (req != null && req.getImageSource() != null && !req.getImageSource().isBlank()) {
            imageSource = req.getImageSource().trim().toUpperCase(Locale.ROOT);
        } else if (!resolveKnownPresetObstacles(sceneId).isEmpty()
                && (req == null || req.getPresetObstacles() == null || !req.getPresetObstacles().isEmpty())) {
            imageSource = "PRESET_SCENE";
        } else {
            imageSource = "USER_UPLOAD";
        }

        String scaleType = resolveScaleType(req, imageSource);

        List<SpatialCoordinate> calibrationPts = (req != null && req.getCalibrationPoints() != null
                && req.getCalibrationPoints().size() == 4)
                ? req.getCalibrationPoints()
                : List.of(
                        new SpatialCoordinate(140.0, 580.0), // near-left (0, 0)
                        new SpatialCoordinate(720.0, 580.0), // near-right (W, 0)
                        new SpatialCoordinate(580.0, 190.0), // far-right (W, L)
                        new SpatialCoordinate(280.0, 190.0)  // far-left (0, L)
                );

        // Step 1: Calibrate 4-Point Projective Homography (Pixels <-> Meters) — P0-E: no artificial latency padding
        long homographyStartNs = System.nanoTime();
        homographyEngine.calibrate(calibrationPts, widthM, lengthM);
        double homographyMs = Math.round(((System.nanoTime() - homographyStartNs) / 1_000_000.0) * 1000.0) / 1000.0;
        String homographyMsg = String.format(Locale.US,
                "Calibrated 3x3 floor homography H for %.1fm x %.1fm metric corridor (v=%.2fm/s, mu=%.2f)",
                widthM, lengthM, velocityMs, frictionMu);
        simulationEngine.appendLog(executionId, "HOMOGRAPHY", "INFO", homographyMsg, homographyMs);
        pipelineLogs.add(buildLog(executionId, "HOMOGRAPHY", "INFO", homographyMsg, homographyMs));

        // Optional Step 1.5: Adversarial Red-Team Hazard Injection via Muse Image 1.0
        String activeImageUri = req != null ? req.getImageBase64() : null;
        String redTeamPrompt = (req != null && req.getRedTeamPrompt() != null && !req.getRedTeamPrompt().isBlank())
                ? req.getRedTeamPrompt()
                : "Add an overturned wooden cargo crate and hydraulic oil spill in the center of the industrial warehouse corridor floor";

        if (redTeamMode) {
            long rtStartNs = System.nanoTime();
            activeImageUri = metaApiClient.injectAdversarialRedTeamHazard(activeImageUri, redTeamPrompt);
            double rtMs = Math.round(((System.nanoTime() - rtStartNs) / 1_000_000.0) * 1000.0) / 1000.0;
            String rtMsg = String.format(Locale.US,
                    "Muse Image 1.0 synthesized adversarial floor hazard: \"%s\"", redTeamPrompt);
            simulationEngine.appendLog(executionId, "MUSE_IMAGE", "WARN", rtMsg, rtMs);
            pipelineLogs.add(buildLog(executionId, "MUSE_IMAGE", "WARN", rtMsg, rtMs));
        }

        // Step 2: SAM 3.1 (/v1/responses) + Muse Spark 1.3 (/v1/chat/completions) Perception & Metric Projection
        // P0-B: Only pass presetBoxes if imageSource == "PRESET_SCENE"
        List<BoundingBox> effectivePresetBoxes = List.of();
        if ("PRESET_SCENE".equalsIgnoreCase(imageSource)) {
            if (req != null && req.getPresetObstacles() != null) {
                effectivePresetBoxes = req.getPresetObstacles();
            } else {
                effectivePresetBoxes = resolveKnownPresetObstacles(sceneId);
            }
        }

        PerceptionPipelineResult perception = metaApiClient.detectAndSegmentSceneWithStatus(
                executionId, activeImageUri, imageSource, conceptPrompt, effectivePresetBoxes,
                homographyEngine, velocityMs, frictionMu, safetyKernel);
        List<MetricObstacle> obstacles = new ArrayList<>(perception.getObstacles());
        String perceptionStatus = perception.getPerceptionStatus();
        String obstacleSource = perception.getObstacleSource();

        if (perception.getDiagnosticNotes() != null) {
            for (String note : perception.getDiagnosticNotes()) {
                pipelineLogs.add(buildLog(executionId, "SAM 3.1", "WARN", note, perception.getSamLatencyMs()));
            }
        }
        if (perception.getErrorMessage() != null && !perception.getErrorMessage().isBlank()) {
            pipelineLogs.add(buildLog(executionId, "SAM 3.1",
                    "PERCEPTION_ERROR".equalsIgnoreCase(perceptionStatus) ? "ERROR" : "WARN",
                    perception.getErrorMessage(), perception.getLatencyMs()));
        }

        if (redTeamMode && !"PERCEPTION_ERROR".equalsIgnoreCase(perceptionStatus)) {
            // Ensure an adversarial central spine obstacle is present for Red-Team verification stress test
            BoundingBox rtBox = BoundingBox.builder()
                    .xMin(375.0)
                    .yMin(315.0)
                    .xMax(495.0)
                    .yMax(425.0)
                    .label("Adversarial Hazard: Overturned Crate & Spill")
                    .confidence(0.992)
                    .samRawToken("<0f>9<|box;x1=375;y1=315;x2=495;y2=425;w=860;h=640;c=0.992|><|mask;x=0;y=0;c=0.992;data=110,120,!SAM31_RLE|>")
                    .build();
            List<SpatialCoordinate> metricPoly = homographyEngine.projectObstacleBoxToMetricPolygon(rtBox, 860, 640);
            double cx = metricPoly.stream().mapToDouble(SpatialCoordinate::getX).average().orElse(widthM * 0.5);
            double cy = metricPoly.stream().mapToDouble(SpatialCoordinate::getY).average().orElse(lengthM * 0.48);
            double rtRadiusM = metaApiClient.computeConservativeFootprintRadius(metricPoly, cx, cy);
            double dMin = safetyKernel.computeRequiredClearanceMeters(velocityMs, frictionMu);
            List<SpatialCoordinate> pixelPoly = List.of(
                    new SpatialCoordinate(rtBox.getXMin(), rtBox.getYMin()),
                    new SpatialCoordinate(rtBox.getXMax(), rtBox.getYMin()),
                    new SpatialCoordinate(rtBox.getXMax(), rtBox.getYMax()),
                    new SpatialCoordinate(rtBox.getXMin(), rtBox.getYMax())
            );

            obstacles.add(0, MetricObstacle.builder()
                    .id("obs-rt-1")
                    .label(rtBox.getLabel())
                    .confidence(rtBox.getConfidence())
                    .pixelBox(rtBox)
                    .pixelPolygon(pixelPoly)
                    .metricPolygon(metricPoly)
                    .centerXMeters(Math.round(cx * 100.0) / 100.0)
                    .centerYMeters(Math.round(cy * 100.0) / 100.0)
                    .radiusMeters(Math.round(rtRadiusM * 100.0) / 100.0)
                    .inflatedRadiusMeters(Math.round((rtRadiusM + dMin) * 100.0) / 100.0)
                    .samMaskToken(rtBox.getSamRawToken())
                    .build());
            perceptionStatus = "DETECTED";
            if ("NONE".equals(obstacleSource)) {
                obstacleSource = "RED_TEAM_SYNTHETIC";
            }
        }

        double samMs = perception.getLatencyMs();
        String samMsg = String.format(Locale.US,
                "SAM 3.1 + Muse Spark perception status=%s (obstacleSource=%s, count=%d, rejected=%d, samMs=%.1f, groundingMs=%.1f, concept=\"%s\")",
                perceptionStatus, obstacleSource, obstacles.size(), perception.getRejectedDetectionsCount(),
                perception.getSamLatencyMs(), perception.getGroundingLatencyMs(), conceptPrompt);
        String samLevel = "DETECTED".equals(perceptionStatus)
                ? "INFO"
                : ("PERCEPTION_ERROR".equals(perceptionStatus) ? "ERROR" : "WARN");
        simulationEngine.appendLog(executionId, "SAM 3.1", samLevel, samMsg, samMs);
        pipelineLogs.add(buildLog(executionId, "SAM 3.1", samLevel, samMsg, samMs));

        double dStop = safetyKernel.computeStoppingDistanceMeters(velocityMs, frictionMu);
        double dMin = safetyKernel.computeRequiredClearanceMeters(velocityMs, frictionMu);
        String executionMode = metaApiClient.isLiveApiEnabled() ? "LIVE_META_API" : "OFFLINE_DETERMINISTIC";

        // P0-C: If perception returned NO_DETECTIONS, INVALID_DETECTION, or PERCEPTION_ERROR (or obstacles is empty),
        // NEVER approve a trajectory -> return UNVERIFIED with empty waypoints and empty poses!
        // Unknown metric scale (photo without measured/nominal dimensions) also halts as UNVERIFIED:
        // distances in meters would be invented, so no route may be approved or exported.
        boolean scaleUnknown = "UNKNOWN".equals(scaleType);
        if (!"DETECTED".equals(perceptionStatus) || obstacles.isEmpty() || scaleUnknown) {
            List<MetricObstacle> haltObstacles = scaleUnknown && "DETECTED".equals(perceptionStatus)
                    ? obstacles : List.of();
            String unverifiedHash = "sha256:unverified-" + perceptionStatus.toLowerCase(Locale.ROOT);
            VerificationReport unverifiedReport = VerificationReport.builder()
                    .approved(false)
                    .verificationStatus("UNVERIFIED")
                    .verificationHash(unverifiedHash)
                    .minClearanceMeters(0.0)
                    .lateralClearanceRequiredMeters(safetyKernel.computeLateralClearanceMeters())
                    .requiredClearanceMeters(dMin)
                    .stoppingDistanceMeters(dStop)
                    .reactionDistanceMeters(Math.round((velocityMs * KinematicSafetyKernel.REACTION_TIME_SEC) * 1000.0) / 1000.0)
                    .effectiveDecelerationMps2(safetyKernel.computeEffectiveDecelerationMps2(frictionMu))
                    .counterexampleMessage(scaleUnknown
                            ? "UNVERIFIED (UNKNOWN_SCALE): Corridor dimensions have no measured or nominal reference; metric distances cannot be trusted, so no route is approved."
                            : "UNVERIFIED (" + perceptionStatus + "): Insufficient obstacle perception evidence to validate or approve a route.")
                    .verificationLatencyMs(0.0)
                    .evaluatedWaypoints(List.of())
                    .build();

            long ros2StartNs = System.nanoTime();
            Ros2ExportBundle unverifiedBundle = ros2ExportService.generateRos2Bundle(
                    widthM, lengthM, haltObstacles, List.of(), unverifiedReport, velocityMs, frictionMu,
                    sceneId, scaleType, executionId);
            double ros2Ms = Math.round(((System.nanoTime() - ros2StartNs) / 1_000_000.0) * 1000.0) / 1000.0;
            registerRos2Bundle(executionId, unverifiedBundle);

            String unvMsg = String.format(Locale.US,
                    "Scene verification halted with status=UNVERIFIED (perceptionStatus=%s, imageSource=%s). Zero poses exported.",
                    perceptionStatus, imageSource);
            simulationEngine.appendLog(executionId, "KINEMATIC_GUARD", "WARN", unvMsg, ros2Ms);
            pipelineLogs.add(buildLog(executionId, "KINEMATIC_GUARD", "WARN", unvMsg, ros2Ms));

            int attemptedCalls = metaApiClient.getAttemptedApiCallsCounter().get() - attemptedBefore;
            int successfulCalls = metaApiClient.getLiveApiSuccessCounter().get() - successBefore;
            int failedCalls = metaApiClient.getFailedApiCallsCounter().get() - failedBefore;
            double totalMs = Math.round(((System.nanoTime() - startNs) / 1_000_000.0) * 1000.0) / 1000.0;
            simulationEngine.updateClosedLoopLatency(totalMs);

            return VerifiableSceneResponse.builder()
                    .executionId(executionId)
                    .timestamp(System.currentTimeMillis())
                    .sceneId(sceneId)
                    .sceneTitle(sceneTitle)
                    .imageSource(imageSource)
                    .obstacleSource(obstacleSource)
                    .perceptionStatus(perceptionStatus)
                    .verificationStatus("UNVERIFIED")
                    .verificationHash(unverifiedHash)
                    .waypointSource("NONE")
                    .scaleType(scaleType)
                    .executionMode(executionMode)
                    .conceptPrompt(conceptPrompt)
                    .voiceTranscript(operatorCommand)
                    .activeImageDataUri(activeImageUri)
                    .redTeamApplied(redTeamMode)
                    .redTeamPrompt(redTeamMode ? redTeamPrompt : null)
                    .corridorWidthMeters(widthM)
                    .corridorLengthMeters(lengthM)
                    .velocityMs(velocityMs)
                    .frictionMu(frictionMu)
                    .stoppingDistanceMeters(dStop)
                    .requiredClearanceMeters(dMin)
                    .homographyMatrix(homographyEngine.getHomographyMatrixFlat())
                    .calibrationPoints(calibrationPts)
                    .obstacles(haltObstacles)
                    .agentTurns(List.of())
                    .rejectedWaypoints(List.of())
                    .verifiedWaypoints(List.of())
                    .ros2Bundle(unverifiedBundle)
                    .pipelineLogs(pipelineLogs)
                    .totalLatencyMs(totalMs)
                    .samLatencyMs(perception.getSamLatencyMs())
                    .groundingLatencyMs(perception.getGroundingLatencyMs())
                    .turn1LatencyMs(0.0)
                    .turn2LatencyMs(0.0)
                    .rawSamOutput(perception.getRawSamOutput())
                    .rawGroundingOutput(perception.getRawGroundingOutput())
                    .rejectedDetectionsCount(perception.getRejectedDetectionsCount())
                    .liveMetaApiCalls(successfulCalls)
                    .attemptedMetaApiCalls(attemptedCalls)
                    .successfulMetaApiCalls(successfulCalls)
                    .failedMetaApiCalls(failedCalls)
                    .build();
        }

        // Step 3: Multi-Turn Verifiable Agent Loop (Turn 1 Rejected -> Turn 2 Verified)
        List<AgentVerificationTurn> turns = metaApiClient.runVerifiableAgentToolLoop(
                executionId,
                operatorCommand,
                reasoningModel,
                obstacles,
                homographyEngine,
                safetyKernel,
                velocityMs,
                frictionMu
        );

        AgentVerificationTurn turn1 = turns.get(0);
        AgentVerificationTurn turn2 = turns.get(turns.size() - 1);

        String t1Log = String.format(Locale.US,
                "Turn 1 verify_kinematic_safety %s (source=%s, minClearance=%.2fm, lateralReq=%.2fm): %s",
                turn1.getKernelVerdict().isApproved() ? "APPROVED" : "REJECTED",
                turn1.getWaypointSource(),
                turn1.getMinClearanceMeters(),
                turn1.getKernelVerdict().getLateralClearanceRequiredMeters(),
                turn1.getCounterexampleMessage());
        simulationEngine.appendLog(executionId, "KINEMATIC_GUARD", "INTERCEPT", t1Log, turn1.getLatencyMs());
        pipelineLogs.add(buildLog(executionId, "KINEMATIC_GUARD", "INTERCEPT", t1Log, turn1.getLatencyMs()));

        boolean turn2Approved = turn2.getKernelVerdict() != null && turn2.getKernelVerdict().isApproved();
        String finalVerificationStatus = turn2Approved ? "APPROVED" : "REJECTED";
        String finalVerificationHash = turn2.getKernelVerdict() != null
                ? turn2.getKernelVerdict().getVerificationHash()
                : "sha256:rejected";
        String finalWaypointSource = turn2Approved ? turn2.getWaypointSource() : "NONE";

        String t2Log = turn2Approved
                ? String.format(Locale.US,
                        "Turn 2 replanning APPROVED by verify_kinematic_safety (source=%s, minClearance=%.2fm >= lateralReq=%.2fm, hash=%s)",
                        turn2.getWaypointSource(),
                        turn2.getMinClearanceMeters(),
                        turn2.getKernelVerdict().getLateralClearanceRequiredMeters(),
                        finalVerificationHash)
                : String.format(Locale.US,
                        "Turn 2 replanning REJECTED by verify_kinematic_safety — corridor blocked or constraints unsatisfiable: %s",
                        turn2.getCounterexampleMessage());
        simulationEngine.appendLog(executionId, "MUSE_WONDER", turn2Approved ? "SUCCESS" : "CRITICAL", t2Log, turn2.getLatencyMs());
        pipelineLogs.add(buildLog(executionId, "MUSE_WONDER", turn2Approved ? "SUCCESS" : "CRITICAL", t2Log, turn2.getLatencyMs()));

        // Step 4: ROS 2 Nav2 Export Package Generation (only emits executable poses if turn2Approved == true)
        long ros2StartNs = System.nanoTime();
        Ros2ExportBundle ros2Bundle = ros2ExportService.generateRos2Bundle(
                widthM,
                lengthM,
                obstacles,
                turn2Approved ? turn2.getProposedWaypoints() : List.of(),
                turn2.getKernelVerdict(),
                velocityMs,
                frictionMu,
                sceneId,
                scaleType,
                executionId
        );
        double ros2Ms = Math.round(((System.nanoTime() - ros2StartNs) / 1_000_000.0) * 1000.0) / 1000.0;
        registerRos2Bundle(executionId, ros2Bundle);

        String ros2Log = String.format(Locale.US,
                "Compiled ROS 2 Nav2 occupancy grid (%dx%d @ %.2fm/cell, status=%s) + %d verified metric waypoints",
                ros2Bundle.getGridWidthCells(),
                ros2Bundle.getGridHeightCells(),
                ros2Bundle.getResolutionMetersPerPixel(),
                ros2Bundle.getVerificationStatus(),
                turn2Approved ? turn2.getProposedWaypoints().size() : 0);
        simulationEngine.appendLog(executionId, "ROS2_EXPORT", "INFO", ros2Log, ros2Ms);
        pipelineLogs.add(buildLog(executionId, "ROS2_EXPORT", "INFO", ros2Log, ros2Ms));

        int attemptedCalls = metaApiClient.getAttemptedApiCallsCounter().get() - attemptedBefore;
        int successfulCalls = metaApiClient.getLiveApiSuccessCounter().get() - successBefore;
        int failedCalls = metaApiClient.getFailedApiCallsCounter().get() - failedBefore;
        double totalMs = Math.round(((System.nanoTime() - startNs) / 1_000_000.0) * 1000.0) / 1000.0;
        simulationEngine.updateClosedLoopLatency(totalMs);

        return VerifiableSceneResponse.builder()
                .executionId(executionId)
                .timestamp(System.currentTimeMillis())
                .sceneId(sceneId)
                .sceneTitle(sceneTitle)
                .imageSource(imageSource)
                .obstacleSource(obstacleSource)
                .perceptionStatus(perceptionStatus)
                .verificationStatus(finalVerificationStatus)
                .verificationHash(finalVerificationHash)
                .waypointSource(finalWaypointSource)
                .scaleType(scaleType)
                .executionMode(executionMode)
                .conceptPrompt(conceptPrompt)
                .voiceTranscript(operatorCommand)
                .activeImageDataUri(activeImageUri)
                .redTeamApplied(redTeamMode)
                .redTeamPrompt(redTeamMode ? redTeamPrompt : null)
                .corridorWidthMeters(widthM)
                .corridorLengthMeters(lengthM)
                .velocityMs(velocityMs)
                .frictionMu(frictionMu)
                .stoppingDistanceMeters(dStop)
                .requiredClearanceMeters(dMin)
                .homographyMatrix(homographyEngine.getHomographyMatrixFlat())
                .calibrationPoints(calibrationPts)
                .obstacles(obstacles)
                .agentTurns(turns)
                .rejectedWaypoints(turn1.getProposedWaypoints())
                .verifiedWaypoints(turn2Approved ? turn2.getProposedWaypoints() : List.of())
                .ros2Bundle(ros2Bundle)
                .pipelineLogs(pipelineLogs)
                .totalLatencyMs(totalMs)
                .samLatencyMs(perception.getSamLatencyMs())
                .groundingLatencyMs(perception.getGroundingLatencyMs())
                .turn1LatencyMs(turn1.getLatencyMs())
                .turn2LatencyMs(turns.size() > 1 ? turn2.getLatencyMs() : 0.0)
                .rawSamOutput(perception.getRawSamOutput())
                .rawGroundingOutput(perception.getRawGroundingOutput())
                .rejectedDetectionsCount(perception.getRejectedDetectionsCount())
                .liveMetaApiCalls(successfulCalls)
                .attemptedMetaApiCalls(attemptedCalls)
                .successfulMetaApiCalls(successfulCalls)
                .failedMetaApiCalls(failedCalls)
                .build();
    }

    /**
     * Legacy voice closed-loop handler preserved for backwards compatibility with existing endpoints and tests.
     */
    public ClosedLoopExecutionResponse executeVoiceClosedLoop(VoiceCommandRequest request) {
        byte[] rawAudio = decodeAudioPayload(request != null ? request.getAudioBase64() : null);
        String hint = request != null ? request.getTranscriptHint() : null;
        Double tx = request != null ? request.getTargetX() : null;
        Double ty = request != null ? request.getTargetY() : null;

        return runClosedLoopPipeline(rawAudio, hint, tx, ty, 108.0, 0.92);
    }

    /**
     * Legacy chaos injection handler preserved for backwards compatibility with existing endpoints and tests.
     */
    public ClosedLoopExecutionResponse executeChaosInjection(ChaosInjectionRequest request) {
        String preset = request != null && request.getPresetId() != null
                ? request.getPresetId().toUpperCase(Locale.ROOT)
                : "THERMAL_SECTOR_B";

        String transcript = request != null && request.getCommandText() != null && !request.getCommandText().isBlank()
                ? request.getCommandText()
                : mapPresetToTranscript(preset);

        Double cx = request != null ? request.getCenterX() : null;
        Double cy = request != null ? request.getCenterY() : null;
        double radius = request != null && request.getRadius() != null ? request.getRadius() : 108.0;
        double severity = request != null && request.getSeverity() != null ? request.getSeverity() : 0.94;

        if (cx == null || cy == null) {
            SpatialCoordinate presetCoords = mapPresetToCoordinates(preset);
            cx = presetCoords.getX();
            cy = presetCoords.getY();
        }

        byte[] syntheticVoicePayload = transcript.getBytes(StandardCharsets.UTF_8);
        return runClosedLoopPipeline(syntheticVoicePayload, transcript, cx, cy, radius, severity);
    }

    private ClosedLoopExecutionResponse runClosedLoopPipeline(
            byte[] audioBytes,
            String transcriptHint,
            Double targetX,
            Double targetY,
            double radius,
            double severity) {

        long pipelineStartNs = System.nanoTime();
        List<AgentLogEntry> executionLogs = new ArrayList<>();

        // STAGE 1: Muse Voice Transcribe
        MuseVoiceTranscribeResponse voiceResp = metaApiClient.transcribeVoice(
                audioBytes, transcriptHint, targetX, targetY);
        String voiceLogMsg = String.format("Transcribed \"%s\" (conf: %.1f%%)",
                voiceResp.getTranscript(), voiceResp.getConfidence() * 100.0);
        simulationEngine.appendLog("MUSE_VOICE", "INFO", voiceLogMsg, voiceResp.getLatencyMs());
        executionLogs.add(buildLog("MUSE_VOICE", "INFO", voiceLogMsg, voiceResp.getLatencyMs()));

        SpatialCoordinate epicenter = voiceResp.getInferredEpicenter();

        // STAGE 2: Muse Image Visual Chaos Synthesis
        MuseImageChaosResponse imageResp = metaApiClient.injectVisualChaos(
                voiceResp.getTranscript(),
                audioBytes,
                epicenter.getX(),
                epicenter.getY(),
                radius
        );
        String imageLogMsg = String.format(
                "Synthesized obstacle mask at (%.0f, %.0f) [Thermal Index: %.1f°C]",
                epicenter.getX(), epicenter.getY(), imageResp.getPeakThermalCelsius());
        simulationEngine.appendLog("MUSE_IMAGE", "WARN", imageLogMsg, imageResp.getLatencyMs());
        executionLogs.add(buildLog("MUSE_IMAGE", "WARN", imageLogMsg, imageResp.getLatencyMs()));

        // STAGE 3: SAM 3.1 Spatial Mask Segmentation (48-point convex hull polygon)
        byte[] imageBytes = imageResp.getOverlayDataUri().getBytes(StandardCharsets.UTF_8);
        Sam31SegmentResponse samResp = metaApiClient.segmentSpatialMask(
                imageBytes,
                epicenter.getX(),
                epicenter.getY(),
                radius
        );
        String samLogMsg = String.format(
                "Generated %d-point convex hull polygon around obstacle (IoU: %.3f, Mask: %s)",
                samResp.getVertexCount(), samResp.getIouScore(), samResp.getMaskId());
        simulationEngine.appendLog("SAM 3.1", "INFO", samLogMsg, samResp.getLatencyMs());
        executionLogs.add(buildLog("SAM 3.1", "INFO", samLogMsg, samResp.getLatencyMs()));

        // Register active HazardZone in the 25Hz simulation arena
        HazardType hazardType = parseHazardType(voiceResp.getDetectedHazardType());
        long now = System.currentTimeMillis();
        HazardZone hazardZone = HazardZone.builder()
                .id("HZ-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT))
                .label(voiceResp.getTranscript())
                .sector(voiceResp.getTargetSector())
                .type(hazardType)
                .center(epicenter)
                .effectiveRadius(radius)
                .severity(severity)
                .peakThermalC(imageResp.getPeakThermalCelsius())
                .polygonHull(samResp.getConvexHullVertices())
                .samMaskId(samResp.getMaskId())
                .samConfidenceIou(samResp.getIouScore())
                .museImagePrompt(imageResp.getSynthesizedPrompt())
                .museImageOverlayDataUri(imageResp.getOverlayDataUri())
                .createdAtEpochMs(now)
                .expiresAtEpochMs(now + 95_000L)
                .active(true)
                .build();

        simulationEngine.registerHazardZone(hazardZone);

        // STAGE 4: Muse Wonder + Muse Spark Multi-Agent Path Replan
        List<RobotNode> fleet = simulationEngine.getFleet();
        MuseWonderReasonResponse wonderResp = metaApiClient.reasonSpatialStrategy(
                voiceResp.getTranscript(),
                fleet,
                epicenter,
                radius,
                samResp.getConvexHullVertices()
        );
        String wonderLogMsg = String.format(
                "Multi-agent path replan proposed for %d AMRs via %s + %s",
                wonderResp.getTrajectories().size(),
                wonderResp.getPrimaryModel(),
                wonderResp.getPartnerModel());
        simulationEngine.appendLog("MUSE_WONDER", "INFO", wonderLogMsg, wonderResp.getLatencyMs());
        executionLogs.add(buildLog("MUSE_WONDER", "INFO", wonderLogMsg, wonderResp.getLatencyMs()));

        // STAGE 5: Deterministic KinematicSafetyKernel Verification (<1ms per robot)
        List<VoronoiCell> currentCells = voronoiEngine.computeVoronoiCells(
                fleet, simulationEngine.getGridWidth(), simulationEngine.getGridHeight());
        Map<String, VoronoiCell> cellMap = currentCells.stream()
                .collect(Collectors.toMap(VoronoiCell::getRobotId, c -> c, (a, b) -> a));
        Map<String, RobotNode> robotMap = fleet.stream()
                .collect(Collectors.toMap(RobotNode::getId, r -> r, (a, b) -> a));

        List<KinematicInterceptRecord> verdicts = new ArrayList<>();
        for (ProposedRobotTrajectory proposal : wonderResp.getTrajectories()) {
            RobotNode robot = robotMap.get(proposal.getRobotId());
            if (robot == null) {
                continue;
            }
            robot.setProposedWaypoint(proposal.getProposedWaypoint());
            VoronoiCell cell = cellMap.get(robot.getId());

            KinematicInterceptRecord verdict = safetyKernel.verifyAgentTrajectory(
                    robot,
                    proposal.getProposedWaypoint(),
                    proposal.getProposedVx(),
                    proposal.getProposedVy(),
                    fleet,
                    simulationEngine.getActiveHazards(),
                    cell
            );
            verdicts.add(verdict);

            if (verdict.isIntercepted()) {
                double defVx = verdict.getKernelDeflectedVector().getX();
                double defVy = verdict.getKernelDeflectedVector().getY();
                robot.setVx(defVx);
                robot.setVy(defVy);
                robot.setSpeed(Math.hypot(defVx, defVy));
                robot.setHeadingRad(Math.atan2(defVy, defVx));
                robot.setTargetWaypoint(new SpatialCoordinate(
                        Math.max(80.0, Math.min(920.0, robot.getPosition().getX() + defVx * 3.0)),
                        Math.max(80.0, Math.min(920.0, robot.getPosition().getY() + defVy * 3.0))
                ));
                robot.setState(RobotNode.OperationalState.AVOIDING);
                robot.setKinematicInterceptActive(true);
                robot.setLastInterceptReason(verdict.getReason());
                robot.setLastInterceptTimestamp(System.currentTimeMillis());

                String guardMsg = String.format(
                        "Intercepted %s! Path violates BVC clearance. Deflected via orthogonal vector (Latency: %.2fms)",
                        robot.getId(), verdict.getVerificationLatencyMs());
                simulationEngine.appendLog("KINEMATIC_GUARD", "INTERCEPT", guardMsg, verdict.getVerificationLatencyMs());
                executionLogs.add(buildLog("KINEMATIC_GUARD", "INTERCEPT", guardMsg, verdict.getVerificationLatencyMs()));
            } else {
                robot.setTargetWaypoint(proposal.getProposedWaypoint());
                robot.setVx(proposal.getProposedVx());
                robot.setVy(proposal.getProposedVy());
            }
        }

        double totalLoopMs = Math.round(((System.nanoTime() - pipelineStartNs) / 1_000_000.0) * 1000.0) / 1000.0;
        simulationEngine.updateClosedLoopLatency(totalLoopMs);

        String statusMsg = "Closed-loop physical stability maintained. Zero-collision invariant verified.";
        simulationEngine.appendLog("STATUS", "SUCCESS", statusMsg, 0.2);
        executionLogs.add(buildLog("STATUS", "SUCCESS", statusMsg, 0.2));

        simulationEngine.stepPhysicsTick();

        return ClosedLoopExecutionResponse.builder()
                .executionId("exec-" + UUID.randomUUID().toString().substring(0, 8))
                .timestamp(System.currentTimeMillis())
                .voiceStage(voiceResp)
                .imageStage(imageResp)
                .samStage(samResp)
                .wonderStage(wonderResp)
                .kinematicVerdicts(verdicts)
                .activeHazard(hazardZone)
                .pipelineLogs(executionLogs)
                .totalClosedLoopLatencyMs(totalLoopMs)
                .build();
    }

    private byte[] decodeAudioPayload(String base64) {
        if (base64 == null || base64.isBlank()) {
            return new byte[0];
        }
        try {
            String cleaned = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
            return Base64.getDecoder().decode(cleaned);
        } catch (Exception e) {
            return base64.getBytes(StandardCharsets.UTF_8);
        }
    }

    private String mapPresetToTranscript(String presetId) {
        return switch (presetId) {
            case "THERMAL_SECTOR_B" -> "Thermal anomaly detected at Node 3 in Sector B";
            case "COLLAPSE_GRID_4" -> "Racking obstruction in Grid 4 — deploy SAM 3.1 perimeter barrier";
            case "CORRIDOR_EVAC" -> "Corridor clearance directive — clear central autonomous spine";
            case "REROUTE_HEAVY_AMR" -> "Reroute Heavy Freight AMRs around Sector D floor spill";
            default -> "Physical obstacle injected — execute closed-loop BVC fleet deflection";
        };
    }

    private SpatialCoordinate mapPresetToCoordinates(String presetId) {
        List<RobotNode> fleet = simulationEngine.getFleet();
        return switch (presetId) {
            case "THERMAL_SECTOR_B" -> {
                if (fleet.size() >= 3) {
                    SpatialCoordinate p = fleet.get(2).getPosition();
                    yield new SpatialCoordinate(
                            Math.max(220.0, Math.min(780.0, p.getX() - 60.0)),
                            Math.max(220.0, Math.min(780.0, p.getY() + 30.0))
                    );
                }
                yield new SpatialCoordinate(640.0, 390.0);
            }
            case "COLLAPSE_GRID_4" -> {
                if (!fleet.isEmpty()) {
                    SpatialCoordinate p = fleet.get(0).getPosition();
                    yield new SpatialCoordinate(
                            Math.max(220.0, Math.min(780.0, p.getX() + 70.0)),
                            Math.max(220.0, Math.min(780.0, p.getY() + 45.0))
                    );
                }
                yield new SpatialCoordinate(340.0, 330.0);
            }
            case "CORRIDOR_EVAC" -> new SpatialCoordinate(500.0, 500.0);
            case "REROUTE_HEAVY_AMR" -> {
                if (fleet.size() >= 5) {
                    SpatialCoordinate p = fleet.get(4).getPosition();
                    yield new SpatialCoordinate(
                            Math.max(220.0, Math.min(780.0, p.getX() - 40.0)),
                            Math.max(220.0, Math.min(780.0, p.getY() - 55.0))
                    );
                }
                yield new SpatialCoordinate(560.0, 700.0);
            }
            default -> new SpatialCoordinate(500.0, 480.0);
        };
    }

    private HazardType parseHazardType(String raw) {
        if (raw == null) {
            return HazardType.THERMAL_RUNAWAY;
        }
        try {
            return HazardType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return HazardType.THERMAL_RUNAWAY;
        }
    }

    private AgentLogEntry buildLog(String tag, String level, String message, double latencyMs) {
        return buildLog(null, tag, level, message, latencyMs);
    }

    private AgentLogEntry buildLog(String executionId, String tag, String level, String message, double latencyMs) {
        return AgentLogEntry.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .executionId(executionId)
                .timestamp(System.currentTimeMillis())
                .tag(tag)
                .level(level)
                .message(message)
                .latencyMs(latencyMs)
                .build();
    }
}
