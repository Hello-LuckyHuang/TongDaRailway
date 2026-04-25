package com.hxzhitang.tongdarailway.railway.planner;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Single-file implementation:
 * 1. Sample n points in minX..maxX, minZ..maxZ with a Hammersley sequence.
 * 2. Filter sampled points with a callback.
 * 3. Build a Delaunay triangulation with the Bowyer-Watson algorithm.
 * 4. Convert triangle edges into an undirected graph.
 * 5. Remove edges until adjacent radial edges around each node differ by at least minAngle.
 * 6. Remove more edges until every node degree is at most maxDegree.
 * 7. Drop isolated nodes and output coordinates plus connected coordinates.
 */
public final class RouteGraph {
    private static final double EPS = 1e-12;

    private RouteGraph() {
    }

    /**
     * Generates graph node data with a point filter inserted after Hammersley sampling
     * and before Delaunay triangulation. The callback receives x and z coordinates.
     *
     * @param minX            region minimum X
     * @param maxX            region maximum X
     * @param minZ            region minimum Z
     * @param maxZ            region maximum Z
     * @param n               sample count
     * @param seed            seed used to randomize the Hammersley sequence
     * @param minAngleDegrees minimum angle between adjacent radial edges, in degrees
     * @param maxDegree       maximum allowed degree per node
     * @param pointFilter     callback used to keep or remove sampled points
     * @return nodes containing own coordinate and all connected coordinates
     */
    public static List<NodeData> generate(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            int n,
            long seed,
            double minAngleDegrees,
            int maxDegree,
            PointFilter pointFilter
    ) {
        double minAngleRadians = Math.toRadians(minAngleDegrees);
        validate(minX, maxX, minZ, maxZ, n, minAngleRadians, maxDegree);
        if (pointFilter == null) {
            throw new IllegalArgumentException("pointFilter must not be null.");
        }

        List<Point> points = filterPoints(hammersleyPoints(minX, maxX, minZ, maxZ, n, seed), pointFilter);
        if (points.isEmpty()) {
            return Collections.emptyList();
        }

        List<Triangle> triangles = delaunayTriangulation(points);
        Map<Integer, Set<Integer>> graph = buildGraph(points.size(), triangles);

        enforceMinimumAdjacentAngle(points, graph, minAngleRadians);
        enforceMaximumDegree(points, graph, maxDegree);

        return toNodeData(points, graph);
    }

    /**
     * Example:
     * java HammersleyDelaunayGraph 0 100 0 100 50 12345 25 5
     *
     * Arguments are minX maxX minZ maxZ n seed minAngleDegrees maxDegree.
     */
    public static void main(String[] args) {
        double minX = 0.0;
        double maxX = 100.0;
        double minZ = 0.0;
        double maxZ = 100.0;
        int n = 6;
        long seed = 133L;
        double minAngleDegrees = 25.0;
        int maxDegree = 5;

        if (args.length == 8) {
            minX = Double.parseDouble(args[0]);
            maxX = Double.parseDouble(args[1]);
            minZ = Double.parseDouble(args[2]);
            maxZ = Double.parseDouble(args[3]);
            n = Integer.parseInt(args[4]);
            seed = Long.parseLong(args[5]);
            minAngleDegrees = Double.parseDouble(args[6]);
            maxDegree = Integer.parseInt(args[7]);
        } else if (args.length != 0) {
            throw new IllegalArgumentException(
                    "Usage: java HammersleyDelaunayGraph minX maxX minZ maxZ n seed minAngleDegrees maxDegree");
        }

        Random random = new Random(seed ^ 0x9E3779B97F4A7C15L);
        PointFilter randomFilter = (x, z) -> random.nextBoolean();
        List<NodeData> nodes = generate(
                minX,
                maxX,
                minZ,
                maxZ,
                n,
                seed,
                minAngleDegrees,
                maxDegree,
                randomFilter);
        for (NodeData node : nodes) {
            System.out.println("node: " + node.point);
            for (Vec3 connected : node.connected) {
                System.out.println("  connected: " + connected);
            }
        }
    }

    private static void validate(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            int n,
            double minAngleRadians,
            int maxDegree
    ) {
        if (!Double.isFinite(minX) || !Double.isFinite(maxX)
                || !Double.isFinite(minZ) || !Double.isFinite(maxZ)) {
            throw new IllegalArgumentException("Bounds must be finite numbers.");
        }
        if (maxX <= minX || maxZ <= minZ) {
            throw new IllegalArgumentException("maxX must be greater than minX, and maxZ greater than minZ.");
        }
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive.");
        }
        if (!Double.isFinite(minAngleRadians) || minAngleRadians < 0.0 || minAngleRadians >= Math.PI * 2.0) {
            throw new IllegalArgumentException("minAngleRadians must be in [0, 2PI).");
        }
        if (maxDegree < 0) {
            throw new IllegalArgumentException("maxDegree must be non-negative.");
        }
    }

    private static List<Point> hammersleyPoints(double minX, double maxX, double minZ, double maxZ, int n, long seed) {
        List<Point> result = new ArrayList<>(n);
        double width = maxX - minX;
        double depth = maxZ - minZ;
        Random random = new Random(seed);
        double offsetU = random.nextDouble();
        double offsetV = random.nextDouble();

        for (int i = 0; i < n; i++) {
            double u = fractionalPart((i + 0.5) / n + offsetU);
            double v = fractionalPart(radicalInverseBase2(i) + offsetV);
            double x = minX + u * width;
            double z = minZ + v * depth;
            result.add(new Point(i, x, z));
        }
        return result;
    }

    private static double fractionalPart(double value) {
        return value - Math.floor(value);
    }

    private static List<Point> filterPoints(List<Point> points, PointFilter pointFilter) {
        List<Point> result = new ArrayList<>();
        for (Point point : points) {
            if (pointFilter.keep(point.x, point.z)) {
                result.add(new Point(result.size(), point.x, point.z));
            }
        }
        return result;
    }

    private static double radicalInverseBase2(int value) {
        double result = 0.0;
        double fraction = 0.5;
        int x = value;

        while (x > 0) {
            if ((x & 1) == 1) {
                result += fraction;
            }
            x >>>= 1;
            fraction *= 0.5;
        }
        return result;
    }

    private static List<Triangle> delaunayTriangulation(List<Point> inputPoints) {
        int originalCount = inputPoints.size();
        List<Point> points = new ArrayList<>(inputPoints);
        addSuperTriangle(points, inputPoints);

        int superA = originalCount;
        int superB = originalCount + 1;
        int superC = originalCount + 2;

        List<Triangle> triangles = new ArrayList<>();
        triangles.add(orientedTriangle(superA, superB, superC, points));

        for (int i = 0; i < originalCount; i++) {
            Point point = points.get(i);
            List<Triangle> badTriangles = new ArrayList<>();

            for (Triangle triangle : triangles) {
                if (isInsideCircumcircle(point, triangle, points)) {
                    badTriangles.add(triangle);
                }
            }

            Map<Edge, Integer> edgeCounts = new LinkedHashMap<>();
            for (Triangle triangle : badTriangles) {
                countEdge(edgeCounts, new Edge(triangle.a, triangle.b));
                countEdge(edgeCounts, new Edge(triangle.b, triangle.c));
                countEdge(edgeCounts, new Edge(triangle.c, triangle.a));
            }

            triangles.removeAll(badTriangles);

            for (Map.Entry<Edge, Integer> entry : edgeCounts.entrySet()) {
                if (entry.getValue() == 1) {
                    Edge boundary = entry.getKey();
                    triangles.add(orientedTriangle(boundary.u, boundary.v, i, points));
                }
            }
        }

        List<Triangle> result = new ArrayList<>();
        for (Triangle triangle : triangles) {
            if (triangle.a < originalCount && triangle.b < originalCount && triangle.c < originalCount) {
                result.add(triangle);
            }
        }
        return result;
    }

    private static void addSuperTriangle(List<Point> points, List<Point> inputPoints) {
        double minX = inputPoints.get(0).x;
        double maxX = inputPoints.get(0).x;
        double minZ = inputPoints.get(0).z;
        double maxZ = inputPoints.get(0).z;

        for (Point p : inputPoints) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }

        double dx = maxX - minX;
        double dz = maxZ - minZ;
        double delta = Math.max(dx, dz);
        if (delta <= EPS) {
            delta = 1.0;
        }

        double midX = (minX + maxX) * 0.5;
        double midZ = (minZ + maxZ) * 0.5;
        double r = delta * 64.0;
        int base = points.size();

        points.add(new Point(base, midX - 2.0 * r, midZ - r));
        points.add(new Point(base + 1, midX, midZ + 2.0 * r));
        points.add(new Point(base + 2, midX + 2.0 * r, midZ - r));
    }

    private static Triangle orientedTriangle(int a, int b, int c, List<Point> points) {
        Point pa = points.get(a);
        Point pb = points.get(b);
        Point pc = points.get(c);
        double cross = cross(pa, pb, pc);

        if (Math.abs(cross) <= EPS) {
            return new Triangle(a, b, c);
        }
        if (cross > 0.0) {
            return new Triangle(a, b, c);
        }
        return new Triangle(a, c, b);
    }

    private static boolean isInsideCircumcircle(Point p, Triangle triangle, List<Point> points) {
        Point a = points.get(triangle.a);
        Point b = points.get(triangle.b);
        Point c = points.get(triangle.c);

        double ax = a.x - p.x;
        double az = a.z - p.z;
        double bx = b.x - p.x;
        double bz = b.z - p.z;
        double cx = c.x - p.x;
        double cz = c.z - p.z;

        double determinant =
                (ax * ax + az * az) * (bx * cz - bz * cx)
                        - (bx * bx + bz * bz) * (ax * cz - az * cx)
                        + (cx * cx + cz * cz) * (ax * bz - az * bx);

        return determinant > EPS;
    }

    private static double cross(Point a, Point b, Point c) {
        return (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x);
    }

    private static void countEdge(Map<Edge, Integer> counts, Edge edge) {
        counts.put(edge, counts.getOrDefault(edge, 0) + 1);
    }

    private static Map<Integer, Set<Integer>> buildGraph(int pointCount, List<Triangle> triangles) {
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        for (int i = 0; i < pointCount; i++) {
            graph.put(i, new HashSet<Integer>());
        }
        for (Triangle triangle : triangles) {
            addGraphEdge(graph, triangle.a, triangle.b);
            addGraphEdge(graph, triangle.b, triangle.c);
            addGraphEdge(graph, triangle.c, triangle.a);
        }
        return graph;
    }

    private static void enforceMinimumAdjacentAngle(
            List<Point> points,
            Map<Integer, Set<Integer>> graph,
            double minAngleRadians
    ) {
        if (minAngleRadians <= EPS) {
            return;
        }

        boolean changed;
        do {
            changed = false;
            EdgeRemoval bestRemoval = null;

            for (int node = 0; node < points.size(); node++) {
                EdgeRemoval candidate = findWorstAngleViolation(points, graph, node, minAngleRadians);
                if (candidate == null) {
                    continue;
                }
                if (bestRemoval == null
                        || candidate.angleGap < bestRemoval.angleGap - EPS
                        || (Math.abs(candidate.angleGap - bestRemoval.angleGap) <= EPS
                        && candidate.removedEdgeLength > bestRemoval.removedEdgeLength)) {
                    bestRemoval = candidate;
                }
            }

            if (bestRemoval != null) {
                removeGraphEdge(graph, bestRemoval.u, bestRemoval.v);
                changed = true;
            }
        } while (changed);
    }

    private static EdgeRemoval findWorstAngleViolation(
            List<Point> points,
            Map<Integer, Set<Integer>> graph,
            int node,
            double minAngleRadians
    ) {
        Set<Integer> neighbors = graph.get(node);
        if (neighbors == null || neighbors.size() < 2) {
            return null;
        }

        List<NeighborAngle> angles = new ArrayList<>();
        Point center = points.get(node);
        for (int neighbor : neighbors) {
            Point p = points.get(neighbor);
            double angle = Math.atan2(p.z - center.z, p.x - center.x);
            if (angle < 0.0) {
                angle += Math.PI * 2.0;
            }
            angles.add(new NeighborAngle(neighbor, angle));
        }

        Collections.sort(angles, Comparator.comparingDouble(a -> a.angle));

        double smallestGap = Double.POSITIVE_INFINITY;
        int firstNeighbor = -1;
        int secondNeighbor = -1;

        for (int i = 0; i < angles.size(); i++) {
            NeighborAngle current = angles.get(i);
            NeighborAngle next = angles.get((i + 1) % angles.size());
            double gap = next.angle - current.angle;
            if (i == angles.size() - 1) {
                gap += Math.PI * 2.0;
            }

            if (gap < minAngleRadians - EPS && gap < smallestGap) {
                smallestGap = gap;
                firstNeighbor = current.node;
                secondNeighbor = next.node;
            }
        }

        if (firstNeighbor < 0) {
            return null;
        }

        double firstLength = distanceSquared(points.get(node), points.get(firstNeighbor));
        double secondLength = distanceSquared(points.get(node), points.get(secondNeighbor));
        int removeNeighbor = firstLength >= secondLength ? firstNeighbor : secondNeighbor;
        double removedLength = Math.max(firstLength, secondLength);

        return new EdgeRemoval(node, removeNeighbor, smallestGap, removedLength);
    }

    private static void enforceMaximumDegree(List<Point> points, Map<Integer, Set<Integer>> graph, int maxDegree) {
        boolean changed;
        do {
            changed = false;
            EdgeRemoval bestRemoval = null;

            for (int node = 0; node < points.size(); node++) {
                Set<Integer> neighbors = graph.get(node);
                if (neighbors == null || neighbors.size() <= maxDegree) {
                    continue;
                }

                int farthestNeighbor = -1;
                double farthestDistance = -1.0;
                for (int neighbor : neighbors) {
                    double distance = distanceSquared(points.get(node), points.get(neighbor));
                    if (distance > farthestDistance) {
                        farthestDistance = distance;
                        farthestNeighbor = neighbor;
                    }
                }

                EdgeRemoval candidate = new EdgeRemoval(node, farthestNeighbor, 0.0, farthestDistance);
                if (bestRemoval == null || candidate.removedEdgeLength > bestRemoval.removedEdgeLength) {
                    bestRemoval = candidate;
                }
            }

            if (bestRemoval != null) {
                removeGraphEdge(graph, bestRemoval.u, bestRemoval.v);
                changed = true;
            }
        } while (changed);
    }

    private static double distanceSquared(Point a, Point b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static void addGraphEdge(Map<Integer, Set<Integer>> graph, int a, int b) {
        if (a == b) {
            return;
        }
        graph.get(a).add(b);
        graph.get(b).add(a);
    }

    private static void removeGraphEdge(Map<Integer, Set<Integer>> graph, int a, int b) {
        Set<Integer> neighborsA = graph.get(a);
        Set<Integer> neighborsB = graph.get(b);
        if (neighborsA != null) {
            neighborsA.remove(b);
        }
        if (neighborsB != null) {
            neighborsB.remove(a);
        }
    }

    private static List<NodeData> toNodeData(List<Point> points, Map<Integer, Set<Integer>> graph) {
        List<NodeData> result = new ArrayList<>();

        for (int i = 0; i < points.size(); i++) {
            Set<Integer> neighbors = graph.get(i);
            if (neighbors == null || neighbors.isEmpty()) {
                continue;
            }

            Point point = points.get(i);
            List<Vec3> connected = new ArrayList<>();
            List<Integer> sortedNeighbors = new ArrayList<>(neighbors);
            sortedNeighbors.sort((left, right) -> {
                Point a = points.get(left);
                Point b = points.get(right);
                double angleA = Math.atan2(a.z - point.z, a.x - point.x);
                double angleB = Math.atan2(b.z - point.z, b.x - point.x);
                return Double.compare(angleA, angleB);
            });

            for (int neighbor : sortedNeighbors) {
                Point p = points.get(neighbor);
                connected.add(new Vec3(p.x, 0.0, p.z));
            }

            result.add(new NodeData(new Vec3(point.x, 0.0, point.z), connected));
        }
        return result;
    }

    public static final class NodeData {
        public Vec3 point;
        public final List<Vec3> connected;

        private NodeData(Vec3 point, List<Vec3> connected) {
            this.point = point;
            this.connected = Collections.unmodifiableList(new ArrayList<>(connected));
        }

        public void setPointY(double y) {
            this.point = new Vec3(point.x, y, point.z);
        }

        @Override
        public String toString() {
            return "NodeData{point=" + point + ", connected=" + connected + '}';
        }
    }

    @FunctionalInterface
    public interface PointFilter {
        boolean keep(double x, double z);
    }

    private static final class Point {
        final int id;
        final double x;
        final double z;

        Point(int id, double x, double z) {
            this.id = id;
            this.x = x;
            this.z = z;
        }
    }

    private static final class Triangle {
        final int a;
        final int b;
        final int c;

        Triangle(int a, int b, int c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }

    private static final class Edge {
        final int u;
        final int v;

        Edge(int a, int b) {
            if (a <= b) {
                this.u = a;
                this.v = b;
            } else {
                this.u = b;
                this.v = a;
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Edge)) {
                return false;
            }
            Edge edge = (Edge) other;
            return u == edge.u && v == edge.v;
        }

        @Override
        public int hashCode() {
            return Objects.hash(u, v);
        }
    }

    private static final class NeighborAngle {
        final int node;
        final double angle;

        NeighborAngle(int node, double angle) {
            this.node = node;
            this.angle = angle;
        }
    }

    private static final class EdgeRemoval {
        final int u;
        final int v;
        final double angleGap;
        final double removedEdgeLength;

        EdgeRemoval(int u, int v, double angleGap, double removedEdgeLength) {
            this.u = u;
            this.v = v;
            this.angleGap = angleGap;
            this.removedEdgeLength = removedEdgeLength;
        }
    }
}
