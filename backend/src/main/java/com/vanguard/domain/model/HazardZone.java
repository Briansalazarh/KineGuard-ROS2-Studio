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
public class HazardZone {

    public enum HazardType {
        THERMAL_RUNAWAY,
        STRUCTURAL_COLLAPSE,
        CORRIDOR_EVACUATION,
        FREIGHT_REROUTE_ZONE
    }

    private String id;
    private String label;
    private String sector;
    private HazardType type;

    private SpatialCoordinate center;
    private double effectiveRadius;
    private double severity;
    private double peakThermalC;

    @Builder.Default
    private List<SpatialCoordinate> polygonHull = new ArrayList<>();

    private String samMaskId;
    private double samConfidenceIou;
    private String museImagePrompt;
    private String museImageOverlayDataUri;

    private long createdAtEpochMs;
    private long expiresAtEpochMs;
    private boolean active;
}
