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
public class RobotNode {

    public enum OperationalState {
        NOMINAL,
        AVOIDING,
        EMERGENCY_STOP
    }

    private String id;
    private String callsign;
    private String payloadClass;

    private SpatialCoordinate position;
    private SpatialCoordinate targetWaypoint;
    private SpatialCoordinate proposedWaypoint;

    private double vx;
    private double vy;
    private double speed;
    private double maxSpeed;
    private double headingRad;

    private double radius;
    private double safetyBuffer;
    private double stoppingDistance;

    private double batteryPct;
    private double thermalC;

    private OperationalState state;
    private boolean kinematicInterceptActive;
    private String lastInterceptReason;
    private long lastInterceptTimestamp;

    @Builder.Default
    private List<SpatialCoordinate> trajectoryHistory = new ArrayList<>();

    @Builder.Default
    private List<SpatialCoordinate> patrolWaypoints = new ArrayList<>();

    private int currentWaypointIndex;
}
