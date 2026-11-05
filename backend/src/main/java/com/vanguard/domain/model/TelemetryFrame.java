package com.vanguard.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryFrame {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentLogEntry {
        private String id;
        private String executionId;
        private long timestamp;
        private String tag;
        private String level;
        private String message;
        private double latencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemMetrics {
        private int activeNodes;
        private int activeHazards;
        private double closedLoopLatencyMs;
        private double kinematicCheckLatencyMs;
        private double collisionRatePct;
        private long guardrailInterceptions;
        private long totalFramesProcessed;
        private double telemetryHz;
        private double minFleetSeparationMeters;
        private String metaIntegrationMode;
        private boolean physicalStabilityNominal;
    }

    private long frameId;
    private long timestamp;
    private double gridWidth;
    private double gridHeight;

    @Builder.Default
    private List<RobotNode> robots = new ArrayList<>();

    @Builder.Default
    private List<VoronoiCell> voronoiCells = new ArrayList<>();

    @Builder.Default
    private List<HazardZone> hazards = new ArrayList<>();

    @Builder.Default
    private List<AgentLogEntry> recentLogs = new ArrayList<>();

    private SystemMetrics metrics;
}
