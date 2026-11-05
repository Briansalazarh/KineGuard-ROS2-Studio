package com.vanguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanguard.domain.dto.ChaosInjectionRequest;
import com.vanguard.domain.dto.MetaApiDtos.AgentVerificationTurn;
import com.vanguard.domain.dto.MetaApiDtos.AnalyzeSceneRequest;
import com.vanguard.domain.dto.MetaApiDtos.BoundingBox;
import com.vanguard.domain.dto.MetaApiDtos.ClosedLoopExecutionResponse;
import com.vanguard.domain.dto.MetaApiDtos.MetricObstacle;
import com.vanguard.domain.dto.MetaApiDtos.MetricWaypoint;
import com.vanguard.domain.dto.MetaApiDtos.Ros2ExportBundle;
import com.vanguard.domain.dto.MetaApiDtos.VerifiableSceneResponse;
import com.vanguard.domain.dto.MetaApiDtos.VerificationReport;
import com.vanguard.domain.dto.VoiceCommandRequest;
import com.vanguard.domain.model.RobotNode;
import com.vanguard.domain.model.SpatialCoordinate;
import com.vanguard.domain.model.VoronoiCell;
import com.vanguard.engine.ChaosSimulationEngine;
import com.vanguard.engine.HomographyCalibrationEngine;
import com.vanguard.engine.KinematicSafetyKernel;
import com.vanguard.engine.Ros2ExportService;
import com.vanguard.engine.VoronoiSpatialEngine;
import com.vanguard.controller.VoiceChaosController;
import com.vanguard.meta.MetaApiClient;
import com.vanguard.meta.MetaServiceOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "meta.api.key=mock_mode")
class VanguardEngineTests {

    @Autowired
    private VoronoiSpatialEngine voronoiEngine;

    @Autowired
    private KinematicSafetyKernel safetyKernel;

    @Autowired
    private ChaosSimulationEngine simulationEngine;

    @Autowired
    private MetaServiceOrchestrator orchestrator;

    @Autowired
    private HomographyCalibrationEngine homographyEngine;

    @Autowired
    private Ros2ExportService ros2ExportService;

    @Autowired
    private MetaApiClient metaApiClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testMetricCoordinateJsonContract() throws Exception {
        MetricWaypoint waypoint = MetricWaypoint.builder().id("W0")
                .xMeters(2.0).yMeters(0.4).uPixel(430).vPixel(560).targetSpeedMs(1.6).build();
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(waypoint));
        assertEquals(2.0, json.path("xMeters").asDouble(Double.NaN));
        assertEquals(0.4, json.path("yMeters").asDouble(Double.NaN));
        assertEquals(430, json.path("uPixel").asDouble(Double.NaN));
        assertEquals(560, json.path("vPixel").asDouble(Double.NaN));
        assertEquals(6, json.size(), "Must not emit duplicate lowercase coordinate keys");
        assertEquals(waypoint, objectMapper.treeToValue(json, MetricWaypoint.class));

        BoundingBox box = BoundingBox.builder().xMin(10).yMin(20).xMax(30).yMax(40).build();
        JsonNode boxJson = objectMapper.valueToTree(box);
        assertEquals(10, boxJson.path("xMin").asDouble(Double.NaN));
        assertEquals(20, boxJson.path("yMin").asDouble(Double.NaN));
        assertEquals(30, boxJson.path("xMax").asDouble(Double.NaN));
        assertEquals(40, boxJson.path("yMax").asDouble(Double.NaN));
        assertEquals(7, boxJson.size());
        assertEquals(box, objectMapper.treeToValue(boxJson, BoundingBox.class));
    }

    @Test
    void testCollinearCalibrationPointsRejected() {
        // P0, P1, P2 lie on the same horizontal line y = 580 -> must be rejected
        List<SpatialCoordinate> collinearPts = List.of(
                new SpatialCoordinate(140.0, 580.0),
                new SpatialCoordinate(430.0, 580.0),
                new SpatialCoordinate(720.0, 580.0),
                new SpatialCoordinate(280.0, 190.0)
        );
        assertThrows(IllegalArgumentException.class,
                () -> homographyEngine.calibrate(collinearPts, 4.0, 6.0),
                "Collinear calibration points must be explicitly rejected");
    }

    @Test
    void testSelfIntersectingBowtieCalibrationRejected() {
        // Crossed order (P1 and P2 swapped -> self-intersecting bowtie polygon)
        List<SpatialCoordinate> bowtiePts = List.of(
                new SpatialCoordinate(140.0, 580.0),
                new SpatialCoordinate(580.0, 190.0),
                new SpatialCoordinate(720.0, 580.0),
                new SpatialCoordinate(280.0, 190.0)
        );
        assertThrows(IllegalArgumentException.class,
                () -> homographyEngine.calibrate(bowtiePts, 4.0, 6.0),
                "Self-intersecting bowtie quadrilateral must be explicitly rejected");
    }

    @Test
    void testHomographyIndependentGroundTruthCheckpoints() {
        List<SpatialCoordinate> floorCorners = List.of(
                new SpatialCoordinate(140.0, 580.0), // P0: near-left  -> (0.0m, 0.0m)
                new SpatialCoordinate(720.0, 580.0), // P1: near-right -> (4.0m, 0.0m)
                new SpatialCoordinate(580.0, 190.0), // P2: far-right  -> (4.0m, 6.0m)
                new SpatialCoordinate(280.0, 190.0)  // P3: far-left   -> (0.0m, 6.0m)
        );
        homographyEngine.calibrate(floorCorners, 4.0, 6.0);

        // Evaluate 3 independent ground-truth checkpoints not used in calibration.
        // Note: The pixel coordinate for the metric center (2.0m, 3.0m) is derived purely from projective geometry
        // (the intersection of trapezoid diagonals P0->P2 and P1->P3 is at u = 430, v = 7105/22 = 322.954545),
        // without calling homographyEngine.metricToPixel().
        double analyticalDiagonalIntersectionV = 7105.0 / 22.0;
        List<SpatialCoordinate> groundTruthMeters = List.of(
                new SpatialCoordinate(2.0, 0.0), // bottom midpoint
                new SpatialCoordinate(2.0, 6.0), // top midpoint
                new SpatialCoordinate(2.0, 3.0)  // corridor center (diagonal intersection)
        );
        List<SpatialCoordinate> independentPixels = List.of(
                new SpatialCoordinate(430.0, 580.0),
                new SpatialCoordinate(430.0, 190.0),
                new SpatialCoordinate(430.0, analyticalDiagonalIntersectionV)
        );

        double rmsErrorMeters = homographyEngine.evaluateIndependentCheckpointsErrorMeters(
                independentPixels, groundTruthMeters);
        assertTrue(rmsErrorMeters < 0.01,
                "Independent ground-truth checkpoint RMS error must be < 0.01m, got: " + rmsErrorMeters);
    }

    @Test
    void testKinematicSafetyKernelRejectsNaNInfiniteAndOutOfCorridorBounds() {
        MetricObstacle obs = MetricObstacle.builder()
                .id("obs-1").label("Pallet").centerXMeters(2.0).centerYMeters(3.0).radiusMeters(0.40).build();

        // 1. NaN coordinate
        List<MetricWaypoint> nanPath = List.of(
                MetricWaypoint.builder().id("W0").xMeters(2.0).yMeters(0.5).targetSpeedMs(1.5).build(),
                MetricWaypoint.builder().id("W1").xMeters(Double.NaN).yMeters(3.0).targetSpeedMs(1.5).build()
        );
        VerificationReport nanReport = safetyKernel.verifyTrajectory(nanPath, List.of(obs), 1.5, 0.62);
        assertFalse(nanReport.isApproved(), "NaN waypoint must be rejected");
        assertTrue(nanReport.getCounterexampleMessage().contains("NAN_OR_INFINITE_WAYPOINT"));

        // 2. Negative velocity
        List<MetricWaypoint> normalPath = List.of(
                MetricWaypoint.builder().id("W0").xMeters(0.8).yMeters(0.5).targetSpeedMs(1.5).build(),
                MetricWaypoint.builder().id("W1").xMeters(0.8).yMeters(5.0).targetSpeedMs(1.5).build()
        );
        VerificationReport negVelReport = safetyKernel.verifyTrajectory(normalPath, List.of(obs), -1.0, 0.62);
        assertFalse(negVelReport.isApproved(), "Negative velocity must be rejected");

        // 3. Waypoint outside corridor wall (x = 0.10m < r_robot = 0.32m)
        List<MetricWaypoint> wallCollisionPath = List.of(
                MetricWaypoint.builder().id("W0").xMeters(0.10).yMeters(0.5).targetSpeedMs(1.5).build(),
                MetricWaypoint.builder().id("W1").xMeters(0.80).yMeters(5.0).targetSpeedMs(1.5).build()
        );
        VerificationReport wallReport = safetyKernel.verifyTrajectory(wallCollisionPath, List.of(obs), 1.5, 0.62);
        assertFalse(wallReport.isApproved(), "Waypoint colliding with corridor wall must be rejected");
        assertTrue(wallReport.getCounterexampleMessage().contains("CORRIDOR_WALL_COLLISION"));
    }

    @Test
    void testKinematicSafetyKernelFullLongitudinalStoppingVsLateralClearance() {
        // Verify full longitudinal stopping distance formula without arbitrary 0.5 factor:
        // At v = 1.6 m/s, mu = 0.62 -> a_eff = min(3.5, 0.62 * 9.81) = 3.5 m/s^2
        // d_stop = 1.6 * 0.15 + 1.6^2 / (2 * 3.5) = 0.24 + 0.3657 = 0.606 m
        double dStop = safetyKernel.computeStoppingDistanceMeters(1.6, 0.62);
        assertEquals(0.606, dStop, 0.01);

        // Required longitudinal clearance = r_robot (0.32) + d_stop (0.606) + margin (0.15) = 1.076 m (NO 0.5 factor!)
        double dReq = safetyKernel.computeRequiredClearanceMeters(1.6, 0.62);
        assertEquals(1.076, dReq, 0.01);

        MetricObstacle centralObstacle = MetricObstacle.builder()
                .id("obs-1")
                .label("Wooden Pallet")
                .centerXMeters(2.0)
                .centerYMeters(3.0)
                .radiusMeters(0.42)
                .build();

        // Unsafe centerline trajectory passing directly over (2.0m, 3.0m) -> REJECTED
        List<MetricWaypoint> unsafePath = List.of(
                MetricWaypoint.builder().id("W0").xMeters(2.0).yMeters(0.5).targetSpeedMs(1.6).build(),
                MetricWaypoint.builder().id("W1").xMeters(2.0).yMeters(3.0).targetSpeedMs(1.6).build(),
                MetricWaypoint.builder().id("W2").xMeters(2.0).yMeters(5.5).targetSpeedMs(1.6).build()
        );

        VerificationReport rejectedReport = safetyKernel.verifyTrajectory(
                unsafePath, List.of(centralObstacle), 1.6, 0.62);
        assertFalse(rejectedReport.isApproved(), "Centerline trajectory colliding with obstacle must be REJECTED");
        assertEquals("REJECTED", rejectedReport.getVerificationStatus());

        // Safe deflected trajectory passing along x = 0.70m (lateral distance to obstacle edge = 1.30 - 0.42 = 0.88m >= 0.47m)
        List<MetricWaypoint> safePath = List.of(
                MetricWaypoint.builder().id("W0").xMeters(2.0).yMeters(0.5).targetSpeedMs(1.6).build(),
                MetricWaypoint.builder().id("W1").xMeters(0.70).yMeters(2.0).targetSpeedMs(1.6).build(),
                MetricWaypoint.builder().id("W2").xMeters(0.70).yMeters(4.2).targetSpeedMs(1.6).build(),
                MetricWaypoint.builder().id("W3").xMeters(2.0).yMeters(5.5).targetSpeedMs(1.6).build()
        );

        VerificationReport approvedReport = safetyKernel.verifyTrajectory(
                safePath, List.of(centralObstacle), 1.6, 0.62);
        assertTrue(approvedReport.isApproved(), "Deflected trajectory clearing obstacle must be APPROVED");
        assertEquals("APPROVED", approvedReport.getVerificationStatus());
    }

    @Test
    void testVerificationHashInvalidatedWhenTrajectoryOrCalibrationMutates() {
        MetricObstacle obs = MetricObstacle.builder()
                .id("obs-1").label("Pallet").centerXMeters(2.2).centerYMeters(3.0).radiusMeters(0.38).build();

        List<MetricWaypoint> approvedPath = List.of(
                MetricWaypoint.builder().id("W0").xMeters(0.75).yMeters(0.5).targetSpeedMs(1.4).build(),
                MetricWaypoint.builder().id("W1").xMeters(0.75).yMeters(5.5).targetSpeedMs(1.4).build()
        );
        List<Double> hMatrix = List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);

        VerificationReport report = safetyKernel.verifyTrajectory(
                approvedPath, List.of(obs), 1.4, 0.65, 4.0, 6.0, hMatrix);
        assertTrue(report.isApproved());
        assertTrue(safetyKernel.isVerificationReportValidForState(
                report, approvedPath, List.of(obs), 1.4, 0.65, 4.0, 6.0, hMatrix));

        // Mutate a waypoint after approval -> verificationHash must become invalid!
        List<MetricWaypoint> tamperedPath = List.of(
                MetricWaypoint.builder().id("W0").xMeters(0.75).yMeters(0.5).targetSpeedMs(1.4).build(),
                MetricWaypoint.builder().id("W1").xMeters(1.85).yMeters(5.5).targetSpeedMs(1.4).build()
        );
        assertFalse(safetyKernel.isVerificationReportValidForState(
                report, tamperedPath, List.of(obs), 1.4, 0.65, 4.0, 6.0, hMatrix),
                "Modifying waypoints after approval must invalidate the verification report");

        // Mutate homography calibration matrix -> verificationHash must become invalid!
        List<Double> tamperedH = List.of(1.25, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);
        assertFalse(safetyKernel.isVerificationReportValidForState(
                report, approvedPath, List.of(obs), 1.4, 0.65, 4.0, 6.0, tamperedH),
                "Changing homography calibration after approval must invalidate the verification report");
    }

    @Test
    void testCompletelyBlockedCorridorRemainsRejectedOnBothTurns() {
        // Calibrate a 4.0m x 6.0m corridor
        homographyEngine.calibrate(List.of(
                new SpatialCoordinate(140.0, 580.0),
                new SpatialCoordinate(720.0, 580.0),
                new SpatialCoordinate(580.0, 190.0),
                new SpatialCoordinate(280.0, 190.0)
        ), 4.0, 6.0);

        // Barricade of 3 large obstacles blocking the entire 4.0m width at y = 3.0m
        List<MetricObstacle> wallOfObstacles = List.of(
                MetricObstacle.builder().id("obs-left").label("Barricade L").centerXMeters(0.85).centerYMeters(3.0).radiusMeters(0.75).build(),
                MetricObstacle.builder().id("obs-mid").label("Barricade C").centerXMeters(2.00).centerYMeters(3.0).radiusMeters(0.75).build(),
                MetricObstacle.builder().id("obs-right").label("Barricade R").centerXMeters(3.15).centerYMeters(3.0).radiusMeters(0.75).build()
        );

        List<AgentVerificationTurn> turns = metaApiClient.runVerifiableAgentToolLoop(
                "Attempt to cross completely blocked corridor",
                "muse-spark-1.3",
                wallOfObstacles,
                homographyEngine,
                safetyKernel,
                1.6,
                0.62
        );

        assertEquals(2, turns.size());
        assertFalse(turns.get(0).getKernelVerdict().isApproved(), "Turn 1 must be REJECTED");
        assertFalse(turns.get(1).getKernelVerdict().isApproved(),
                "Turn 2 MUST ALSO BE REJECTED when corridor is physically blocked — never fabricate an approval!");
        assertEquals("REJECTED", turns.get(1).getKernelVerdict().getVerificationStatus());

        // Verify Ros2ExportService refuses to export executable poses for a REJECTED trajectory
        Ros2ExportBundle rejectedBundle = ros2ExportService.generateRos2Bundle(
                4.0, 6.0, wallOfObstacles, turns.get(1).getProposedWaypoints(),
                turns.get(1).getKernelVerdict(), 1.6, 0.62);
        assertEquals("REJECTED", rejectedBundle.getVerificationStatus());
        assertTrue(rejectedBundle.getNav2WaypointsJson().contains("\"kinematic_approved\": false"));
        assertTrue(rejectedBundle.getNav2WaypointsJson().contains("\"poses\": [\n  ]"),
                "Rejected ROS 2 bundle must contain an empty poses array");
    }

    @Test
    void testToolCallsParserExtractsWaypointsFromCompletionFixture() {
        homographyEngine.calibrate(List.of(
                new SpatialCoordinate(140.0, 580.0),
                new SpatialCoordinate(720.0, 580.0),
                new SpatialCoordinate(580.0, 190.0),
                new SpatialCoordinate(280.0, 190.0)
        ), 4.0, 6.0);

        Map<String, Object> completionFixture = Map.of(
                "choices", List.of(
                        Map.of(
                                "message", Map.of(
                                        "role", "assistant",
                                        "tool_calls", List.of(
                                                Map.of(
                                                        "id", "call_fixture_meta_991",
                                                        "type", "function",
                                                        "function", Map.of(
                                                                "name", "verify_kinematic_safety",
                                                                "arguments", "{\"reasoning\":\"Left lane bypass\",\"waypoints\":[{\"id\":\"W0\",\"xMeters\":2.0,\"yMeters\":0.5},{\"id\":\"W1\",\"xMeters\":0.75,\"yMeters\":3.0},{\"id\":\"W2\",\"xMeters\":2.0,\"yMeters\":5.5}]}"
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        String extractedId = metaApiClient.extractToolCallId(completionFixture);
        assertEquals("call_fixture_meta_991", extractedId);

        List<MetricWaypoint> extractedWps = metaApiClient.extractToolCallWaypoints(
                completionFixture, homographyEngine, 1.5);
        assertNotNull(extractedWps);
        assertEquals(3, extractedWps.size());
        assertEquals(0.75, extractedWps.get(1).getXMeters(), 0.001);
        assertEquals(3.0, extractedWps.get(1).getYMeters(), 0.001);
    }

    @Test
    void testVerifiableScenePipelineAndRos2TrinaryCompliance() {
        AnalyzeSceneRequest request = AnalyzeSceneRequest.builder()
                .sceneId("LOGISTICS_CORRIDOR_A")
                .conceptPrompt("box")
                .voiceCommandHint("Navigate AMR-01 to North Receiving Dock")
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse response = orchestrator.analyzeAndVerifyScene(request);
        assertNotNull(response);
        assertEquals(9, response.getHomographyMatrix().size());
        assertFalse(response.getObstacles().isEmpty(), "SAM 3.1 + Muse Spark must detect corridor obstacles");
        assertEquals(2, response.getAgentTurns().size(), "Must execute 2-turn verifiable tool-calling loop");
        assertFalse(response.getAgentTurns().get(0).getKernelVerdict().isApproved(), "Turn 1 naive route must be REJECTED");
        assertTrue(response.getAgentTurns().get(1).getKernelVerdict().isApproved(),
                "Turn 2 replanned route must be APPROVED, but got: " + response.getAgentTurns().get(1).getCounterexampleMessage());
        assertNotEquals(
                response.getAgentTurns().get(0).getKernelVerdict().getVerificationHash(),
                response.getAgentTurns().get(1).getKernelVerdict().getVerificationHash());

        Ros2ExportBundle bundle = response.getRos2Bundle();
        assertNotNull(bundle);
        assertEquals("APPROVED", bundle.getVerificationStatus());
        assertTrue(bundle.getMapPgmAscii().startsWith("P2"), "ROS 2 map.pgm must use standard ASCII P2 PGM header");
        assertFalse(bundle.getMapPgmAscii().contains(" 128 "), "Trinary map.pgm must not use 128 for inflation");
        assertTrue(bundle.getMapPgmAscii().contains(" 205 "), "Trinary map.pgm must mark monocular occlusion zones as 205 (UNKNOWN)");
        assertTrue(bundle.getNav2CostmapParamsYaml().contains("nav2_costmap_2d::InflationLayer"),
                "Inflation must be configured via Nav2 InflationLayer parameters");
        assertTrue(bundle.getNav2WaypointsJson().contains("\"orientation\""),
                "Waypoints JSON must include PoseStamped orientation quaternions");
    }

    @Test
    void testVoronoiAndBufferedVoronoiCells() {
        List<RobotNode> fleet = simulationEngine.getFleet();
        assertEquals(6, fleet.size(), "Fleet must have 6 Autonomous Mobile Robots");

        List<VoronoiCell> cells = voronoiEngine.computeVoronoiCells(fleet, 1000.0, 1000.0);
        assertEquals(6, cells.size(), "Must generate 6 Voronoi cells");

        for (VoronoiCell cell : cells) {
            assertTrue(cell.getRawVertices().size() >= 3, "Raw Voronoi polygon must have >= 3 vertices");
            assertTrue(cell.getBufferedVertices().size() >= 3, "Buffered Voronoi Cell must have >= 3 vertices");
            assertTrue(cell.getAreaSqUnits() > 0.0, "BVC area must be positive");
        }
    }

    @Test
    void testClosedLoopOrchestrationAndKinematicGuard() {
        ChaosInjectionRequest req = ChaosInjectionRequest.builder()
                .presetId("THERMAL_SECTOR_B")
                .commandText("Thermal runaway detected at Node 3")
                .build();

        ClosedLoopExecutionResponse response = orchestrator.executeChaosInjection(req);
        assertNotNull(response);
        assertNotNull(response.getVoiceStage());
        assertEquals("muse-voice-transcribe-1.0", response.getVoiceStage().getModel());
        assertNotNull(response.getImageStage());
        assertEquals("muse-image-1.0", response.getImageStage().getModel());
        assertNotNull(response.getSamStage());
        assertEquals("sam-3.1", response.getSamStage().getModel());
        assertEquals(48, response.getSamStage().getVertexCount(), "SAM 3.1 must generate 48-point convex hull");
        assertNotNull(response.getWonderStage());
        assertEquals("muse-wonder-1.0", response.getWonderStage().getPrimaryModel());
        assertEquals("muse-spark-1.3", response.getWonderStage().getPartnerModel());
        assertFalse(response.getKinematicVerdicts().isEmpty());

        boolean anyIntercepted = response.getKinematicVerdicts().stream()
                .anyMatch(v -> v.isIntercepted() && v.getVerificationLatencyMs() < 1.0);
        assertTrue(anyIntercepted, "KinematicSafetyKernel must intercept unsafe trajectory in < 1ms");
    }

    @Test
    void testVoiceCommandClosedLoop() {
        VoiceCommandRequest voiceReq = VoiceCommandRequest.builder()
                .transcriptHint("Structural Collapse Grid 4")
                .build();

        ClosedLoopExecutionResponse response = orchestrator.executeVoiceClosedLoop(voiceReq);
        assertNotNull(response);
        assertEquals("STRUCTURAL_COLLAPSE", response.getVoiceStage().getDetectedHazardType());
        assertTrue(safetyKernel.getTotalInterceptions().get() > 0);
    }

    // =========================================================================
    // P0 Integrity Audit Regression Tests (P0-B, P0-C, P0-D, P0-E, P0-F & yMax=455)
    // =========================================================================

    @Test
    void testUserUploadedPhotoWithoutPresetContaminationReturnsNoDetectionsAndUnverified() {
        // P0-B: Even if a caller passes presetObstacles alongside imageSource="USER_UPLOAD",
        // user-uploaded photos must NEVER inherit preset obstacles when live detections are absent.
        List<BoundingBox> contaminatingPreset = List.of(
                BoundingBox.builder().xMin(365).yMin(260).xMax(515).yMax(385).label("Preset Pallet").confidence(0.99).build()
        );

        AnalyzeSceneRequest userUploadReq = AnalyzeSceneRequest.builder()
                .sceneId("LOGISTICS_CORRIDOR_A")
                .imageSource("USER_UPLOAD")
                .conceptPrompt("box")
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .presetObstacles(contaminatingPreset)
                .build();

        VerifiableSceneResponse response = orchestrator.analyzeAndVerifyScene(userUploadReq);
        assertNotNull(response);
        assertEquals("USER_UPLOAD", response.getImageSource());
        assertEquals("NONE", response.getObstacleSource());
        assertEquals("NO_DETECTIONS", response.getPerceptionStatus(),
                "User upload without live detections must report NO_DETECTIONS");
        assertEquals("UNVERIFIED", response.getVerificationStatus(),
                "NO_DETECTIONS must never become APPROVED");
        assertTrue(response.getObstacles().isEmpty(),
                "User upload must not inherit preset obstacles");
        assertTrue(response.getVerifiedWaypoints().isEmpty(),
                "UNVERIFIED scene must not emit verifiedWaypoints");
        assertEquals("UNVERIFIED", response.getRos2Bundle().getVerificationStatus());
        assertTrue(response.getRos2Bundle().getNav2WaypointsJson().contains("\"kinematic_approved\": false"));
        assertTrue(response.getRos2Bundle().getNav2WaypointsJson().contains("\"poses\": [\n  ]"));
    }

    @Test
    void testZeroDetectionsAndPerceptionErrorNeverBecomeApproved() {
        // P0-C Case 1: Explicit preset scene with empty obstacle list -> NO_DETECTIONS -> UNVERIFIED
        AnalyzeSceneRequest zeroDetectionsReq = AnalyzeSceneRequest.builder()
                .sceneId("EMPTY_TEST_SCENE")
                .imageSource("PRESET_SCENE")
                .presetObstacles(List.of())
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse zeroDetResp = orchestrator.analyzeAndVerifyScene(zeroDetectionsReq);
        assertEquals("NO_DETECTIONS", zeroDetResp.getPerceptionStatus());
        assertEquals("UNVERIFIED", zeroDetResp.getVerificationStatus());
        assertNotEquals("APPROVED", zeroDetResp.getVerificationStatus());
        assertTrue(zeroDetResp.getObstacles().isEmpty());
        assertTrue(zeroDetResp.getVerifiedWaypoints().isEmpty());

        // P0-C Case 2: Corrupted / malformed non-data-URI image payload -> PERCEPTION_ERROR -> UNVERIFIED
        AnalyzeSceneRequest errorReq = AnalyzeSceneRequest.builder()
                .sceneId("LOGISTICS_CORRIDOR_A")
                .imageSource("USER_UPLOAD")
                .imageBase64("corrupted_non_data_uri_payload")
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse errorResp = orchestrator.analyzeAndVerifyScene(errorReq);
        assertEquals("PERCEPTION_ERROR", errorResp.getPerceptionStatus());
        assertEquals("UNVERIFIED", errorResp.getVerificationStatus());
        assertNotEquals("APPROVED", errorResp.getVerificationStatus());
        assertTrue(errorResp.getObstacles().isEmpty());
        assertTrue(errorResp.getVerifiedWaypoints().isEmpty());
        assertEquals("UNVERIFIED", errorResp.getRos2Bundle().getVerificationStatus());
    }

    @Test
    void testObstacleGeometryConservativeRadiusAndMonotonicity() {
        // P0-D: Verify no * 0.75 shrink factor and no 0.62m cap on obstacle footprint radius,
        // and verify monotonicity (enlarging an obstacle never turns a rejected trajectory into APPROVED).
        homographyEngine.calibrate(List.of(
                new SpatialCoordinate(140.0, 580.0),
                new SpatialCoordinate(720.0, 580.0),
                new SpatialCoordinate(580.0, 190.0),
                new SpatialCoordinate(280.0, 190.0)
        ), 4.0, 6.0);

        BoundingBox mediumBox = BoundingBox.builder()
                .xMin(365).yMin(260).xMax(515).yMax(385).label("Medium Pallet").confidence(0.98).build();
        BoundingBox largerBox = BoundingBox.builder()
                .xMin(280).yMin(240).xMax(600).yMax(410).label("Wide Cargo Barrier").confidence(0.98).build();

        List<MetricObstacle> mediumObsList = metaApiClient.detectAndSegmentScene(
                null, "box", List.of(mediumBox), homographyEngine, 1.6, 0.62, safetyKernel);
        List<MetricObstacle> largerObsList = metaApiClient.detectAndSegmentScene(
                null, "box", List.of(largerBox), homographyEngine, 1.6, 0.62, safetyKernel);

        assertEquals(1, mediumObsList.size());
        assertEquals(1, largerObsList.size());

        MetricObstacle mediumObs = mediumObsList.get(0);
        MetricObstacle largerObs = largerObsList.get(0);

        // Verify radius exceeds the old 0.62m artificial cap
        assertTrue(mediumObs.getRadiusMeters() > 0.62,
                "Conservative footprint radius must not be capped at 0.62m, got: " + mediumObs.getRadiusMeters());
        assertTrue(largerObs.getRadiusMeters() > mediumObs.getRadiusMeters(),
                "Enlarging obstacle bounding box must strictly increase metric radius");

        // Monotonicity invariant: A trajectory rejected by mediumObs must NEVER become approved against largerObs
        List<MetricWaypoint> candidateTrajectory = List.of(
                MetricWaypoint.builder().id("W0").xMeters(2.00).yMeters(0.45).targetSpeedMs(1.6).build(),
                MetricWaypoint.builder().id("W1").xMeters(1.20).yMeters(2.20).targetSpeedMs(1.6).build(),
                MetricWaypoint.builder().id("W2").xMeters(1.20).yMeters(3.20).targetSpeedMs(1.6).build(),
                MetricWaypoint.builder().id("W3").xMeters(2.00).yMeters(5.50).targetSpeedMs(1.6).build()
        );

        VerificationReport verdictMedium = safetyKernel.verifyTrajectory(
                candidateTrajectory, List.of(mediumObs), 1.6, 0.62);
        VerificationReport verdictLarger = safetyKernel.verifyTrajectory(
                candidateTrajectory, List.of(largerObs), 1.6, 0.62);

        assertFalse(verdictMedium.isApproved(), "Trajectory colliding with medium obstacle must be REJECTED");
        assertFalse(verdictLarger.isApproved(),
                "Monotonicity violation: Enlarging the obstacle must NEVER turn a rejected trajectory into APPROVED");
        assertTrue(verdictLarger.getMinClearanceMeters() <= verdictMedium.getMinClearanceMeters());
    }

    @Test
    void testAuthenticTelemetryNoPaddedLatenciesAndAccurateApiCounters() {
        // P0-E: In offline mode (META_API_KEY=mock_mode), liveMetaApiCalls must be 0 (not hardcoded 4),
        // and latencies must reflect real monotonic measurements without constant padding (+8.4, +3.2, etc.).
        AnalyzeSceneRequest req = AnalyzeSceneRequest.builder()
                .sceneId("LOGISTICS_CORRIDOR_A")
                .imageSource("PRESET_SCENE")
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse response = orchestrator.analyzeAndVerifyScene(req);
        if (!metaApiClient.isLiveApiEnabled()) {
            assertEquals("OFFLINE_DETERMINISTIC", response.getExecutionMode());
            assertEquals(0, response.getLiveMetaApiCalls(),
                    "Offline mode must report 0 liveMetaApiCalls, never hardcoded 4");
            assertEquals(0, response.getAttemptedMetaApiCalls());
            assertEquals(0, response.getSuccessfulMetaApiCalls());
            assertEquals(0, response.getFailedMetaApiCalls());
        }
        assertTrue(response.getTotalLatencyMs() > 0.0,
                "Monotonic execution latency must be positive");

        // Prove that KinematicSafetyKernel no longer clamps/pads latency with Math.max(0.18, ... + 0.16):
        // A warm in-memory trajectory verification executes in < 0.18ms, which was impossible under the old floor.
        List<MetricWaypoint> simplePath = List.of(
                MetricWaypoint.builder().id("W0").xMeters(0.8).yMeters(0.5).targetSpeedMs(1.5).build(),
                MetricWaypoint.builder().id("W1").xMeters(0.8).yMeters(5.0).targetSpeedMs(1.5).build()
        );
        safetyKernel.verifyTrajectory(simplePath, List.of(), 1.5, 0.62); // warm-up
        VerificationReport unpaddedReport = safetyKernel.verifyTrajectory(simplePath, List.of(), 1.5, 0.62);
        assertTrue(unpaddedReport.getVerificationLatencyMs() >= 0.0 && unpaddedReport.getVerificationLatencyMs() < 0.18,
                "Unpadded warm kernel latency must be < 0.18ms (proving removal of +0.16ms padding and 0.18ms floor), got: "
                        + unpaddedReport.getVerificationLatencyMs());
    }

    @Test
    void testWaypointSourceProvenanceIsExplicitOnAllTurns() {
        // P0-F: Verify waypointSource is explicitly populated on every turn and on VerifiableSceneResponse
        AnalyzeSceneRequest req = AnalyzeSceneRequest.builder()
                .sceneId("LOGISTICS_CORRIDOR_A")
                .imageSource("PRESET_SCENE")
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse response = orchestrator.analyzeAndVerifyScene(req);
        assertEquals(2, response.getAgentTurns().size());
        for (AgentVerificationTurn turn : response.getAgentTurns()) {
            assertNotNull(turn.getWaypointSource());
            assertTrue(
                    List.of("LLM_TOOL_CALL", "DETERMINISTIC_FALLBACK", "MOCK_SCRIPTED").contains(turn.getWaypointSource()),
                    "Invalid waypointSource: " + turn.getWaypointSource());
        }
        if (!metaApiClient.isLiveApiEnabled()) {
            assertEquals("MOCK_SCRIPTED", response.getAgentTurns().get(0).getWaypointSource());
            assertEquals("MOCK_SCRIPTED", response.getAgentTurns().get(1).getWaypointSource());
            assertEquals("MOCK_SCRIPTED", response.getWaypointSource());
        }
    }

    @Test
    void testOriginalCloseObstacleSceneYMax455RemainsRejectedAtStart() {
        // Negative regression test preserving the original yMax=455 obstacle configuration where the pallet
        // projects to (2.09m, 1.42m) with conservative radius ~0.83m, leaving < 0.47m clearance from W0(2.00m, 0.40m).
        List<BoundingBox> closeObstacleBoxes = List.of(
                BoundingBox.builder()
                        .xMin(355).yMin(310).xMax(525).yMax(455)
                        .label("Close Pallet Blocking Start")
                        .confidence(0.986)
                        .build()
        );

        AnalyzeSceneRequest closeReq = AnalyzeSceneRequest.builder()
                .sceneId("LOGISTICS_CORRIDOR_A")
                .imageSource("PRESET_SCENE")
                .presetObstacles(closeObstacleBoxes)
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse response = orchestrator.analyzeAndVerifyScene(closeReq);
        assertEquals("REJECTED", response.getVerificationStatus(),
                "Scene with yMax=455 obstacle too close to start W0(2.00m, 0.40m) must remain REJECTED");
        assertFalse(response.getAgentTurns().get(0).getKernelVerdict().isApproved());
        assertFalse(response.getAgentTurns().get(1).getKernelVerdict().isApproved());
        assertTrue(response.getVerifiedWaypoints().isEmpty(),
                "Rejected scene must not export verifiedWaypoints");
        assertEquals("NONE", response.getWaypointSource());
        assertEquals("REJECTED", response.getRos2Bundle().getVerificationStatus());
    }

    @Test
    void testTurn2PreservesFullTurn1GeometricContextAndToolCallHistory() throws Exception {
        // Phase 2 Req #2 & #6: Verify that Turn 2 preserves the full Turn 1 conversation history:
        //   - Original developer message
        //   - Exact original Turn 1 user message (with corridor bounds, Start, Goal, obstacles, radii, velocity, friction, dStop)
        //   - Assistant tool_calls message emitted by Muse in Turn 1
        //   - Tool role result message with KinematicSafetyKernel counterexample
        // And verify that when Muse Turn 2 emits a valid safe trajectory, waypointSource is LLM_TOOL_CALL.
        ObjectMapper mapper = new ObjectMapper();
        List<String> capturedChatRequests = new ArrayList<>();

        RestClient testRestClient = RestClient.builder()
                .baseUrl("https://api.meta.ai/v1")
                .requestInterceptor((request, body, execution) -> {
                    String uri = request.getURI().toString();
                    String bodyStr = new String(body, StandardCharsets.UTF_8);
                    if (uri.endsWith("/chat/completions")) {
                        capturedChatRequests.add(bodyStr);
                        int turnIdx = capturedChatRequests.size();
                        String responseJson;
                        if (turnIdx == 1) {
                            // Turn 1: LLM proposes a centerline trajectory that collides with obstacle
                            responseJson = """
                                    {
                                      "id": "chatcmpl-turn1",
                                      "choices": [{
                                        "message": {
                                          "role": "assistant",
                                          "content": "Proposing direct centerline path.",
                                          "tool_calls": [{
                                            "id": "call_turn1_live_test",
                                            "type": "function",
                                            "function": {
                                              "name": "verify_kinematic_safety",
                                              "arguments": "{\\"waypoints\\":[{\\"id\\":\\"W0\\",\\"x_meters\\":2.0,\\"y_meters\\":0.45,\\"target_speed_ms\\":1.6},{\\"id\\":\\"W1\\",\\"x_meters\\":2.0,\\"y_meters\\":1.90,\\"target_speed_ms\\":1.6},{\\"id\\":\\"W2\\",\\"x_meters\\":2.0,\\"y_meters\\":3.80,\\"target_speed_ms\\":1.6},{\\"id\\":\\"W3\\",\\"x_meters\\":2.0,\\"y_meters\\":5.50,\\"target_speed_ms\\":1.4}]}"
                                            }
                                          }]
                                        }
                                      }]
                                    }
                                    """;
                        } else {
                            // Turn 2: LLM proposes a safe lateral bypass on the left lane (x=0.44m, entryY=0.85m, exitY=4.49m)
                            responseJson = """
                                    {
                                      "id": "chatcmpl-turn2",
                                      "choices": [{
                                        "message": {
                                          "role": "assistant",
                                          "content": "Replanning along left corridor bypass x=0.44m.",
                                          "tool_calls": [{
                                            "id": "call_turn2_live_test",
                                            "type": "function",
                                            "function": {
                                              "name": "verify_kinematic_safety",
                                              "arguments": "{\\"reasoning\\":\\"Shifted lateral bypass lane to X=0.44m per counterexample\\",\\"waypoints\\":[{\\"id\\":\\"W0\\",\\"xMeters\\":2.0,\\"yMeters\\":0.40},{\\"id\\":\\"W1\\",\\"xMeters\\":0.44,\\"yMeters\\":0.85},{\\"id\\":\\"W2\\",\\"xMeters\\":0.44,\\"yMeters\\":4.49},{\\"id\\":\\"W3\\",\\"xMeters\\":2.0,\\"yMeters\\":5.60}]}"
                                            }
                                          }]
                                        }
                                      }]
                                    }
                                    """;
                        }
                        MockClientHttpResponse resp = new MockClientHttpResponse(
                                responseJson.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        return resp;
                    }
                    throw new IllegalStateException("Unexpected endpoint: " + uri);
                })
                .build();

        MetaApiClient liveClient = metaApiClient.withTestTransport(testRestClient, "meta-live-test-key");

        homographyEngine.calibrate(List.of(
                new SpatialCoordinate(140.0, 580.0),
                new SpatialCoordinate(720.0, 580.0),
                new SpatialCoordinate(580.0, 190.0),
                new SpatialCoordinate(280.0, 190.0)
        ), 4.0, 6.0);

        List<MetricObstacle> obstacles = metaApiClient.detectAndSegmentScene(
                null, "box",
                List.of(BoundingBox.builder()
                        .xMin(365).yMin(260).xMax(515).yMax(385)
                        .label("Industrial Pallet & Cargo Box").confidence(0.984).build()),
                homographyEngine, 1.6, 0.62, safetyKernel);

        List<AgentVerificationTurn> turns = liveClient.runVerifiableAgentToolLoop(
                "exec-turn2-context-test",
                "Navigate AMR-01 to North Receiving Dock",
                "muse-spark-1.3",
                obstacles,
                homographyEngine,
                safetyKernel,
                1.6,
                0.62
        );

        assertEquals(2, capturedChatRequests.size(), "Must execute Turn 1 and Turn 2 chat completions");
        JsonNode turn1ReqJson = mapper.readTree(capturedChatRequests.get(0));
        JsonNode turn2ReqJson = mapper.readTree(capturedChatRequests.get(1));

        JsonNode turn1Messages = turn1ReqJson.get("messages");
        JsonNode turn2Messages = turn2ReqJson.get("messages");
        assertEquals(2, turn1Messages.size());
        assertEquals(4, turn2Messages.size(),
                "Turn 2 must contain [developer, original user prompt, assistant tool_calls, tool result]");

        String turn1UserContent = turn1Messages.get(1).get("content").asText();
        String turn2UserContent = turn2Messages.get(1).get("content").asText();
        assertEquals(turn1UserContent, turn2UserContent,
                "Turn 2 user message MUST preserve the exact Turn 1 geometric user message");
        assertFalse(turn2UserContent.contains("Propose centerline route"),
                "Turn 2 must never replace the user message with 'Propose centerline route...'");

        // Verify all required geometric & physical parameters are present in the preserved user prompt
        assertTrue(turn2UserContent.contains("width=4.00m"));
        assertTrue(turn2UserContent.contains("length=6.00m"));
        assertTrue(turn2UserContent.contains("Start W0=(2.00m, 0.40m)"));
        assertTrue(turn2UserContent.contains("Goal W3=(2.00m, 5.60m)"));
        assertTrue(turn2UserContent.contains("velocity=1.60m/s"));
        assertTrue(turn2UserContent.contains("friction_mu=0.62"));
        assertTrue(turn2UserContent.contains("stoppingDistance_d_stop="));
        assertTrue(turn2UserContent.contains("lateralClearanceRequired="));
        assertTrue(turn2UserContent.contains("radius="));
        assertTrue(turn2UserContent.contains("requiredCenterDistance>="));

        // Verify assistant tool_calls history and tool result correlation
        assertEquals("assistant", turn2Messages.get(2).get("role").asText());
        assertEquals("call_turn1_live_test",
                turn2Messages.get(2).get("tool_calls").get(0).get("id").asText());
        assertEquals("tool", turn2Messages.get(3).get("role").asText());
        assertEquals("call_turn1_live_test", turn2Messages.get(3).get("tool_call_id").asText());
        assertTrue(turn2Messages.get(3).get("content").asText().contains("\"approved\":false"));

        // Verify Turn 1 and Turn 2 attribution
        assertEquals("LLM_TOOL_CALL", turns.get(0).getWaypointSource());
        assertFalse(turns.get(0).getKernelVerdict().isApproved());
        assertEquals("LLM_TOOL_CALL", turns.get(1).getWaypointSource(),
                "Genuine LLM Turn 2 trajectory approved by kernel must be attributed as LLM_TOOL_CALL");
        assertTrue(turns.get(1).getKernelVerdict().isApproved());
    }

    @Test
    void testDeterministicFallbackProperlyAttributedWhenLlmTurn2IsUnsafeOrMissing() throws Exception {
        // Phase 2 Req #2 & #6: Verify that when Turn 2 LLM proposes an unsafe trajectory (rejected by kernel)
        // or fails to emit a valid safe trajectory, the deterministic bypass is attributed as DETERMINISTIC_FALLBACK
        // and NEVER falsely attributed as LLM_TOOL_CALL.
        List<String> capturedRequests = new ArrayList<>();

        RestClient testRestClient = RestClient.builder()
                .baseUrl("https://api.meta.ai/v1")
                .requestInterceptor((request, body, execution) -> {
                    String uri = request.getURI().toString();
                    capturedRequests.add(uri);
                    String responseJson;
                    if (uri.endsWith("/responses")) {
                        // Valid SAM 3.1 floor obstacle detection
                        responseJson = """
                                {
                                  "output_text": "<0f>0<|box;x1=365;y1=260;x2=515;y2=385;w=860;h=640;c=0.984|><|mask;x=0;y=0;c=0.984;data=125,150,!SAM31_RLE|>"
                                }
                                """;
                    } else {
                        // Both Turn 1 and Turn 2 LLM responses propose centerline paths that collide with the obstacle!
                        responseJson = """
                                {
                                  "id": "chatcmpl-unsafe",
                                  "choices": [{
                                    "message": {
                                      "role": "assistant",
                                      "content": "Attempting centerline route again.",
                                      "tool_calls": [{
                                        "id": "call_unsafe_llm",
                                        "type": "function",
                                        "function": {
                                          "name": "verify_kinematic_safety",
                                          "arguments": "{\\"waypoints\\":[{\\"id\\":\\"W0\\",\\"x_meters\\":2.0,\\"y_meters\\":0.45,\\"target_speed_ms\\":1.6},{\\"id\\":\\"W1\\",\\"x_meters\\":2.05,\\"y_meters\\":2.00,\\"target_speed_ms\\":1.6},{\\"id\\":\\"W2\\",\\"x_meters\\":2.05,\\"y_meters\\":3.20,\\"target_speed_ms\\":1.6},{\\"id\\":\\"W3\\",\\"x_meters\\":2.0,\\"y_meters\\":5.50,\\"target_speed_ms\\":1.4}]}"
                                        }
                                      }]
                                    }
                                  }]
                                }
                                """;
                    }
                    MockClientHttpResponse resp = new MockClientHttpResponse(
                            responseJson.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return resp;
                })
                .build();

        MetaApiClient liveClient = metaApiClient.withTestTransport(testRestClient, "meta-live-test-key");
        MetaServiceOrchestrator liveOrchestrator = orchestrator.withTestMetaClient(liveClient);

        AnalyzeSceneRequest req = AnalyzeSceneRequest.builder()
                .executionId("exec-fallback-attr-01")
                .sceneId("LOGISTICS_CORRIDOR_A")
                .imageSource("USER_UPLOAD")
                .imageBase64("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse response = liveOrchestrator.analyzeAndVerifyScene(req);
        assertEquals("exec-fallback-attr-01", response.getExecutionId());
        assertEquals("SAM_LIVE", response.getObstacleSource());
        assertEquals("APPROVED", response.getVerificationStatus());
        assertEquals("DETERMINISTIC_FALLBACK", response.getWaypointSource(),
                "When LLM Turn 2 trajectory is rejected by KinematicSafetyKernel, final waypointSource MUST be DETERMINISTIC_FALLBACK, never LLM_TOOL_CALL");
        assertEquals("LLM_TOOL_CALL", response.getAgentTurns().get(0).getWaypointSource());
        assertEquals("DETERMINISTIC_FALLBACK", response.getAgentTurns().get(1).getWaypointSource());

        // Exercise the same Spring ObjectMapper that serializes controller responses.
        String serialized = objectMapper.writeValueAsString(response);
        JsonNode json = objectMapper.readTree(serialized);
        assertEquals("LIVE_META_API", json.path("executionMode").asText());
        for (String pointer : List.of("/agentTurns/0/proposedWaypoints/0",
                "/agentTurns/1/proposedWaypoints/0", "/agentTurns/0/kernelVerdict/evaluatedWaypoints/0",
                "/rejectedWaypoints/0", "/verifiedWaypoints/0")) {
            JsonNode point = json.at(pointer);
            for (String field : List.of("xMeters", "yMeters", "uPixel", "vPixel", "targetSpeedMs")) {
                assertTrue(point.path(field).isNumber(), pointer + "/" + field + " must be numeric");
                assertFalse(point.has(field.toLowerCase(java.util.Locale.ROOT)));
            }
        }
        assertTrue(json.at("/obstacles/0/pixelBox/xMin").isNumber());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/live-scene-response.json"), serialized);
    }

    @Test
    void testUploadedPhotoRejectsSamBoxesOutsideCalibratedFloorBeforeClamping() throws Exception {
        // Bounding-box tokens captured from the portrait corridor photo.
        String samOutput = """
                0<|box;x1=196;y1=543;x2=291;y2=583;w=735;h=980;c=0.7697842121124268|>
                1<|box;x1=197;y1=569;x2=291;y2=630;w=735;h=980;c=0.8012422323226929|>
                2<|box;x1=198;y1=617;x2=292;y2=670;w=735;h=980;c=0.827957034111023|>
                3<|box;x1=291;y1=555;x2=306;y2=602;w=735;h=980;c=0.8476190567016602|>
                4<|box;x1=234;y1=514;x2=286;y2=545;w=735;h=980;c=0.8827838897705078|>
                5<|box;x1=286;y1=525;x2=302;y2=561;w=735;h=980;c=0.8241757750511169|>
                6<|box;x1=200;y1=660;x2=292;y2=705;w=735;h=980;c=0.827957034111023|>
                """;
        byte[] samResponse = objectMapper.writeValueAsBytes(Map.of("output_text", samOutput));
        RestClient transport = RestClient.builder()
                .baseUrl("https://api.meta.ai/v1")
                .requestInterceptor((request, body, execution) -> {
                    assertTrue(request.getURI().getPath().endsWith("/responses"));
                    MockClientHttpResponse response = new MockClientHttpResponse(samResponse, HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return response;
                })
                .build();
        var photoCalibration = new HomographyCalibrationEngine();
        photoCalibration.calibrate(List.of(
                new SpatialCoordinate(49.142857, 632.163265),
                new SpatialCoordinate(757.034014, 625.632653),
                new SpatialCoordinate(551.797763, 432.450090),
                new SpatialCoordinate(264.814827, 435.097600)
        ), 1.6, 4.4);
        var perception = metaApiClient.withTestTransport(transport, "meta-live-test-key")
                .detectAndSegmentSceneWithStatus("exec-photo-floor-filter",
                        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
                        "USER_UPLOAD", "box", List.of(), photoCalibration, 0.6, 0.62, safetyKernel);

        assertEquals("SAM_LIVE", perception.getObstacleSource());
        assertEquals(1, perception.getAttemptedApiCalls());
        assertEquals(5, perception.getRejectedDetectionsCount());
        assertEquals(2, perception.getObstacles().size());
        assertTrue(perception.getObstacles().get(0).getSamMaskToken().contains("y2=670;"));
        assertTrue(perception.getObstacles().get(1).getSamMaskToken().contains("y2=705;"));
    }

    @Test
    void testInvalidDetectionsRejectedInBothUserUploadAndPresetScene() {
        // Phase 2 Req #3, #4, #6: Verify that invalid/spurious detections from SAM 3.1 / Muse Grounding
        // (e.g., whole-frame artifact 0..858x0..638, inverted box, or ceiling box outside floor corridor)
        // are rejected by validateObstacleBox, preserve rawSamOutput/rawGroundingOutput, and NEVER become SAM_LIVE.
        RestClient invalidBoxesRestClient = RestClient.builder()
                .baseUrl("https://api.meta.ai/v1")
                .requestInterceptor((request, body, execution) -> {
                    String uri = request.getURI().toString();
                    String responseJson;
                    if (uri.endsWith("/responses")) {
                        // SAM 3.1 returns 1 whole-frame artifact box and 1 inverted degenerate box
                        responseJson = """
                                {
                                  "output_text": "<0f>0<|box;x1=0;y1=0;x2=858;y2=638;w=860;h=640;c=0.99|><0f>1<|box;x1=500;y1=400;x2=300;y2=200;w=860;h=640;c=0.95|>"
                                }
                                """;
                    } else {
                        // Muse Grounding returns a ceiling box far above the floor corridor (y=20..70 -> metricY > 10m)
                        responseJson = """
                                {
                                  "choices": [{
                                    "message": {
                                      "role": "assistant",
                                      "content": "{\\"obstacles\\":[{\\"label\\":\\"Ceiling Light\\",\\"box\\":[100,20,200,70]}]}"
                                    }
                                  }]
                                }
                                """;
                    }
                    MockClientHttpResponse resp = new MockClientHttpResponse(
                            responseJson.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return resp;
                })
                .build();

        MetaApiClient liveClient = metaApiClient.withTestTransport(invalidBoxesRestClient, "meta-live-test-key");
        MetaServiceOrchestrator liveOrchestrator = orchestrator.withTestMetaClient(liveClient);
        String validDataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

        // Case A: USER_UPLOAD -> must NOT use preset geometry; must return INVALID_DETECTION + UNVERIFIED + 0 poses
        AnalyzeSceneRequest uploadReq = AnalyzeSceneRequest.builder()
                .executionId("exec-invalid-upload-01")
                .sceneId("USER_CUSTOM_SCENE")
                .imageSource("USER_UPLOAD")
                .imageBase64(validDataUri)
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse uploadResp = liveOrchestrator.analyzeAndVerifyScene(uploadReq);
        assertEquals("INVALID_DETECTION", uploadResp.getPerceptionStatus());
        assertEquals("NONE", uploadResp.getObstacleSource());
        assertNotEquals("SAM_LIVE", uploadResp.getObstacleSource());
        assertEquals("UNVERIFIED", uploadResp.getVerificationStatus());
        assertTrue(uploadResp.getRejectedDetectionsCount() >= 3,
                "All 3 invalid detections must be counted as rejected, got: " + uploadResp.getRejectedDetectionsCount());
        assertNotNull(uploadResp.getRawSamOutput());
        assertNotNull(uploadResp.getRawGroundingOutput());
        assertTrue(uploadResp.getObstacles().isEmpty());
        assertTrue(uploadResp.getVerifiedWaypoints().isEmpty());

        // Case B: PRESET_SCENE with known valid preset geometry -> invalid live detections rejected,
        // fallback geometry explicitly labeled PRESET_DEMO (never SAM_LIVE), with raw evidence preserved
        AnalyzeSceneRequest presetReq = AnalyzeSceneRequest.builder()
                .executionId("exec-invalid-preset-02")
                .sceneId("LOGISTICS_CORRIDOR_A")
                .imageSource("PRESET_SCENE")
                .imageBase64(validDataUri)
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse presetResp = liveOrchestrator.analyzeAndVerifyScene(presetReq);
        assertEquals("PRESET_DEMO", presetResp.getObstacleSource(),
                "PRESET_SCENE fallback after invalid live detections must be labeled PRESET_DEMO, never SAM_LIVE");
        assertNotEquals("SAM_LIVE", presetResp.getObstacleSource());
        assertTrue(presetResp.getRejectedDetectionsCount() >= 3);
        assertNotNull(presetResp.getRawSamOutput());

        // Case C: PRESET_SCENE where provided presetObstacles are also invalid -> INVALID_DETECTION + UNVERIFIED
        AnalyzeSceneRequest badPresetReq = AnalyzeSceneRequest.builder()
                .executionId("exec-invalid-preset-03")
                .sceneId("LOGISTICS_CORRIDOR_A")
                .imageSource("PRESET_SCENE")
                .imageBase64(validDataUri)
                .presetObstacles(List.of(
                        BoundingBox.builder().xMin(0).yMin(0).xMax(859).yMax(639).label("Whole Frame").confidence(0.99).build()
                ))
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse badPresetResp = liveOrchestrator.analyzeAndVerifyScene(badPresetReq);
        assertEquals("INVALID_DETECTION", badPresetResp.getPerceptionStatus());
        assertEquals("UNVERIFIED", badPresetResp.getVerificationStatus());
        assertTrue(badPresetResp.getVerifiedWaypoints().isEmpty());
    }

    @Test
    void testMetaApiErrorReflectedInLogsAndResponseWithoutArtificialApproval() {
        // Phase 2 Req #3, #5, #6: Verify that when Meta API fails (e.g. stage timeout / HTTP error),
        // the failure is logged with the correlated executionId into pipelineLogs and ChaosSimulationEngine,
        // failedMetaApiCalls > 0, and the scene returns PERCEPTION_ERROR + UNVERIFIED with 0 approved poses.
        RestClient failingRestClient = RestClient.builder()
                .baseUrl("https://api.meta.ai/v1")
                .requestInterceptor((request, body, execution) -> {
                    throw new ResourceAccessException("Read timed out after 30000ms calling " + request.getURI());
                })
                .build();

        MetaApiClient failingClient = metaApiClient.withTestTransport(failingRestClient, "meta-live-test-key");
        MetaServiceOrchestrator failingOrchestrator = orchestrator.withTestMetaClient(failingClient);

        AnalyzeSceneRequest req = AnalyzeSceneRequest.builder()
                .executionId("exec-timeout-test-99")
                .sceneId("LOGISTICS_CORRIDOR_A")
                .imageSource("PRESET_SCENE")
                .imageBase64("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
                .corridorWidthMeters(4.0)
                .corridorLengthMeters(6.0)
                .velocityMs(1.6)
                .frictionMu(0.62)
                .build();

        VerifiableSceneResponse response = failingOrchestrator.analyzeAndVerifyScene(req);
        assertEquals("exec-timeout-test-99", response.getExecutionId());
        assertEquals("PERCEPTION_ERROR", response.getPerceptionStatus());
        assertEquals("UNVERIFIED", response.getVerificationStatus(),
                "Total Meta API failure must result in UNVERIFIED, never APPROVED");
        assertEquals("NONE", response.getObstacleSource());
        assertEquals("NONE", response.getWaypointSource());
        assertTrue(response.getObstacles().isEmpty());
        assertTrue(response.getVerifiedWaypoints().isEmpty());
        assertTrue(response.getFailedMetaApiCalls() >= 2,
                "Both SAM 3.1 and Muse Spark Grounding failures must be counted in failedMetaApiCalls");

        boolean hasCorrelatedErrorInPipelineLogs = response.getPipelineLogs().stream()
                .anyMatch(l -> "exec-timeout-test-99".equals(l.getExecutionId())
                        && "ERROR".equals(l.getLevel()));
        assertTrue(hasCorrelatedErrorInPipelineLogs,
                "pipelineLogs must contain an ERROR entry correlated with executionId=exec-timeout-test-99");

        boolean hasTimeoutInEngineLogs = simulationEngine.getRecentLogs().stream()
                .anyMatch(l -> "exec-timeout-test-99".equals(l.getExecutionId())
                        && "ERROR".equals(l.getLevel())
                        && l.getMessage() != null
                        && l.getMessage().contains("[META_API_STAGE_TIMEOUT]"));
        assertTrue(hasTimeoutInEngineLogs,
                "ChaosSimulationEngine recentLogs must contain [META_API_STAGE_TIMEOUT] correlated with executionId=exec-timeout-test-99");
    }

    // =========================================================================
    // Continuation: scale provenance, ROS 2 grid coherence, floor clipping
    // =========================================================================

    @Test
    void testRos2GridCoversExactlyDeclaredCorridorWithScaleProvenance() {
        VerificationReport approved = VerificationReport.builder()
                .approved(true).verificationStatus("APPROVED").verificationHash("sha256:test-scale")
                .minClearanceMeters(0.88).lateralClearanceRequiredMeters(0.47)
                .requiredClearanceMeters(1.08).stoppingDistanceMeters(0.61).build();
        List<MetricWaypoint> poses = List.of(
                MetricWaypoint.builder().id("W0").xMeters(0.8).yMeters(0.4).targetSpeedMs(0.6).build(),
                MetricWaypoint.builder().id("W1").xMeters(0.8).yMeters(4.0).targetSpeedMs(0.6).build());
        Ros2ExportBundle bundle = ros2ExportService.generateRos2Bundle(
                1.6, 4.4, List.of(), poses, approved, 0.6, 0.62,
                "USER_CUSTOM_SCENE", "NOMINAL_ASSUMED");
        assertEquals(32, bundle.getGridWidthCells(), "1.6m / 0.05m must be 32 cells, without minimum-size padding");
        assertEquals(88, bundle.getGridHeightCells(), "4.4m / 0.05m must be 88 cells, without minimum-size padding");
        assertEquals("USER_CUSTOM_SCENE", bundle.getSceneId());
        assertEquals("NOMINAL_ASSUMED", bundle.getScaleType());
        assertTrue(bundle.getMapPgmAscii().contains("32 88\n255"));
        assertTrue(bundle.getMapYaml().contains("scale_type=NOMINAL_ASSUMED"));
        assertTrue(bundle.getMapYaml().contains("origin: [0.000, 0.000, 0.000]"));
        assertTrue(bundle.getNav2WaypointsJson().contains("\"scale_type\": \"NOMINAL_ASSUMED\""));
        assertFalse(bundle.getNav2WaypointsJson().contains("\"poses\": [\n  ]"),
                "Approved bundle must export its poses");
    }

    @Test
    void testUnknownScaleHaltsUnverifiedWhileNominalScaleStillVerifies() {
        AnalyzeSceneRequest unknownReq = AnalyzeSceneRequest.builder()
                .sceneId("LOGISTICS_CORRIDOR_A").imageSource("PRESET_SCENE")
                .scaleType("UNKNOWN")
                .corridorWidthMeters(4.0).corridorLengthMeters(6.0)
                .velocityMs(1.6).frictionMu(0.62).build();
        VerifiableSceneResponse unknown = orchestrator.analyzeAndVerifyScene(unknownReq);
        assertEquals("UNKNOWN", unknown.getScaleType());
        assertEquals("DETECTED", unknown.getPerceptionStatus());
        assertEquals("UNVERIFIED", unknown.getVerificationStatus(),
                "Unknown metric scale must never approve a route");
        assertFalse(unknown.getObstacles().isEmpty(), "Detected obstacles stay as evidence");
        assertTrue(unknown.getVerifiedWaypoints().isEmpty());
        assertEquals("UNKNOWN", unknown.getRos2Bundle().getScaleType());
        assertTrue(unknown.getRos2Bundle().getNav2WaypointsJson().contains("\"poses\": [\n  ]"));

        AnalyzeSceneRequest nominalReq = AnalyzeSceneRequest.builder()
                .sceneId("LOGISTICS_CORRIDOR_A").imageSource("PRESET_SCENE")
                .corridorWidthMeters(4.0).corridorLengthMeters(6.0)
                .velocityMs(1.6).frictionMu(0.62).build();
        VerifiableSceneResponse nominal = orchestrator.analyzeAndVerifyScene(nominalReq);
        assertEquals("NOMINAL_PRESET", nominal.getScaleType());
        assertEquals("APPROVED", nominal.getVerificationStatus(),
                "Known positive case must stay approved under nominal preset scale");
        assertFalse(nominal.getVerifiedWaypoints().isEmpty());
    }

    @Test
    void testRos2ExportIsBoundToExecutionIdWithoutCrossContamination() {
        // Execution A: known APPROVED case (4 poses), offline fixtures only — no Meta calls.
        VerifiableSceneResponse respA = orchestrator.analyzeAndVerifyScene(AnalyzeSceneRequest.builder()
                .executionId("exec-iso-A").sceneId("LOGISTICS_CORRIDOR_A").imageSource("PRESET_SCENE")
                .corridorWidthMeters(4.0).corridorLengthMeters(6.0).velocityMs(1.6).frictionMu(0.62).build());
        assertEquals("APPROVED", respA.getVerificationStatus());
        assertEquals(4, respA.getVerifiedWaypoints().size());

        // Execution B: known REJECTED case (0 poses) run afterwards, overwriting "latest".
        VerifiableSceneResponse respB = orchestrator.analyzeAndVerifyScene(AnalyzeSceneRequest.builder()
                .executionId("exec-iso-B").sceneId("LOGISTICS_CORRIDOR_A").imageSource("PRESET_SCENE")
                .presetObstacles(List.of(BoundingBox.builder()
                        .xMin(355).yMin(310).xMax(525).yMax(455)
                        .label("Close Pallet Blocking Start").confidence(0.986).build()))
                .corridorWidthMeters(4.0).corridorLengthMeters(6.0).velocityMs(1.6).frictionMu(0.62).build());
        assertEquals("REJECTED", respB.getVerificationStatus());
        assertTrue(respB.getVerifiedWaypoints().isEmpty());

        Ros2ExportBundle bundleA = orchestrator.getRos2BundleForExecution("exec-iso-A");
        assertNotNull(bundleA, "Execution A bundle must remain retrievable after B ran");
        assertEquals("exec-iso-A", bundleA.getExecutionId());
        assertEquals("LOGISTICS_CORRIDOR_A", bundleA.getSceneId());
        assertEquals("APPROVED", bundleA.getVerificationStatus());
        assertEquals(respA.getVerificationHash(), bundleA.getVerificationHash());
        assertTrue(bundleA.getNav2WaypointsJson().contains("\"execution_id\": \"exec-iso-A\""));
        assertTrue(bundleA.getNav2WaypointsJson().contains("\"id\": \"W3\""),
                "A must keep its four approved poses after B");

        Ros2ExportBundle bundleB = orchestrator.getRos2BundleForExecution("exec-iso-B");
        assertNotNull(bundleB);
        assertEquals("exec-iso-B", bundleB.getExecutionId());
        assertEquals("REJECTED", bundleB.getVerificationStatus());
        assertEquals(respB.getVerificationHash(), bundleB.getVerificationHash());
        assertTrue(bundleB.getNav2WaypointsJson().contains("\"poses\": [\n  ]"),
                "B must never export A's approved poses");
        assertNotEquals(bundleA.getVerificationHash(), bundleB.getVerificationHash());
        assertNull(orchestrator.getRos2BundleForExecution("exec-iso-missing"));

        VoiceChaosController controller = new VoiceChaosController(orchestrator);
        ResponseEntity<Ros2ExportBundle> httpA = controller.exportRos2Bundle("exec-iso-A");
        assertEquals(HttpStatus.OK, httpA.getStatusCode());
        assertEquals("exec-iso-A", httpA.getBody().getExecutionId());
        assertEquals(HttpStatus.NOT_FOUND, controller.exportRos2Bundle("exec-iso-missing").getStatusCode(),
                "Unknown executionId must 404, never substitute another execution");
    }

    @Test
    void testExecutionLogsAreCorrelatedAndFilterable() {
        simulationEngine.appendLog("exec-log-A", "ROS2_EXPORT", "INFO", "bundle A compiled", 1.0);
        simulationEngine.appendLog("exec-log-B", "ROS2_EXPORT", "INFO", "bundle B compiled", 1.0);
        var logsA = simulationEngine.getLogsForExecution("exec-log-A");
        assertFalse(logsA.isEmpty());
        assertTrue(logsA.stream().allMatch(e -> "exec-log-A".equals(e.getExecutionId())));
        assertTrue(logsA.stream().noneMatch(e -> e.getMessage().contains("bundle B")),
                "Logs filtered by executionId must not attribute another execution's results");
        assertTrue(simulationEngine.getLogsForExecution("exec-log-missing").isEmpty());
    }

    @Test
    void testFootprintClippingNeverShiftsOutsideFloorIntoCorridor() {
        homographyEngine.calibrate(List.of(
                new SpatialCoordinate(140.0, 580.0),
                new SpatialCoordinate(720.0, 580.0),
                new SpatialCoordinate(580.0, 190.0),
                new SpatialCoordinate(280.0, 190.0)
        ), 4.0, 6.0);

        BoundingBox outside = BoundingBox.builder()
                .xMin(0).yMin(500).xMax(100).yMax(560).label("Off Floor").confidence(0.95).build();
        assertNotNull(metaApiClient.validateObstacleBox(outside, homographyEngine),
                "Box fully outside the calibrated floor must be rejected");
        assertTrue(homographyEngine.projectObstacleBoxToMetricPolygon(outside, 860, 640).isEmpty(),
                "Off-floor box must not be shifted into the corridor");

        BoundingBox straddling = BoundingBox.builder()
                .xMin(150).yMin(260).xMax(300).yMax(385).label("Edge Box").confidence(0.95).build();
        List<SpatialCoordinate> polygon = homographyEngine.projectObstacleBoxToMetricPolygon(straddling, 860, 640);
        assertFalse(polygon.isEmpty(), "Partially observed floor obstacle keeps its observed interval");
        for (SpatialCoordinate pt : polygon) {
            assertTrue(pt.getX() >= -0.001 && pt.getX() <= 4.001 && pt.getY() >= -0.001 && pt.getY() <= 6.001,
                    "Clipped footprint must stay inside the calibrated floor: " + pt.getX() + "," + pt.getY());
        }
    }
}
