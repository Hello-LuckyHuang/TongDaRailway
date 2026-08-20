package com.hxzhitang.tongdarailway.railway.planner;

import com.hxzhitang.tongdarailway.railway.RailwayBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hxzhitang.tongdarailway.Tongdarailway.HEIGHT_MAX_INCREMENT;

/**
 * Chooses station elevations from foundation support and route feasibility.
 */
final class StationElevationPlanner {
    private static final int[] FOUNDATION_OFFSETS = {-18, 0, 18};
    private static final int STATION_APPROACH_LENGTH = 65;
    private static final int OPTIMIZATION_PASSES = 12;
    private static final double INFEASIBLE_EDGE_COST = 100_000.0;

    private StationElevationPlanner() {
    }

    static List<StationElevation> plan(
            List<RouteGraph.NodeData> nodes,
            RailwayBuilder builder,
            ServerLevel level
    ) {
        if (nodes.isEmpty()) {
            return List.of();
        }

        int seaLevel = level.getSeaLevel();
        int minHeight = seaLevel;
        int maxHeight = seaLevel + HEIGHT_MAX_INCREMENT;
        int heightCount = maxHeight - minHeight + 1;

        SiteProfile[] sites = new SiteProfile[nodes.size()];
        Map<Long, Integer> nodeIndices = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            RouteGraph.NodeData node = nodes.get(i);
            int x = (int) node.point.x;
            int z = (int) node.point.z;
            nodeIndices.put(coordinateKey(x, z), i);
            sites[i] = sampleSite(builder, level, x, z, minHeight, heightCount);
        }

        List<EdgeProfile> edges = buildEdges(nodes, nodeIndices, minHeight, heightCount);
        List<List<EdgeProfile>> incidentEdges = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            incidentEdges.add(new ArrayList<>());
        }
        for (EdgeProfile edge : edges) {
            incidentEdges.get(edge.firstNode).add(edge);
            incidentEdges.get(edge.secondNode).add(edge);
        }

        int[] selected = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            selected[i] = minimumIndex(sites[i].costs);
        }

        for (int pass = 0; pass < OPTIMIZATION_PASSES; pass++) {
            boolean changed = false;
            for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
                int bestHeightIndex = selected[nodeIndex];
                double bestCost = Double.POSITIVE_INFINITY;
                for (int candidate = 0; candidate < heightCount; candidate++) {
                    double cost = sites[nodeIndex].costs[candidate];
                    for (EdgeProfile edge : incidentEdges.get(nodeIndex)) {
                        int neighbor = edge.other(nodeIndex);
                        cost += edge.cost(nodeIndex, candidate, selected[neighbor]);
                    }
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestHeightIndex = candidate;
                    }
                }
                if (bestHeightIndex != selected[nodeIndex]) {
                    selected[nodeIndex] = bestHeightIndex;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        repairInfeasibleEdges(edges, incidentEdges, sites, selected);
        if (hasInfeasibleEdge(edges, selected)) {
            // Sea level + 5 favors ocean clearance and gives the network a
            // deterministic zero-grade fallback.
            Arrays.fill(selected, Math.min(5, heightCount - 1));
        }

        List<StationElevation> result = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            int height = minHeight + selected[i];
            result.add(new StationElevation(
                    height,
                    sites[i].centerSurface,
                    sites[i].undergroundCosts[selected[i]] < sites[i].groundCosts[selected[i]]
            ));
        }
        return result;
    }

    private static SiteProfile sampleSite(
            RailwayBuilder builder,
            ServerLevel level,
            int x,
            int z,
            int minHeight,
            int heightCount
    ) {
        int[] surfaceHeights = new int[FOUNDATION_OFFSETS.length * FOUNDATION_OFFSETS.length];
        int sampleIndex = 0;
        for (int offsetX : FOUNDATION_OFFSETS) {
            for (int offsetZ : FOUNDATION_OFFSETS) {
                surfaceHeights[sampleIndex++] = builder.getExactHeight(level, x + offsetX, z + offsetZ);
            }
        }

        int[] sorted = surfaceHeights.clone();
        Arrays.sort(sorted);
        int median = sorted[sorted.length / 2];
        int relief = sorted[sorted.length - 1] - sorted[0];
        double[] costs = new double[heightCount];
        double[] groundCosts = new double[heightCount];
        double[] undergroundCosts = new double[heightCount];

        for (int heightIndex = 0; heightIndex < heightCount; heightIndex++) {
            int height = minHeight + heightIndex;
            int unsupportedSamples = 0;
            double supportCost = 0.0;
            double excavationCost = 0.0;
            for (int surface : surfaceHeights) {
                int unsupported = Math.max(0, height - surface - 3);
                if (surface < height - 4) {
                    unsupportedSamples++;
                }
                supportCost += unsupported * unsupported * 6.0;

                int excavation = Math.max(0, surface - height - 5);
                excavationCost += excavation * excavation * 0.25;
            }

            double groundCost = Math.abs(height - median) * 1.5
                    + supportCost
                    + excavationCost
                    + Math.max(0, relief - 12) * 2.0;
            if (unsupportedSamples > surfaceHeights.length / 3) {
                groundCost = Double.POSITIVE_INFINITY;
            }

            int cover = sorted[0] - height;
            double undergroundCost = cover >= 10
                    ? 55.0 + Math.abs(cover - 14) * 1.2 + relief * 0.25
                    : Double.POSITIVE_INFINITY;

            groundCosts[heightIndex] = groundCost;
            undergroundCosts[heightIndex] = undergroundCost;
            costs[heightIndex] = Math.min(groundCost, undergroundCost);
        }

        return new SiteProfile(
                surfaceHeights[FOUNDATION_OFFSETS.length + 1],
                costs,
                groundCosts,
                undergroundCosts
        );
    }

    private static List<EdgeProfile> buildEdges(
            List<RouteGraph.NodeData> nodes,
            Map<Long, Integer> nodeIndices,
            int minHeight,
            int heightCount
    ) {
        List<EdgeProfile> result = new ArrayList<>();
        for (int first = 0; first < nodes.size(); first++) {
            RouteGraph.NodeData node = nodes.get(first);
            for (Vec3 connected : node.connected) {
                Integer second = nodeIndices.get(coordinateKey((int) connected.x, (int) connected.z));
                if (second == null || first >= second) {
                    continue;
                }
                result.add(sampleEdge(
                        first,
                        second,
                        node.point,
                        nodes.get(second).point,
                        minHeight,
                        heightCount
                ));
            }
        }
        return result;
    }

    private static EdgeProfile sampleEdge(
            int firstNode,
            int secondNode,
            Vec3 first,
            Vec3 second,
            int minHeight,
            int heightCount
    ) {
        double dx = second.x - first.x;
        double dz = second.z - first.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double gradedDistance = Math.max(0.0, distance - STATION_APPROACH_LENGTH * 2.0);
        int riseCapacity = (int) Math.floor(
                gradedDistance / VerticalProfilePlanner.HORIZONTAL_BLOCKS_PER_RISE + 1.0e-9
        );

        double[][] costs = new double[heightCount][heightCount];
        for (int firstHeightIndex = 0; firstHeightIndex < heightCount; firstHeightIndex++) {
            int firstHeight = minHeight + firstHeightIndex;
            for (int secondHeightIndex = 0; secondHeightIndex < heightCount; secondHeightIndex++) {
                int secondHeight = minHeight + secondHeightIndex;
                costs[firstHeightIndex][secondHeightIndex] = routeFeasibilityCost(
                        riseCapacity,
                        firstHeight,
                        secondHeight
                );
            }
        }
        return new EdgeProfile(firstNode, secondNode, costs);
    }

    private static double routeFeasibilityCost(
            int riseCapacity,
            int startHeight,
            int endHeight
    ) {
        int requiredRise = Math.abs(endHeight - startHeight);
        if (requiredRise > riseCapacity) {
            return INFEASIBLE_EDGE_COST + (requiredRise - riseCapacity) * 1_000.0;
        }
        return Math.abs(startHeight - endHeight) * 0.2;
    }

    private static void repairInfeasibleEdges(
            List<EdgeProfile> edges,
            List<List<EdgeProfile>> incidentEdges,
            SiteProfile[] sites,
            int[] selected
    ) {
        for (int pass = 0; pass < OPTIMIZATION_PASSES; pass++) {
            boolean repaired = false;
            for (EdgeProfile edge : edges) {
                if (edge.costs[selected[edge.firstNode]][selected[edge.secondNode]] < INFEASIBLE_EDGE_COST) {
                    continue;
                }

                int bestFirst = selected[edge.firstNode];
                int bestSecond = selected[edge.secondNode];
                double bestCost = Double.POSITIVE_INFINITY;
                for (int firstCandidate = 0; firstCandidate < sites[edge.firstNode].costs.length; firstCandidate++) {
                    for (int secondCandidate = 0; secondCandidate < sites[edge.secondNode].costs.length; secondCandidate++) {
                        if (edge.costs[firstCandidate][secondCandidate] >= INFEASIBLE_EDGE_COST) {
                            continue;
                        }
                        double cost = sites[edge.firstNode].costs[firstCandidate]
                                + sites[edge.secondNode].costs[secondCandidate]
                                + edge.costs[firstCandidate][secondCandidate]
                                + otherEdgeCosts(edge.firstNode, edge, firstCandidate, incidentEdges, selected)
                                + otherEdgeCosts(edge.secondNode, edge, secondCandidate, incidentEdges, selected);
                        if (cost < bestCost) {
                            bestCost = cost;
                            bestFirst = firstCandidate;
                            bestSecond = secondCandidate;
                        }
                    }
                }
                if (Double.isFinite(bestCost)) {
                    selected[edge.firstNode] = bestFirst;
                    selected[edge.secondNode] = bestSecond;
                    repaired = true;
                }
            }
            if (!repaired) {
                break;
            }
        }
    }

    private static boolean hasInfeasibleEdge(List<EdgeProfile> edges, int[] selected) {
        for (EdgeProfile edge : edges) {
            if (edge.costs[selected[edge.firstNode]][selected[edge.secondNode]] >= INFEASIBLE_EDGE_COST) {
                return true;
            }
        }
        return false;
    }

    private static double otherEdgeCosts(
            int node,
            EdgeProfile excluded,
            int candidate,
            List<List<EdgeProfile>> incidentEdges,
            int[] selected
    ) {
        double result = 0.0;
        for (EdgeProfile edge : incidentEdges.get(node)) {
            if (edge == excluded) {
                continue;
            }
            result += edge.cost(node, candidate, selected[edge.other(node)]);
        }
        return result;
    }

    private static int minimumIndex(double[] values) {
        int result = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] < values[result]) {
                result = i;
            }
        }
        return result;
    }

    private static long coordinateKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    record StationElevation(int height, int surfaceHeight, boolean underground) {
    }

    private record SiteProfile(
            int centerSurface,
            double[] costs,
            double[] groundCosts,
            double[] undergroundCosts
    ) {
    }

    private static final class EdgeProfile {
        private final int firstNode;
        private final int secondNode;
        private final double[][] costs;

        private EdgeProfile(int firstNode, int secondNode, double[][] costs) {
            this.firstNode = firstNode;
            this.secondNode = secondNode;
            this.costs = costs;
        }

        private int other(int node) {
            return node == firstNode ? secondNode : firstNode;
        }

        private double cost(int node, int ownHeight, int otherHeight) {
            return node == firstNode ? costs[ownHeight][otherHeight] : costs[otherHeight][ownHeight];
        }
    }
}
