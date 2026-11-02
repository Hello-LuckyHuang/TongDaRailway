package com.hxzhitang.tongdarailway.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 以路网中的有限线段为生成元，隐式表示边 Voronoi 图。
 * 区域内任意点到所属边的水平距离，都不大于到其他路网边的距离。
 * 直接比较距离可保留端点附近的曲线边界，无需将区域近似成多边形。
 */
public final class EdgeVoronoiDiagram {
    private static final double DISTANCE_EPSILON = 1.0e-7;
    private final List<Edge> edges;

    public EdgeVoronoiDiagram(Collection<Edge> edges) {
        // 无向边统一端点顺序，再去除路网邻接表中正反方向的重复边。
        this.edges = List.copyOf(new LinkedHashSet<>(edges));
    }

    public Cell cellFor(Edge edge) {
        if (!edges.contains(edge)) {
            throw new IllegalArgumentException("The edge must belong to the route graph");
        }
        return new Cell(edge);
    }

    /** 路网原始边使用站点坐标，不能用偏移后的车站出口替代。 */
    public record Edge(int startX, int startZ, int endX, int endZ) {
        public Edge {
            if (startX > endX || (startX == endX && startZ > endZ)) {
                int oldStartX = startX;
                int oldStartZ = startZ;
                startX = endX;
                startZ = endZ;
                endX = oldStartX;
                endZ = oldStartZ;
            }
        }

        double distanceSquared(double x, double z) {
            double dx = (double) endX - startX;
            double dz = (double) endZ - startZ;
            double lengthSquared = dx * dx + dz * dz;
            // 投影必须截在线段两端之间；退化边按一个点处理。
            double projection = lengthSquared == 0.0 ? 0.0
                    : Math.clamp(((x - startX) * dx + (z - startZ) * dz) / lengthSquared, 0.0, 1.0);
            double offsetX = x - startX - projection * dx;
            double offsetZ = z - startZ - projection * dz;
            return offsetX * offsetX + offsetZ * offsetZ;
        }

        private int addProjectionBreaks(double x, double z, double dx, double dz, double[] breaks, int count) {
            double edgeX = (double) endX - startX;
            double edgeZ = (double) endZ - startZ;
            double slope = dx * edgeX + dz * edgeZ;
            if (slope == 0.0) {
                return count;
            }
            double offset = (x - startX) * edgeX + (z - startZ) * edgeZ;
            double lengthSquared = edgeX * edgeX + edgeZ * edgeZ;
            double atStart = -offset / slope;
            double atEnd = (lengthSquared - offset) / slope;
            if (atStart > 0.0 && atStart < 1.0) {
                breaks[count++] = atStart;
            }
            if (atEnd > 0.0 && atEnd < 1.0) {
                breaks[count++] = atEnd;
            }
            return count;
        }
    }

    public final class Cell {
        private final Edge edge;

        private Cell(Edge edge) {
            this.edge = edge;
        }

        public boolean contains(double x, double z) {
            double distance = edge.distanceSquared(x, z);
            for (Edge other : edges) {
                // 等距点属于双方，保证相邻区域能够共享边界和站点。
                if (distance > other.distanceSquared(x, z) + DISTANCE_EPSILON) {
                    return false;
                }
            }
            return true;
        }

        /** 检查整段移动，防止两个合法节点之间的连线穿过其他边的区域。 */
        public boolean containsSegment(double startX, double startZ, double endX, double endZ) {
            if (!contains(startX, startZ) || !contains(endX, endZ)) {
                return false;
            }
            double dx = endX - startX;
            double dz = endZ - startZ;
            if (dx == 0.0 && dz == 0.0) {
                return true;
            }

            for (Edge other : edges) {
                if (edge.equals(other)) {
                    continue;
                }
                double[] breaks = new double[6];
                breaks[0] = 0.0;
                breaks[1] = 1.0;
                int count = edge.addProjectionBreaks(startX, startZ, dx, dz, breaks, 2);
                count = other.addProjectionBreaks(startX, startZ, dx, dz, breaks, count);
                Arrays.sort(breaks, 0, count);

                // 最近点在“起点、线段内部、终点”间切换，分段后的距离平方差为二次函数。
                for (int i = 1; i < count; i++) {
                    double from = breaks[i - 1];
                    double to = breaks[i];
                    if (from == to) {
                        continue;
                    }
                    double first = distanceDifference(other, startX + dx * from, startZ + dz * from);
                    double last = distanceDifference(other, startX + dx * to, startZ + dz * to);
                    double middle = distanceDifference(other, startX + dx * (from + to) * 0.5,
                            startZ + dz * (from + to) * 0.5);
                    if (first > DISTANCE_EPSILON || last > DISTANCE_EPSILON || middle > DISTANCE_EPSILON) {
                        return false;
                    }
                    double a = 2.0 * (first + last - 2.0 * middle);
                    double b = last - first - a;
                    // 凹二次函数可能在区间内部越界，因此还要检查极大值，不能只采样端点。
                    if (a < 0.0) {
                        double peak = -b / (2.0 * a);
                        if (peak > 0.0 && peak < 1.0
                                && (a * peak + b) * peak + first > DISTANCE_EPSILON) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private double distanceDifference(Edge other, double x, double z) {
            return edge.distanceSquared(x, z) - other.distanceSquared(x, z);
        }
    }
}
