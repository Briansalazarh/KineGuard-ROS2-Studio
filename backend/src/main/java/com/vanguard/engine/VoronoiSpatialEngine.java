package com.vanguard.engine;

import com.vanguard.domain.model.RobotNode;
import com.vanguard.domain.model.SpatialCoordinate;
import com.vanguard.domain.model.VoronoiCell;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure mathematical 2D Voronoi & Buffered Voronoi Cell (BVC) spatial tessellation engine.
 * Uses exact Sutherland-Hodgman half-plane intersection over [0, width] x [0, height]
 * to construct collision-free convex navigation envelopes in < 0.1ms.
 */
@Component
public class VoronoiSpatialEngine {

    private static final double EPSILON = 1e-6;

    /**
     * Computes both exact Voronoi cells and safety-retracted Buffered Voronoi Cells (BVC)
     * for all active robot nodes on the bounded 2D grid.
     */
    public List<VoronoiCell> computeVoronoiCells(List<RobotNode> robots, double gridWidth, double gridHeight) {
        if (robots == null || robots.isEmpty()) {
            return Collections.emptyList();
        }

        List<VoronoiCell> cells = new ArrayList<>(robots.size());

        for (int i = 0; i < robots.size(); i++) {
            RobotNode robotI = robots.get(i);
            SpatialCoordinate siteI = robotI.getPosition();

            List<SpatialCoordinate> rawPoly = createBoundingBox(0.0, 0.0, gridWidth, gridHeight);

            double wallMargin = Math.max(8.0, robotI.getRadius() * 0.75);
            List<SpatialCoordinate> bvcPoly = createBoundingBox(
                    wallMargin, wallMargin,
                    gridWidth - wallMargin, gridHeight - wallMargin
            );

            for (int j = 0; j < robots.size(); j++) {
                if (i == j) {
                    continue;
                }
                RobotNode robotJ = robots.get(j);
                SpatialCoordinate siteJ = robotJ.getPosition();

                double dx = siteJ.getX() - siteI.getX();
                double dy = siteJ.getY() - siteI.getY();
                double dist = Math.hypot(dx, dy);
                if (dist < EPSILON) {
                    continue;
                }

                double nx = dx / dist;
                double ny = dy / dist;

                double midX = 0.5 * (siteI.getX() + siteJ.getX());
                double midY = 0.5 * (siteI.getY() + siteJ.getY());

                rawPoly = clipPolygonWithHalfPlane(rawPoly, midX, midY, nx, ny);

                double desiredRetraction = robotI.getRadius() + (0.35 * robotI.getSafetyBuffer());
                double safeRetraction = Math.min(desiredRetraction, dist * 0.42);
                double bvcPlaneX = midX - nx * safeRetraction;
                double bvcPlaneY = midY - ny * safeRetraction;

                bvcPoly = clipPolygonWithHalfPlane(bvcPoly, bvcPlaneX, bvcPlaneY, nx, ny);
            }

            double area = computePolygonArea(bvcPoly);
            double minClearance = computeMinBoundaryClearance(siteI, bvcPoly);

            cells.add(VoronoiCell.builder()
                    .robotId(robotI.getId())
                    .generatorSite(siteI.copy())
                    .rawVertices(rawPoly)
                    .bufferedVertices(bvcPoly)
                    .areaSqUnits(area)
                    .minClearance(minClearance)
                    .build());
        }

        return cells;
    }

    /**
     * Sutherland-Hodgman half-plane clipping for a convex polygon.
     * Retains the half-plane { q | (q - planeOrigin) . normal <= 0 }.
     */
    public List<SpatialCoordinate> clipPolygonWithHalfPlane(
            List<SpatialCoordinate> polygon,
            double planeX,
            double planeY,
            double normalX,
            double normalY) {

        if (polygon == null || polygon.size() < 3) {
            return Collections.emptyList();
        }

        List<SpatialCoordinate> output = new ArrayList<>(polygon.size() + 1);
        int count = polygon.size();

        for (int i = 0; i < count; i++) {
            SpatialCoordinate current = polygon.get(i);
            SpatialCoordinate next = polygon.get((i + 1) % count);

            double distCurrent = (current.getX() - planeX) * normalX + (current.getY() - planeY) * normalY;
            double distNext = (next.getX() - planeX) * normalX + (next.getY() - planeY) * normalY;

            boolean currentInside = distCurrent <= EPSILON;
            boolean nextInside = distNext <= EPSILON;

            if (currentInside && nextInside) {
                output.add(next);
            } else if (currentInside) {
                output.add(intersectEdge(current, next, distCurrent, distNext));
            } else if (nextInside) {
                output.add(intersectEdge(current, next, distCurrent, distNext));
                output.add(next);
            }
        }

        return output;
    }

    private SpatialCoordinate intersectEdge(
            SpatialCoordinate a,
            SpatialCoordinate b,
            double distA,
            double distB) {
        double denom = distA - distB;
        if (Math.abs(denom) < EPSILON) {
            return a.copy();
        }
        double t = distA / denom;
        double ix = a.getX() + t * (b.getX() - a.getX());
        double iy = a.getY() + t * (b.getY() - a.getY());
        return new SpatialCoordinate(ix, iy);
    }

    private List<SpatialCoordinate> createBoundingBox(double minX, double minY, double maxX, double maxY) {
        List<SpatialCoordinate> box = new ArrayList<>(4);
        box.add(new SpatialCoordinate(minX, minY));
        box.add(new SpatialCoordinate(maxX, minY));
        box.add(new SpatialCoordinate(maxX, maxY));
        box.add(new SpatialCoordinate(minX, maxY));
        return box;
    }

    /**
     * Computes exact polygon area via the Shoelace theorem.
     */
    public double computePolygonArea(List<SpatialCoordinate> vertices) {
        if (vertices == null || vertices.size() < 3) {
            return 0.0;
        }
        double sum = 0.0;
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            SpatialCoordinate current = vertices.get(i);
            SpatialCoordinate next = vertices.get((i + 1) % n);
            sum += (current.getX() * next.getY()) - (next.getX() * current.getY());
        }
        return Math.abs(sum) * 0.5;
    }

    /**
     * Computes minimum Euclidean distance from the generator site to any edge of its polygon.
     */
    public double computeMinBoundaryClearance(SpatialCoordinate site, List<SpatialCoordinate> vertices) {
        if (vertices == null || vertices.size() < 3) {
            return 0.0;
        }
        double minClearance = Double.MAX_VALUE;
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            SpatialCoordinate a = vertices.get(i);
            SpatialCoordinate b = vertices.get((i + 1) % n);
            double dist = pointToSegmentDistance(site, a, b);
            if (dist < minClearance) {
                minClearance = dist;
            }
        }
        return minClearance == Double.MAX_VALUE ? 0.0 : minClearance;
    }

    /**
     * Checks whether a point lies inside a polygon using ray-casting / winding parity.
     */
    public boolean isPointInsidePolygon(SpatialCoordinate point, List<SpatialCoordinate> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            SpatialCoordinate vi = polygon.get(i);
            SpatialCoordinate vj = polygon.get(j);
            boolean intersect = ((vi.getY() > point.getY()) != (vj.getY() > point.getY()))
                    && (point.getX() < (vj.getX() - vi.getX()) * (point.getY() - vi.getY())
                    / (vj.getY() - vi.getY() + EPSILON) + vi.getX());
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Projects an external coordinate onto the closest point on a polygon's perimeter,
     * retracted slightly inward toward the generator site.
     */
    public SpatialCoordinate projectInsidePolygon(
            SpatialCoordinate target,
            SpatialCoordinate generatorSite,
            List<SpatialCoordinate> polygon) {
        if (polygon == null || polygon.size() < 3 || isPointInsidePolygon(target, polygon)) {
            return target;
        }
        SpatialCoordinate closest = generatorSite;
        double minDist = Double.MAX_VALUE;
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            SpatialCoordinate a = polygon.get(i);
            SpatialCoordinate b = polygon.get((i + 1) % n);
            SpatialCoordinate candidate = closestPointOnSegment(target, a, b);
            double d = target.distanceTo(candidate);
            if (d < minDist) {
                minDist = d;
                closest = candidate;
            }
        }
        double pullX = closest.getX() + 0.12 * (generatorSite.getX() - closest.getX());
        double pullY = closest.getY() + 0.12 * (generatorSite.getY() - closest.getY());
        return new SpatialCoordinate(pullX, pullY);
    }

    public double pointToSegmentDistance(SpatialCoordinate p, SpatialCoordinate a, SpatialCoordinate b) {
        return p.distanceTo(closestPointOnSegment(p, a, b));
    }

    public SpatialCoordinate closestPointOnSegment(SpatialCoordinate p, SpatialCoordinate a, SpatialCoordinate b) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double lenSq = dx * dx + dy * dy;
        if (lenSq < EPSILON) {
            return a.copy();
        }
        double t = ((p.getX() - a.getX()) * dx + (p.getY() - a.getY()) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return new SpatialCoordinate(a.getX() + t * dx, a.getY() + t * dy);
    }
}
