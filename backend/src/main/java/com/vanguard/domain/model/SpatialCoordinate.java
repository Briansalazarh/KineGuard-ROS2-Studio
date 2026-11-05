package com.vanguard.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpatialCoordinate {

    private double x;
    private double y;

    public double distanceTo(SpatialCoordinate other) {
        if (other == null) {
            return Double.MAX_VALUE;
        }
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.hypot(dx, dy);
    }

    public SpatialCoordinate add(double dx, double dy) {
        return new SpatialCoordinate(this.x + dx, this.y + dy);
    }

    public SpatialCoordinate clamp(double minX, double maxX, double minY, double maxY) {
        return new SpatialCoordinate(
                Math.max(minX, Math.min(maxX, this.x)),
                Math.max(minY, Math.min(maxY, this.y))
        );
    }

    public SpatialCoordinate copy() {
        return new SpatialCoordinate(this.x, this.y);
    }
}
