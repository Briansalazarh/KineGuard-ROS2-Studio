package com.vanguard.engine;

import com.vanguard.domain.dto.MetaApiDtos.BoundingBox;
import com.vanguard.domain.model.SpatialCoordinate;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Projective Homography Calibration Engine (3x3 Matrix H & H^-1).
 * Maps 4 floor calibration points in camera pixel coordinates [(u0,v0)..(u3,v3)]
 * to a physical metric ground plane [0..widthMeters] x [0..lengthMeters] using
 * pure Java 8x8 Gaussian elimination with partial pivoting.
 *
 * Enforces strict geometric invariants:
 *   1. Rejects null, NaN/Infinite, or != 4 calibration points.
 *   2. Rejects collinear or near-collinear point triplets (triangle area < 100 px^2).
 *   3. Rejects self-intersecting or non-convex quadrilaterals via consistent edge cross-product winding.
 *   4. Rejects singular or ill-conditioned homography matrices (|det(H)| < 1e-10).
 *   5. Evaluates independent ground-truth checkpoint error in meters (distinct from algebraic round-trip consistency).
 */
@Component
public class HomographyCalibrationEngine {

    private static final double MIN_TRIANGLE_DOUBLE_AREA_PX2 = 200.0; // triangle area >= 100 px^2

    @Getter
    private double[] h = new double[]{
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
    };

    @Getter
    private double[] hInv = new double[]{
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
    };

    @Getter
    private double corridorWidthMeters = 4.0;

    @Getter
    private double corridorLengthMeters = 6.0;

    /**
     * Calibrates the 3x3 projective homography matrix H from 4 floor points in pixels:
     *   p0: near-left  -> (0.0, 0.0)
     *   p1: near-right -> (widthMeters, 0.0)
     *   p2: far-right  -> (widthMeters, lengthMeters)
     *   p3: far-left   -> (0.0, lengthMeters)
     *
     * @throws IllegalArgumentException if the 4 points are collinear, self-intersecting, non-convex, or singular.
     */
    public synchronized void calibrate(
            List<SpatialCoordinate> pixelPoints,
            double widthMeters,
            double lengthMeters) {

        if (!Double.isFinite(widthMeters) || !Double.isFinite(lengthMeters)
                || widthMeters <= 0.5 || lengthMeters <= 0.5) {
            throw new IllegalArgumentException(
                    "Invalid corridor dimensions: widthMeters and lengthMeters must be finite and > 0.5m");
        }
        if (pixelPoints == null || pixelPoints.size() != 4) {
            throw new IllegalArgumentException(
                    "Homography calibration requires exactly 4 floor points (p0..p3)");
        }

        validateCalibrationQuadGeometry(pixelPoints);

        this.corridorWidthMeters = widthMeters;
        this.corridorLengthMeters = lengthMeters;

        List<SpatialCoordinate> dst = List.of(
                new SpatialCoordinate(0.0, 0.0),
                new SpatialCoordinate(this.corridorWidthMeters, 0.0),
                new SpatialCoordinate(this.corridorWidthMeters, this.corridorLengthMeters),
                new SpatialCoordinate(0.0, this.corridorLengthMeters)
        );

        double[] candidateH = solveHomography8x8(pixelPoints, dst);
        double det = determinant3x3(candidateH);
        if (!Double.isFinite(det) || Math.abs(det) < 1e-10) {
            throw new IllegalArgumentException("Degenerate or singular homography matrix (det=" + det + ")");
        }

        this.h = candidateH;
        this.hInv = invert3x3(candidateH);
    }

    /**
     * Validates that all 4 calibration points are finite, non-collinear, and form a strictly convex,
     * non-self-intersecting quadrilateral.
     */
    public void validateCalibrationQuadGeometry(List<SpatialCoordinate> pts) {
        for (int i = 0; i < 4; i++) {
            SpatialCoordinate p = pts.get(i);
            if (p == null || !Double.isFinite(p.getX()) || !Double.isFinite(p.getY())) {
                throw new IllegalArgumentException("Calibration point P" + i + " contains null, NaN, or Infinite coordinates");
            }
        }

        // 1. Check all 4 triplets for collinearity: (0,1,2), (0,1,3), (0,2,3), (1,2,3)
        int[][] triplets = {{0, 1, 2}, {0, 1, 3}, {0, 2, 3}, {1, 2, 3}};
        for (int[] t : triplets) {
            SpatialCoordinate a = pts.get(t[0]);
            SpatialCoordinate b = pts.get(t[1]);
            SpatialCoordinate c = pts.get(t[2]);
            double doubleArea = Math.abs(crossProduct(a, b, c));
            if (doubleArea < MIN_TRIANGLE_DOUBLE_AREA_PX2) {
                throw new IllegalArgumentException(String.format(
                        "Degenerate calibration: points P%d, P%d, P%d are collinear or nearly collinear (2*area=%.2f px^2)",
                        t[0], t[1], t[2], doubleArea));
            }
        }

        // 2. Check strict convexity & non-self-intersection: all consecutive edge turns must have the same sign
        double c0 = crossProduct(pts.get(0), pts.get(1), pts.get(2));
        double c1 = crossProduct(pts.get(1), pts.get(2), pts.get(3));
        double c2 = crossProduct(pts.get(2), pts.get(3), pts.get(0));
        double c3 = crossProduct(pts.get(3), pts.get(0), pts.get(1));

        boolean allPositive = c0 > 0 && c1 > 0 && c2 > 0 && c3 > 0;
        boolean allNegative = c0 < 0 && c1 < 0 && c2 < 0 && c3 < 0;
        if (!allPositive && !allNegative) {
            throw new IllegalArgumentException(
                    "Invalid calibration quadrilateral: polygon is self-intersecting (bowtie) or non-convex");
        }
    }

    private double crossProduct(SpatialCoordinate a, SpatialCoordinate b, SpatialCoordinate c) {
        return (b.getX() - a.getX()) * (c.getY() - a.getY())
                - (b.getY() - a.getY()) * (c.getX() - a.getX());
    }

    public SpatialCoordinate pixelToMetric(double u, double v) {
        double denom = h[6] * u + h[7] * v + h[8];
        if (Math.abs(denom) < 1e-9) {
            denom = 1e-9;
        }
        double x = (h[0] * u + h[1] * v + h[2]) / denom;
        double y = (h[3] * u + h[4] * v + h[5]) / denom;
        return new SpatialCoordinate(
                Math.round(x * 1000.0) / 1000.0,
                Math.round(y * 1000.0) / 1000.0
        );
    }

    public SpatialCoordinate metricToPixel(double xMeters, double yMeters) {
        double denom = hInv[6] * xMeters + hInv[7] * yMeters + hInv[8];
        if (Math.abs(denom) < 1e-9) {
            denom = 1e-9;
        }
        double u = (hInv[0] * xMeters + hInv[1] * yMeters + hInv[2]) / denom;
        double v = (hInv[3] * xMeters + hInv[4] * yMeters + hInv[5]) / denom;
        return new SpatialCoordinate(
                Math.round(u * 10.0) / 10.0,
                Math.round(v * 10.0) / 10.0
        );
    }

    /**
     * Evaluates true metric projection accuracy (RMS error in meters) using independent ground-plane
     * checkpoints that were NOT used to solve the 4-point homography matrix H.
     */
    public double evaluateIndependentCheckpointsErrorMeters(
            List<SpatialCoordinate> independentPixelPoints,
            List<SpatialCoordinate> groundTruthMetricPoints) {

        if (independentPixelPoints == null || groundTruthMetricPoints == null
                || independentPixelPoints.isEmpty()
                || independentPixelPoints.size() != groundTruthMetricPoints.size()) {
            throw new IllegalArgumentException(
                    "Independent checkpoint lists must be non-empty and have matching sizes");
        }

        double sumSqError = 0.0;
        for (int i = 0; i < independentPixelPoints.size(); i++) {
            SpatialCoordinate px = independentPixelPoints.get(i);
            SpatialCoordinate truth = groundTruthMetricPoints.get(i);
            SpatialCoordinate estimated = pixelToMetric(px.getX(), px.getY());
            double err = estimated.distanceTo(truth);
            sumSqError += err * err;
        }
        double rms = Math.sqrt(sumSqError / independentPixelPoints.size());
        return Math.round(rms * 10000.0) / 10000.0;
    }

    /** Pixel-x interval of the calibrated floor quad on scan line v, or null when v misses the floor. */
    public double[] floorPixelXRangeAtY(double v) {
        List<SpatialCoordinate> corners = List.of(
                metricToPixel(0.0, 0.0),
                metricToPixel(corridorWidthMeters, 0.0),
                metricToPixel(corridorWidthMeters, corridorLengthMeters),
                metricToPixel(0.0, corridorLengthMeters));
        double left = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < corners.size(); i++) {
            SpatialCoordinate start = corners.get(i);
            SpatialCoordinate end = corners.get((i + 1) % corners.size());
            if (v < Math.min(start.getY(), end.getY()) || v > Math.max(start.getY(), end.getY())) {
                continue;
            }
            if (start.getY() == end.getY()) {
                left = Math.min(left, Math.min(start.getX(), end.getX()));
                right = Math.max(right, Math.max(start.getX(), end.getX()));
            } else {
                double fraction = (v - start.getY()) / (end.getY() - start.getY());
                double x = start.getX() + fraction * (end.getX() - start.getX());
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        return left <= right ? new double[]{left, right} : null;
    }

    /**
     * Checks the box's lower edge against the calibrated floor in image space.
     * Shifting a projected footprint into the floor would conceal an out-of-floor detection.
     */
    public boolean intersectsCalibratedFloorAtGroundContact(BoundingBox box) {
        double[] range = floorPixelXRangeAtY(box.getYMax());
        return range != null && box.getXMax() >= range[0] && box.getXMin() <= range[1];
    }

    /**
     * Heuristic ground-contact footprint estimator from a 2D perspective bounding box.
     * NOTE: Only the lower ground-contact edge (v = yMax) truly lies on the calibrated floor plane z=0.
     * The depth behind the front contact edge is an explicit planar heuristic; the region behind tall
     * obstacles is marked as UNKNOWN (205) in the Nav2 occupancy grid due to monocular occlusion.
     */
    public List<SpatialCoordinate> projectObstacleBoxToMetricPolygon(
            BoundingBox box,
            int imgWidth,
            int imgHeight) {

        double x1 = box.getXMin();
        double y1 = box.getYMin();
        double x2 = box.getXMax();
        double y2 = box.getYMax();

        // Ground contact line is at y2 (bottom edge of bounding box on floor).
        // We bound the estimated depth strip to at most 28% of visual height to avoid projecting elevated tops.
        double footprintTopY = y2 - Math.max(16.0, Math.min(48.0, (y2 - y1) * 0.28));

        // True geometric clipping: intersect the footprint strip with the convex floor
        // quad in pixel space (Sutherland-Hodgman), then project. Never shift points in.
        List<SpatialCoordinate> strip = List.of(
                new SpatialCoordinate(x1, y2),
                new SpatialCoordinate(x2, y2),
                new SpatialCoordinate(x2, footprintTopY),
                new SpatialCoordinate(x1, footprintTopY));
        List<SpatialCoordinate> clipped = clipPolygonToFloor(strip);
        if (clipped.size() < 3) {
            return List.of();
        }
        List<SpatialCoordinate> metric = new ArrayList<>(clipped.size());
        for (SpatialCoordinate px : clipped) {
            metric.add(roundMetric(pixelToMetric(px.getX(), px.getY())));
        }
        return metric;
    }

    private List<SpatialCoordinate> clipPolygonToFloor(List<SpatialCoordinate> subject) {
        List<SpatialCoordinate> floor = List.of(
                metricToPixel(0.0, 0.0),
                metricToPixel(corridorWidthMeters, 0.0),
                metricToPixel(corridorWidthMeters, corridorLengthMeters),
                metricToPixel(0.0, corridorLengthMeters));
        double cx = floor.stream().mapToDouble(SpatialCoordinate::getX).average().orElse(0.0);
        double cy = floor.stream().mapToDouble(SpatialCoordinate::getY).average().orElse(0.0);
        List<SpatialCoordinate> poly = new ArrayList<>(subject);
        for (int e = 0; e < floor.size() && !poly.isEmpty(); e++) {
            SpatialCoordinate a = floor.get(e);
            SpatialCoordinate b = floor.get((e + 1) % floor.size());
            double insideSign = Math.signum(cross(a, b, new SpatialCoordinate(cx, cy)));
            List<SpatialCoordinate> next = new ArrayList<>();
            for (int i = 0; i < poly.size(); i++) {
                SpatialCoordinate cur = poly.get(i);
                SpatialCoordinate prev = poly.get((i + poly.size() - 1) % poly.size());
                double curCross = cross(a, b, cur) * insideSign;
                double prevCross = cross(a, b, prev) * insideSign;
                boolean curIn = curCross >= -1e-9;
                boolean prevIn = prevCross >= -1e-9;
                if (curIn != prevIn) {
                    double t = prevCross / (prevCross - curCross);
                    next.add(new SpatialCoordinate(
                            prev.getX() + t * (cur.getX() - prev.getX()),
                            prev.getY() + t * (cur.getY() - prev.getY())));
                }
                if (curIn) {
                    next.add(cur);
                }
            }
            poly = next;
        }
        return poly;
    }

    private double cross(SpatialCoordinate a, SpatialCoordinate b, SpatialCoordinate p) {
        return (b.getX() - a.getX()) * (p.getY() - a.getY())
                - (b.getY() - a.getY()) * (p.getX() - a.getX());
    }

    private SpatialCoordinate roundMetric(SpatialCoordinate pt) {
        return new SpatialCoordinate(
                Math.round(pt.getX() * 100.0) / 100.0,
                Math.round(pt.getY() * 100.0) / 100.0
        );
    }

    public List<Double> getHomographyMatrixFlat() {
        List<Double> list = new ArrayList<>(9);
        for (double val : h) {
            list.add(Math.round(val * 1_000_000.0) / 1_000_000.0);
        }
        return list;
    }

    private double[] solveHomography8x8(List<SpatialCoordinate> src, List<SpatialCoordinate> dst) {
        double[][] a = new double[8][8];
        double[] b = new double[8];

        for (int i = 0; i < 4; i++) {
            double u = src.get(i).getX();
            double v = src.get(i).getY();
            double x = dst.get(i).getX();
            double y = dst.get(i).getY();

            int r1 = 2 * i;
            a[r1][0] = u;
            a[r1][1] = v;
            a[r1][2] = 1.0;
            a[r1][3] = 0.0;
            a[r1][4] = 0.0;
            a[r1][5] = 0.0;
            a[r1][6] = -u * x;
            a[r1][7] = -v * x;
            b[r1] = x;

            int r2 = 2 * i + 1;
            a[r2][0] = 0.0;
            a[r2][1] = 0.0;
            a[r2][2] = 0.0;
            a[r2][3] = u;
            a[r2][4] = v;
            a[r2][5] = 1.0;
            a[r2][6] = -u * y;
            a[r2][7] = -v * y;
            b[r2] = y;
        }

        // Gaussian elimination with partial pivoting
        for (int col = 0; col < 8; col++) {
            int pivot = col;
            for (int row = col + 1; row < 8; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            double[] tmpRow = a[col];
            a[col] = a[pivot];
            a[pivot] = tmpRow;
            double tmpB = b[col];
            b[col] = b[pivot];
            b[pivot] = tmpB;

            double diag = a[col][col];
            if (Math.abs(diag) < 1e-11) {
                throw new IllegalArgumentException("Singular linear system during 8x8 homography solve");
            }
            for (int j = col; j < 8; j++) {
                a[col][j] /= diag;
            }
            b[col] /= diag;

            for (int row = 0; row < 8; row++) {
                if (row != col) {
                    double factor = a[row][col];
                    for (int j = col; j < 8; j++) {
                        a[row][j] -= factor * a[col][j];
                    }
                    b[row] -= factor * b[col];
                }
            }
        }

        return new double[]{
                b[0], b[1], b[2],
                b[3], b[4], b[5],
                b[6], b[7], 1.0
        };
    }

    private double determinant3x3(double[] m) {
        return m[0] * (m[4] * m[8] - m[5] * m[7])
                - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    private double[] invert3x3(double[] m) {
        double det = determinant3x3(m);
        if (Math.abs(det) < 1e-11) {
            throw new IllegalArgumentException("Non-invertible 3x3 homography matrix");
        }
        double invDet = 1.0 / det;
        return new double[]{
                (m[4] * m[8] - m[5] * m[7]) * invDet,
                (m[2] * m[7] - m[1] * m[8]) * invDet,
                (m[1] * m[5] - m[2] * m[4]) * invDet,
                (m[5] * m[6] - m[3] * m[8]) * invDet,
                (m[0] * m[8] - m[2] * m[6]) * invDet,
                (m[2] * m[3] - m[0] * m[5]) * invDet,
                (m[3] * m[7] - m[4] * m[6]) * invDet,
                (m[1] * m[6] - m[0] * m[7]) * invDet,
                (m[0] * m[4] - m[1] * m[3]) * invDet
        };
    }
}
