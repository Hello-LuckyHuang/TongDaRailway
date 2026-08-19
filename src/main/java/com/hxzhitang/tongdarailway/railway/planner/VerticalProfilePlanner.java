package com.hxzhitang.tongdarailway.railway.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.hxzhitang.tongdarailway.Tongdarailway.HEIGHT_MAX_INCREMENT;

/**
 * Dynamic-programming planner for the vertical profile of a horizontal route.
 */
final class VerticalProfilePlanner {
    static final int MAX_BRIDGE_CLEARANCE = 20;
    static final double HORIZONTAL_BLOCKS_PER_RISE = 12.0;

    private static final double INF = Double.POSITIVE_INFINITY;

    private VerticalProfilePlanner() {
    }

    static List<int[]> plan(
            List<int[]> horizontalPath,
            int[] terrainHeights,
            int seaLevel,
            int startHeight,
            int endHeight
    ) {
        int pointCount = horizontalPath.size();
        if (pointCount == 0 || terrainHeights.length != pointCount) {
            return Collections.emptyList();
        }
        if (pointCount == 1) {
            if (startHeight != endHeight) {
                return Collections.emptyList();
            }
            int[] point = horizontalPath.getFirst();
            return List.of(new int[]{point[0], point[1], startHeight});
        }

        int minHeight = Math.min(seaLevel + 5, Math.min(startHeight, endHeight));
        int maxHeight = Math.max(seaLevel + HEIGHT_MAX_INCREMENT, Math.max(startHeight, endHeight));
        int heightCount = maxHeight - minHeight + 1;

        if (!isAllowed(0, startHeight, terrainHeights, seaLevel)
                || !isAllowed(pointCount - 1, endHeight, terrainHeights, seaLevel)) {
            return Collections.emptyList();
        }

        double[][] previous = new double[heightCount][heightCount];
        fill(previous, INF);
        int[][][] parent = new int[pointCount][heightCount][heightCount];

        int startIndex = startHeight - minHeight;
        int firstRise = maxRise(horizontalPath.get(0), horizontalPath.get(1));
        for (int currentIndex = 0; currentIndex < heightCount; currentIndex++) {
            int currentHeight = minHeight + currentIndex;
            if (Math.abs(currentHeight - startHeight) > firstRise
                    || !isCandidateAllowed(1, currentHeight, pointCount, endHeight, terrainHeights, seaLevel)) {
                continue;
            }
            previous[startIndex][currentIndex] = nodeCost(0, startHeight, terrainHeights, seaLevel)
                    + nodeCost(1, currentHeight, terrainHeights, seaLevel)
                    + gradeCost(currentHeight - startHeight)
                    + modeChangeCost(
                    mode(startHeight, terrainHeights[0]),
                    mode(currentHeight, terrainHeights[1])
            );
            parent[1][startIndex][currentIndex] = startIndex + 1;
        }

        for (int pointIndex = 2; pointIndex < pointCount; pointIndex++) {
            double[][] current = new double[heightCount][heightCount];
            fill(current, INF);
            int rise = maxRise(horizontalPath.get(pointIndex - 1), horizontalPath.get(pointIndex));

            for (int previousIndex = 0; previousIndex < heightCount; previousIndex++) {
                int previousHeight = minHeight + previousIndex;
                for (int currentIndex = 0; currentIndex < heightCount; currentIndex++) {
                    double costSoFar = previous[previousIndex][currentIndex];
                    if (!Double.isFinite(costSoFar)) {
                        continue;
                    }

                    int currentHeight = minHeight + currentIndex;
                    int firstGrade = currentHeight - previousHeight;
                    int nextStart = Math.max(0, currentIndex - rise);
                    int nextEnd = Math.min(heightCount - 1, currentIndex + rise);
                    for (int nextIndex = nextStart; nextIndex <= nextEnd; nextIndex++) {
                        int nextHeight = minHeight + nextIndex;
                        if (!isCandidateAllowed(
                                pointIndex,
                                nextHeight,
                                pointCount,
                                endHeight,
                                terrainHeights,
                                seaLevel
                        )) {
                            continue;
                        }

                        int secondGrade = nextHeight - currentHeight;
                        double candidateCost = costSoFar
                                + nodeCost(pointIndex, nextHeight, terrainHeights, seaLevel)
                                + gradeCost(secondGrade)
                                + gradeChangeCost(firstGrade, secondGrade)
                                + modeChangeCost(
                                mode(currentHeight, terrainHeights[pointIndex - 1]),
                                mode(nextHeight, terrainHeights[pointIndex])
                        );
                        if (candidateCost < current[currentIndex][nextIndex]) {
                            current[currentIndex][nextIndex] = candidateCost;
                            parent[pointIndex][currentIndex][nextIndex] = previousIndex + 1;
                        }
                    }
                }
            }
            previous = current;
        }

        int endIndex = endHeight - minHeight;
        int bestPreviousIndex = -1;
        double bestCost = INF;
        for (int previousIndex = 0; previousIndex < heightCount; previousIndex++) {
            if (previous[previousIndex][endIndex] < bestCost) {
                bestCost = previous[previousIndex][endIndex];
                bestPreviousIndex = previousIndex;
            }
        }
        if (bestPreviousIndex < 0) {
            return Collections.emptyList();
        }

        int[] selected = new int[pointCount];
        selected[pointCount - 1] = endIndex;
        selected[pointCount - 2] = bestPreviousIndex;
        for (int pointIndex = pointCount - 1; pointIndex >= 2; pointIndex--) {
            int encodedParent = parent[pointIndex][selected[pointIndex - 1]][selected[pointIndex]];
            if (encodedParent == 0) {
                return Collections.emptyList();
            }
            selected[pointIndex - 2] = encodedParent - 1;
        }

        List<int[]> result = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) {
            int[] point = horizontalPath.get(i);
            result.add(new int[]{point[0], point[1], minHeight + selected[i]});
        }
        return result;
    }

    static int maxRise(int[] first, int[] second) {
        double dx = second[0] - first[0];
        double dz = second[1] - first[1];
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 1.0e-9) {
            return 0;
        }
        return Math.max(1, (int) Math.floor(horizontalDistance / HORIZONTAL_BLOCKS_PER_RISE + 1.0e-9));
    }

    private static boolean isCandidateAllowed(
            int pointIndex,
            int height,
            int pointCount,
            int endHeight,
            int[] terrainHeights,
            int seaLevel
    ) {
        if (pointIndex == pointCount - 1 && height != endHeight) {
            return false;
        }
        return isAllowed(pointIndex, height, terrainHeights, seaLevel);
    }

    private static boolean isAllowed(int pointIndex, int height, int[] terrainHeights, int seaLevel) {
        int bridgeReference = Math.max(seaLevel, terrainHeights[pointIndex]);
        return height <= bridgeReference + MAX_BRIDGE_CLEARANCE;
    }

    private static double nodeCost(int pointIndex, int height, int[] terrainHeights, int seaLevel) {
        int terrain = terrainHeights[pointIndex];
        int delta = height - terrain;
        if (delta > 3) {
            return 12.0 + delta * delta * 1.6;
        }
        if (delta < -3) {
            int depth = -delta;
            return 4.0 + depth * 0.35 + depth * depth * 0.015;
        }
        double surfaceCost = Math.abs(delta) * 0.8;
        if (height < seaLevel + 5) {
            surfaceCost += (seaLevel + 5 - height) * 3.0;
        }
        return surfaceCost;
    }

    private static double gradeCost(int grade) {
        return Math.abs(grade) * 1.5 + grade * grade * 6.0;
    }

    private static double gradeChangeCost(int firstGrade, int secondGrade) {
        int change = secondGrade - firstGrade;
        return change * change * 8.0;
    }

    private static double modeChangeCost(ProfileMode first, ProfileMode second) {
        return first == second ? 0.0 : 5.0;
    }

    private static ProfileMode mode(int height, int terrainHeight) {
        int delta = height - terrainHeight;
        if (delta > 3) {
            return ProfileMode.BRIDGE;
        }
        if (delta < -3) {
            return ProfileMode.TUNNEL;
        }
        return ProfileMode.GROUND;
    }

    private static void fill(double[][] array, double value) {
        for (double[] row : array) {
            Arrays.fill(row, value);
        }
    }

    private enum ProfileMode {
        GROUND,
        BRIDGE,
        TUNNEL
    }
}
