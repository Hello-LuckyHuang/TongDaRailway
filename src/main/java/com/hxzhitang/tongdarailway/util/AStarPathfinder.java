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

        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Node> cameFrom = new HashMap<>();

        Node startNode = new Node(start[0], start[1]);
        startNode.g = 0;
        startNode.h = heuristic(start, validEnds);
        startNode.f = startNode.g + startNode.h;

        gScore.put(encode(start[0], start[1]), 0.0);
        openSet.offer(startNode);

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();
            int currentX = current.x;
            int currentY = current.y;
            long currentKey = encode(currentX, currentY);
            double bestCurrentG = gScore.getOrDefault(currentKey, Double.MAX_VALUE);

            // Skip stale queue nodes.
            if (current.g > bestCurrentG) {
                continue;
            }

            int[] reachedTarget = findReachedTarget(currentX, currentY, validEnds, PATH_STEP);
            if (reachedTarget != null) {
                return reconstructPath(cameFrom, current, start, reachedTarget);
            }

            for (int i = 0; i < DIRECTIONS.length; i++) {
                int[] direction = DIRECTIONS[i];
                int newX = currentX + direction[0] * PATH_STEP;
                int newY = currentY + direction[1] * PATH_STEP;
                if (!isWithinRegionRange(newX, newY, center, region_l1_limit)) {
                    continue;
                }
                long neighborKey = encode(newX, newY);

                double movementCost = MOVEMENT_COST[i] * PATH_STEP;
                double heightCost = Math.abs(builder.getHeight(currentX, currentY) - builder.getHeight(newX, newY));
                double tentativeG = current.g + movementCost + heightCost + additionalCostFunction.cost(currentX, currentY);
                double oldNeighborG = gScore.getOrDefault(neighborKey, Double.MAX_VALUE);

                if (tentativeG < oldNeighborG) {
                    Node neighbor = new Node(newX, newY);
                    neighbor.g = tentativeG;
                    neighbor.h = heuristic(new int[]{newX, newY}, validEnds);
                    neighbor.f = neighbor.g + neighbor.h;

                    cameFrom.put(neighborKey, current);
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

    private static long encode(int x, int y) {
        return (((long) x) << 32) | (y & 0xffffffffL);
    }

    private static boolean isWithinRegionRange(int wx, int wz, RegionPos center, int l1Limit) {
        RegionPos regionPos = RegionPos.regionPosFromWorldPos(wx, wz);
        int l1Dist = Math.abs(regionPos.x() - center.x()) + Math.abs(regionPos.z() - center.z());
        return l1Dist <= l1Limit;
    }

    private static List<int[]> reconstructPath(Map<Long, Node> cameFrom, Node current, int[] start, int[] target) {
        List<int[]> path = new ArrayList<>();

        while (current != null) {
            path.add(0, new int[]{current.x, current.y});
            current = cameFrom.get(encode(current.x, current.y));
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

        Node(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    @FunctionalInterface
    public interface AdditionalCostFunction {
        double cost(int x, int y);
    }
}
