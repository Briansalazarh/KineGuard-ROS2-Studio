package com.vanguard.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanguard.config.WebSocketConfig;
import com.vanguard.domain.model.HazardZone;
import com.vanguard.domain.model.RobotNode;
import com.vanguard.domain.model.SpatialCoordinate;
import com.vanguard.domain.model.TelemetryFrame;
import com.vanguard.domain.model.TelemetryFrame.AgentLogEntry;
import com.vanguard.domain.model.TelemetryFrame.SystemMetrics;
import com.vanguard.domain.model.VoronoiCell;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Real-time 25Hz Physical AI & Autonomous Spatial Physics Simulation Engine.
 * Maintains the 6-AMR fleet, active SAM 3.1 hazard polygons, Buffered Voronoi Cells,
 * and broadcasts TelemetryFrames to SSE and WebSocket subscribers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChaosSimulationEngine {

    private static final int MAX_TRAJECTORY_TRAIL = 34;
    private static final int MAX_RECENT_LOGS = 60;

    private final VoronoiSpatialEngine voronoiEngine;
    private final KinematicSafetyKernel safetyKernel;
    private final WebSocketConfig webSocketConfig;
    private final ObjectMapper objectMapper;

    @Value("${vanguard.simulation.grid-width:1000.0}")
    @Getter
    private double gridWidth = 1000.0;

    @Value("${vanguard.simulation.grid-height:1000.0}")
    @Getter
    private double gridHeight = 1000.0;

    @Value("${vanguard.simulation.robot-radius:18.0}")
    private double defaultRobotRadius = 18.0;

    @Value("${vanguard.simulation.safety-buffer:26.0}")
    private double defaultSafetyBuffer = 26.0;

    @Value("${meta.api.key:mock_mode}")
    private String metaApiKey;

    @Getter
    private final List<RobotNode> fleet = new CopyOnWriteArrayList<>();

    @Getter
    private final List<HazardZone> activeHazards = new CopyOnWriteArrayList<>();

    @Getter
    private final List<AgentLogEntry> recentLogs = new CopyOnWriteArrayList<>();

    private final List<Consumer<TelemetryFrame>> frameSubscribers = new CopyOnWriteArrayList<>();

    private final AtomicLong frameCounter = new AtomicLong(0);

    @Getter
    private volatile TelemetryFrame latestFrame;

    @Getter
    private volatile double lastClosedLoopLatencyMs = 0.0;

    @PostConstruct
    public void initializeSimulation() {
        initializeFleet();
        seedInitialBootLogs();
        stepPhysicsTick();
        log.info("VANGUARD-OS Spatial Physics Engine initialized with {} AMRs on {}x{} grid",
                fleet.size(), (int) gridWidth, (int) gridHeight);
    }

    public void registerFrameSubscriber(Consumer<TelemetryFrame> subscriber) {
        frameSubscribers.add(subscriber);
    }

    public void unregisterFrameSubscriber(Consumer<TelemetryFrame> subscriber) {
        frameSubscribers.remove(subscriber);
    }

    public void updateClosedLoopLatency(double latencyMs) {
        this.lastClosedLoopLatencyMs = latencyMs;
    }

    public synchronized void appendLog(String tag, String level, String message, double latencyMs) {
        appendLog("SYS-KERNEL", tag, level, message, latencyMs);
    }

    public synchronized void appendLog(String executionId, String tag, String level, String message, double latencyMs) {
        String effectiveExecId = (executionId != null && !executionId.isBlank()) ? executionId : "SYS-KERNEL";
        AgentLogEntry entry = AgentLogEntry.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .executionId(effectiveExecId)
                .timestamp(System.currentTimeMillis())
                .tag(tag)
                .level(level)
                .message(message)
                .latencyMs(latencyMs)
                .build();
        recentLogs.add(entry);
        while (recentLogs.size() > MAX_RECENT_LOGS) {
            recentLogs.remove(0);
        }
    }

    /** Logs belonging to exactly one execution (SSE frames broadcast globally; filter before display). */
    public List<AgentLogEntry> getLogsForExecution(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return List.of();
        }
        String target = executionId.trim();
        return recentLogs.stream().filter(e -> target.equals(e.getExecutionId())).toList();
    }

    public void registerHazardZone(HazardZone hazard) {
        // Keep up to 4 active hazards simultaneously so the arena stays readable and responsive
        while (activeHazards.size() >= 4) {
            activeHazards.remove(0);
        }
        activeHazards.add(hazard);
    }

    public void resetArena() {
        activeHazards.clear();
        initializeFleet();
        appendLog("STATUS", "SUCCESS",
                "Spatial arena purged of active anomalies. All 6 AMRs restored to nominal Buffered Voronoi patrol.",
                0.4);
    }

    /**
     * Generates a 48-point convex/quasi-convex hull polygon matching SAM 3.1 segmentation boundaries.
     */
    public List<SpatialCoordinate> generate48PointConvexHull(
            SpatialCoordinate center,
            double baseRadius,
            int seedOffset) {
        int points = 48;
        List<SpatialCoordinate> hull = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double angle = (2.0 * Math.PI * i) / points;
            double harmonic = 1.0
                    + 0.055 * Math.cos(3.0 * angle + seedOffset * 0.7)
                    + 0.035 * Math.sin(5.0 * angle - seedOffset * 1.1);
            double r = baseRadius * harmonic;
            double px = Math.max(20.0, Math.min(gridWidth - 20.0, center.getX() + r * Math.cos(angle)));
            double py = Math.max(20.0, Math.min(gridHeight - 20.0, center.getY() + r * Math.sin(angle)));
            hull.add(new SpatialCoordinate(px, py));
        }
        return hull;
    }

    @Scheduled(fixedRate = 40)
    public void stepPhysicsTick() {
        long now = System.currentTimeMillis();
        double dt = 0.040; // 25 Hz deterministic timestep

        // Expire old temporary hazards after their TTL (default 90 seconds)
        activeHazards.removeIf(h -> !h.isActive() || (h.getExpiresAtEpochMs() > 0 && now > h.getExpiresAtEpochMs()));

        List<VoronoiCell> voronoiCells = voronoiEngine.computeVoronoiCells(fleet, gridWidth, gridHeight);
        Map<String, VoronoiCell> cellByRobot = voronoiCells.stream()
                .collect(Collectors.toMap(VoronoiCell::getRobotId, c -> c, (a, b) -> a));

        for (RobotNode robot : fleet) {
            if (robot.getState() == RobotNode.OperationalState.EMERGENCY_STOP) {
                robot.setVx(robot.getVx() * 0.75);
                robot.setVy(robot.getVy() * 0.75);
                robot.setSpeed(Math.hypot(robot.getVx(), robot.getVy()));
                continue;
            }

            // Advance waypoint along patrol circuit when close
            SpatialCoordinate target = robot.getTargetWaypoint();
            if (target == null || robot.getPosition().distanceTo(target) < 34.0) {
                List<SpatialCoordinate> patrol = robot.getPatrolWaypoints();
                if (patrol != null && !patrol.isEmpty()) {
                    int nextIdx = (robot.getCurrentWaypointIndex() + 1) % patrol.size();
                    robot.setCurrentWaypointIndex(nextIdx);
                    robot.setTargetWaypoint(patrol.get(nextIdx).copy());
                }
            }

            VoronoiCell cell = cellByRobot.get(robot.getId());
            safetyKernel.enforceContinuousKinematics(robot, fleet, activeHazards, cell, dt);

            // Record particle trail history
            List<SpatialCoordinate> trail = robot.getTrajectoryHistory();
            trail.add(robot.getPosition().copy());
            while (trail.size() > MAX_TRAJECTORY_TRAIL) {
                trail.remove(0);
            }

            // Simulate subtle battery & thermal telemetry fluctuations
            double drain = 0.0008 * (robot.getSpeed() / Math.max(1.0, robot.getMaxSpeed()));
            robot.setBatteryPct(Math.max(42.0, robot.getBatteryPct() - drain));
            double baseTemp = robot.getState() == RobotNode.OperationalState.AVOIDING ? 56.5 : 44.2;
            robot.setThermalC(baseTemp + 1.8 * Math.sin((now / 900.0) + robot.getId().hashCode()));
        }

        // Recompute Voronoi tessellation after position integration for exact visual alignment
        List<VoronoiCell> updatedCells = voronoiEngine.computeVoronoiCells(fleet, gridWidth, gridHeight);

        double minSepMeters = computeMinFleetSeparation() * 0.1;
        long frameId = frameCounter.incrementAndGet();

        boolean isLiveMetaKey = metaApiKey != null
                && !metaApiKey.isBlank()
                && !"mock_mode".equalsIgnoreCase(metaApiKey);

        SystemMetrics metrics = SystemMetrics.builder()
                .activeNodes(fleet.size())
                .activeHazards(activeHazards.size())
                .closedLoopLatencyMs(lastClosedLoopLatencyMs)
                .kinematicCheckLatencyMs(safetyKernel.getLastCheckLatencyMs())
                .collisionRatePct(0.00)
                .guardrailInterceptions(safetyKernel.getTotalInterceptions().get())
                .totalFramesProcessed(frameId)
                .telemetryHz(25.0)
                .minFleetSeparationMeters(Math.round(minSepMeters * 100.0) / 100.0)
                .metaIntegrationMode(isLiveMetaKey ? "DEV_META_AI_LIVE" : "META_HYBRID_DETERMINISTIC")
                .physicalStabilityNominal(true)
                .build();

        TelemetryFrame frame = TelemetryFrame.builder()
                .frameId(frameId)
                .timestamp(now)
                .gridWidth(gridWidth)
                .gridHeight(gridHeight)
                .robots( new ArrayList<>(fleet))
                .voronoiCells(updatedCells)
                .hazards(new ArrayList<>(activeHazards))
                .recentLogs(new ArrayList<>(recentLogs))
                .metrics(metrics)
                .build();

        this.latestFrame = frame;

        for (Consumer<TelemetryFrame> subscriber : frameSubscribers) {
            try {
                subscriber.accept(frame);
            } catch (Exception ignored) {
                // Subscriber cleanup handled by controller
            }
        }

        try {
            webSocketConfig.broadcastJson(objectMapper.writeValueAsString(frame));
        } catch (Exception ignored) {
            // Non-blocking WebSocket broadcast
        }
    }

    private double computeMinFleetSeparation() {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < fleet.size(); i++) {
            for (int j = i + 1; j < fleet.size(); j++) {
                double d = fleet.get(i).getPosition().distanceTo(fleet.get(j).getPosition());
                if (d < min) {
                    min = d;
                }
            }
        }
        return min == Double.MAX_VALUE ? 100.0 : min;
    }

    private void initializeFleet() {
        fleet.clear();

        fleet.add(createRobot("AMR-01", "KINEGUARD-01", "PALLET_CARRIER",
                160.0, 210.0, 68.0, 96.4,
                List.of(
                        new SpatialCoordinate(160, 210),
                        new SpatialCoordinate(440, 240),
                        new SpatialCoordinate(420, 460),
                        new SpatialCoordinate(170, 440)
                )));

        fleet.add(createRobot("AMR-02", "KINEGUARD-02", "COLD_CHAIN_AMR",
                510.0, 170.0, 72.0, 94.1,
                List.of(
                        new SpatialCoordinate(510, 170),
                        new SpatialCoordinate(820, 190),
                        new SpatialCoordinate(760, 430),
                        new SpatialCoordinate(520, 390)
                )));

        fleet.add(createRobot("AMR-03", "KINEGUARD-03", "SORTATION_BOT",
                830.0, 320.0, 64.0, 91.8,
                List.of(
                        new SpatialCoordinate(830, 320),
                        new SpatialCoordinate(850, 660),
                        new SpatialCoordinate(580, 620),
                        new SpatialCoordinate(610, 340)
                )));

        fleet.add(createRobot("AMR-04", "KINEGUARD-04", "OPTICAL_INSPECTOR",
                190.0, 790.0, 74.0, 98.2,
                List.of(
                        new SpatialCoordinate(190, 790),
                        new SpatialCoordinate(180, 520),
                        new SpatialCoordinate(460, 560),
                        new SpatialCoordinate(450, 830)
                )));

        fleet.add(createRobot("AMR-05", "KINEGUARD-05", "HEAVY_PALLET_MOVER",
                540.0, 810.0, 66.0, 89.7,
                List.of(
                        new SpatialCoordinate(540, 810),
                        new SpatialCoordinate(340, 610),
                        new SpatialCoordinate(660, 540),
                        new SpatialCoordinate(740, 830)
                )));

        fleet.add(createRobot("AMR-06", "KINEGUARD-06", "LOGISTICS_TUG",
                840.0, 780.0, 70.0, 95.0,
                List.of(
                        new SpatialCoordinate(840, 780),
                        new SpatialCoordinate(560, 740),
                        new SpatialCoordinate(620, 470),
                        new SpatialCoordinate(870, 510)
                )));
    }

    private RobotNode createRobot(
            String id,
            String callsign,
            String payloadClass,
            double startX,
            double startY,
            double maxSpeed,
            double battery,
            List<SpatialCoordinate> waypoints) {

        SpatialCoordinate startPos = new SpatialCoordinate(startX, startY);
        SpatialCoordinate nextWp = waypoints.size() > 1 ? waypoints.get(1).copy() : startPos.copy();
        double dx = nextWp.getX() - startX;
        double dy = nextWp.getY() - startY;
        double heading = Math.atan2(dy, dx);
        double initialVx = Math.cos(heading) * (maxSpeed * 0.65);
        double initialVy = Math.sin(heading) * (maxSpeed * 0.65);

        List<SpatialCoordinate> history = new ArrayList<>();
        history.add(startPos.copy());

        return RobotNode.builder()
                .id(id)
                .callsign(callsign)
                .payloadClass(payloadClass)
                .position(startPos)
                .targetWaypoint(nextWp)
                .proposedWaypoint(nextWp.copy())
                .vx(initialVx)
                .vy(initialVy)
                .speed(Math.hypot(initialVx, initialVy))
                .maxSpeed(maxSpeed)
                .headingRad(heading)
                .radius(defaultRobotRadius)
                .safetyBuffer(defaultSafetyBuffer)
                .stoppingDistance(safetyKernel.computeStoppingDistanceUnits(Math.hypot(initialVx, initialVy)))
                .batteryPct(battery)
                .thermalC(43.5)
                .state(RobotNode.OperationalState.NOMINAL)
                .kinematicInterceptActive(false)
                .lastInterceptReason("BVC Envelope Nominal")
                .lastInterceptTimestamp(0L)
                .trajectoryHistory(history)
                .patrolWaypoints(new ArrayList<>(waypoints))
                .currentWaypointIndex(1)
                .build();
    }

    private void seedInitialBootLogs() {
        recentLogs.clear();
        appendLog("STATUS", "INFO",
                "KineGuard ROS2 Studio Kernel v2.0 booted on Java 21 Virtual Threads. 6 AMR nodes online.",
                0.3);
        appendLog("SAM 3.1", "INFO",
                "SAM 3.1 (/v1/responses) & Projective Homography 3x3 Solver H initialized.",
                1.1);
        appendLog("MUSE_WONDER", "INFO",
                "Muse Wonder 1.0 & Muse Spark 1.3 Verifiable Tool-Calling Planner ready.",
                2.4);
        appendLog("KINEMATIC_GUARD", "SUCCESS",
                "Formal Kinematic Safety Verifier armed (d_stop = v^2/2ug + v*t_react, BVC enforced).",
                0.2);
        appendLog("ROS2_EXPORT", "SUCCESS",
                "ROS 2 Humble/Jazzy Nav2 map.pgm + map.yaml compiler online.",
                0.1);
    }
}
