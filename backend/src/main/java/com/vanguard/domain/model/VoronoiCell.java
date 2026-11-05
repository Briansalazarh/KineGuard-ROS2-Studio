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
public class VoronoiCell {

    private String robotId;
    private SpatialCoordinate generatorSite;

    @Builder.Default
    private List<SpatialCoordinate> rawVertices = new ArrayList<>();

    @Builder.Default
    private List<SpatialCoordinate> bufferedVertices = new ArrayList<>();

    private double areaSqUnits;
    private double minClearance;
}
