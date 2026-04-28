package com.hxzhitang.tongdarailway.util;

import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import com.hxzhitang.tongdarailway.railway.RegionPos;

import java.util.*;

// ²Î¿¼£ºURL_ADDRESS// ²Î¿¼£ºhttps://www.redblobgames.com/pathfinding/a-star/introduction.html
import java.util.*;

public class AStarPathfinder {

    private static final int[][] DIRECTIONS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };

    private static final double[] MOVEMENT_COST = {
            1.0, 1.0, 1.0, 1.0,
            1.414, 1.414, 1.414, 1.414
    };
    private static final int PATH_STEP = 12;
    private static final int NO_DIRECTION = -1;
    private static final double DIRECTION_CHANGE_COST = 15;

    public static List<int[]> findPath(RailwayBuilder builder, int[] start, Set<int[]> end, RegionPos center, int region_l1_limit, AdditionalCostFunction additionalCostFunction) {
        if (builder == null || start == null || start.length < 2 || center == null || additionalCostFunction == null) {
            return new ArrayList<>();
        }
        if (end == null || end.isEmpty()) {
            return new ArrayList<>();
        }
        if (!isWithinRegionRange(start[0], start[1], center, region_l1_limit)) {
            return new ArrayList<>();
        }

        List<int[]> validEnds = new ArrayList<>();
        for (int[] target : end) {
            if (target != null && target.length >= 2 && isWithinRegionRange(target[0], target[1], center, region_l1_limit)) {
                validEnds.add(target);
            }
        }
        if (validEnds.isEmpty()) {
            return new ArrayList<>();
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));

        Map<NodeKey, Double> gScore = new HashMap<>();

        Node startNode = new Node(start[0], start[1], NO_DIRECTION);
        startNode.g = 0;
        startNode.h = heuristic(start, validEnds);
        startNode.f = startNode.g + startNode.h;

        gScore.put(new NodeKey(start[0], start[1], NO_DIRECTION), 0.0);
        openSet.offer(startNode);

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();
            int currentX = current.x;
            int currentY = current.y;
            NodeKey currentKey = new NodeKey(currentX, currentY, current.directionIndex);
            double bestCurrentG = gScore.getOrDefault(currentKey, Double.MAX_VALUE);

            // Skip stale queue nodes.
            if (current.g > bestCurrentG) {
                continue;
            }

            int[] reachedTarget = findReachedTarget(currentX, currentY, validEnds, PATH_STEP);
            if (reachedTarget != null) {
                return reconstructPath(current, start, reachedTarget);
            }

            for (int i = 0; i < DIRECTIONS.length; i++) {
                int[] direction = DIRECTIONS[i];
                int newX = currentX + direction[0] * PATH_STEP;
                int newY = currentY + direction[1] * PATH_STEP;
                if (!isWithinRegionRange(newX, newY, center, region_l1_limit)) {
                    continue;
                }
                NodeKey neighborKey = new NodeKey(newX, newY, i);

                double movementCost = MOVEMENT_COST[i] * PATH_STEP;
                double directionChangeCost = current.directionIndex != NO_DIRECTION && current.directionIndex != i
                        ? DIRECTION_CHANGE_COST
                        : 0.0;
                double heightCost = Math.abs(builder.getHeight(currentX, currentY) - builder.getHeight(newX, newY));
                double tentativeG = current.g + movementCost + directionChangeCost + heightCost + additionalCostFunction.cost(currentX, currentY);
                double oldNeighborG = gScore.getOrDefault(neighborKey, Double.MAX_VALUE);

                if (tentativeG < oldNeighborG) {
                    Node neighbor = new Node(newX, newY, i);
                    neighbor.g = tentativeG;
                    neighbor.h = heuristic(new int[]{newX, newY}, validEnds);
                    neighbor.f = neighbor.g + neighbor.h;
                    neighbor.parent = current;

                    gScore.put(neighborKey, tentativeG);
                    openSet.offer(neighbor);
                }
            }
        }

        return new ArrayList<>();
    }

    private static double heuristic(int[] point, List<int[]> targets) {
        double minDistance = Double.MAX_VALUE;
        for (int[] target : targets) {
            int dx = Math.abs(point[0] - target[0]);
            int dy = Math.abs(point[1] - target[1]);
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance < minDistance) {
                minDistance = distance;
            }
        }
        return minDistance;
    }

    private static int[] findReachedTarget(int x, int y, List<int[]> targets, int step) {
        int stepSquared = step * step;
        int[] best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int[] target : targets) {
            int dx = x - target[0];
            int dy = y - target[1];
            int dist = dx * dx + dy * dy;
            if (dist <= stepSquared && dist < bestDist) {
                best = target;
                bestDist = dist;
            }
        }
        return best;
    }

    private static boolean isWithinRegionRange(int wx, int wz, RegionPos center, int l1Limit) {
        RegionPos regionPos = RegionPos.regionPosFromWorldPos(wx, wz);
        int l1Dist = Math.abs(regionPos.x() - center.x()) + Math.abs(regionPos.z() - center.z());
        return l1Dist <= l1Limit;
    }

    private static List<int[]> reconstructPath(Node current, int[] start, int[] target) {
        List<int[]> path = new ArrayList<>();

        while (current != null) {
            path.add(0, new int[]{current.x, current.y});
            current = current.parent;
        }

        if (path.isEmpty() || path.get(0)[0] != start[0] || path.get(0)[1] != start[1]) {
            path.add(0, new int[]{start[0], start[1]});
        }
        int[] last = path.get(path.size() - 1);
        if (last[0] != target[0] || last[1] != target[1]) {
            path.add(new int[]{target[0], target[1]});
        }

        return path;
    }

    static class Node {
        int x, y;
        double g;
        double h;
        double f;
        int directionIndex;
        Node parent;

        Node(int x, int y, int directionIndex) {
            this.x = x;
            this.y = y;
            this.directionIndex = directionIndex;
        }
    }


    static class NodeKey {
        int x, y;
        int directionIndex;

        NodeKey(int x, int y, int directionIndex) {
            this.x = x;
            this.y = y;
            this.directionIndex = directionIndex;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof NodeKey)) {
                return false;
            }
            NodeKey other = (NodeKey) obj;
            return x == other.x && y == other.y && directionIndex == other.directionIndex;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, directionIndex);
        }
    }

    @FunctionalInterface
    public interface AdditionalCostFunction {
        double cost(int x, int y);
    }
}
