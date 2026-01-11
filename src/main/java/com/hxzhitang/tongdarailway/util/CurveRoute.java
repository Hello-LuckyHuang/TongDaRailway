package com.hxzhitang.tongdarailway.util;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class CurveRoute {

    // --- 内部接口与类定义 ---

    public interface CurveSegment {
        double getLength();
        Vec3 getPointAt(double t); // t 范围 [0, 1]
        Vec3 getTangentAt(double t);
        List<Vec3> rasterize(int n); // 在xz平面缩小n倍并光栅化
    }

    /**
     * 采样点信息类，用于KD-Tree存储和插值
     */
    private static class SamplePoint {
        Vec3 position;
        Vec3 tangent;
        double segmentU;      // 在所属片段内的参数u
        double globalDist;    // 该点距离整条曲线起点的距离
        int segmentIndex;

        SamplePoint(Vec3 pos, Vec3 tan, double u, double dist, int idx) {
            this.position = pos;
            this.tangent = tan;
            this.segmentU = u;
            this.globalDist = dist;
            this.segmentIndex = idx;
        }
    }

    // --- 成员变量 ---

    private final List<CurveSegment> segments = new ArrayList<>();
    private final List<SamplePoint> allSamplePoints = new ArrayList<>();
    private KDNode kdTreeRoot;
    private double totalLength = 0;
    private final int SAMPLES_PER_SEGMENT = 50; // 每一段的采样密度

    // --- 核心方法 ---

    public void addSegment(CurveSegment segment) {
        segments.add(segment);
        buildSamplePoints();
    }

    public double getTotalLength() {
        return totalLength;
    }

    /**
     * 更新样本点列表，构建KD-Tree，计算总长度
     */
    public void buildSamplePoints() {
        allSamplePoints.clear();
        totalLength = 0;

        for (int i = 0; i < segments.size(); i++) {
            CurveSegment seg = segments.get(i);
            double segLen = seg.getLength();

            for (int j = 0; j <= SAMPLES_PER_SEGMENT; j++) {
                double u = (double) j / SAMPLES_PER_SEGMENT;
                Vec3 pos = seg.getPointAt(u);
                Vec3 tan = seg.getTangentAt(u);
                // 全局距离 = 之前段的总长 + 当前段内的距离近似
                double currentGlobalDist = totalLength + (u * segLen);

                allSamplePoints.add(new SamplePoint(pos, tan, u, currentGlobalDist, i));
            }
            totalLength += segLen;
        }

        // 构建用于快速查找的KD-Tree
        if (!allSamplePoints.isEmpty()) {
            kdTreeRoot = buildKDTree(new ArrayList<>(allSamplePoints), 0);
        }
    }

    /**
     * 寻找最近点：通过KD-Tree找到最近的两个采样点，并进行线性插值
     */
    public Frame getFrame(Vec3 point) {
        if (kdTreeRoot == null || totalLength == 0) return null;

        // 1. 查找最近的两个采样点
        PriorityQueue<Neighbor> neighbors = new PriorityQueue<>(Comparator.comparingDouble(n -> -n.distance));
        searchNearest(kdTreeRoot, point, 2, 0, neighbors);

        if (neighbors.size() < 2) return null;

        Neighbor n2 = neighbors.poll();
        Neighbor n1 = neighbors.poll(); // n1 是最近的，n2 是次近的

        SamplePoint p1 = n1.node.point;
        SamplePoint p2 = n2.node.point;

        // 2. 在两点间进行投影插值
        Vec3 v12 = p2.position.subtract(p1.position);
        double lineLenSq = v12.lengthSqr();
        double fraction = 0;

        if (lineLenSq > 1e-9) {
            Vec3 v1P = point.subtract(p1.position);
            fraction = Math.max(0, Math.min(1, v1P.dot(v12) / lineLenSq));
        }

        // 3. 计算结果
        Vec3 closestPos = p1.position.add(v12.scale(fraction));
        Vec3 closestTangent = p1.tangent.add(p2.tangent.subtract(p1.tangent).scale(fraction)).normalize();
        double closestDist = p1.globalDist + (p2.globalDist - p1.globalDist) * fraction;
        double tGlobal = closestDist / totalLength;

//        System.out.println("--- Nearest Point Results ---");
//        System.out.printf("Query Point: (%.2f, %.2f, %.2f)\n", point.x, point.y, point.z);
//        System.out.printf("Closest Pos: (%.2f, %.2f, %.2f)\n", closestPos.x, closestPos.y, closestPos.z);
//        System.out.printf("Tangent    : (%.2f, %.2f, %.2f)\n", closestTangent.x, closestTangent.y, closestTangent.z);
//        System.out.printf("Global T   : %.4f (Distance: %.2f / %.2f)\n", tGlobal, closestDist, totalLength);

        return new Frame(
                closestPos,
                closestTangent,
                tGlobal,
                p1.segmentU + (p2.segmentU - p1.segmentU) * fraction,
                p1.segmentIndex
        );
    }

    // --- 内部实现类：LineSegment ---

    public static class LineSegment implements CurveSegment {
        private Vec3 start, end;

        public LineSegment(Vec3 start, Vec3 end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public double getLength() {
            return start.distanceTo(end);
        }

        @Override
        public Vec3 getPointAt(double t) {
            return start.add(end.subtract(start).scale(t));
        }

        @Override
        public Vec3 getTangentAt(double t) {
            return end.subtract(start).normalize();
        }

        @Override
        public List<Vec3> rasterize(int n) {
            List<Vec3> points = new ArrayList<>();
            double len = getLength();
            int steps = (int) Math.max(1, len / n);
            for (int i = 0; i <= steps; i++) {
                Vec3 p = getPointAt((double) i / steps);
                points.add(new Vec3(p.x / n, 0, p.z / n));
            }
            return points;
        }
    }

    // --- 内部实现类：BezierSegment (三阶贝塞尔) ---

    public static class BezierSegment implements CurveSegment {
        private Vec3 p0, p1, p2, p3;

        public BezierSegment(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3) {
            this.p0 = p0; this.p1 = p1; this.p2 = p2; this.p3 = p3;
        }

        public static BezierSegment getCubicBezier(
                Vec3 startPos,           // 起点坐标
                Vec3 startAxis,          // 起点切线方向
                Vec3 endOffset,          // 终点相对于起点的偏移
                Vec3 endAxis             // 终点切线方向
        ) {
            // 计算终点的绝对坐标
            Vec3 endPos = startPos.add(endOffset);

            // 归一化轴向量
            Vec3 axis1 = startAxis.normalize();
            Vec3 axis2 = endAxis.normalize();

            // 计算控制手柄长度
            double handleLength = determineHandleLength(startPos, endPos, axis1, axis2);

            // 计算四个控制点
            Vec3 p0 = startPos;                                    // 起点
            Vec3 p1 = startPos.add(axis1.scale(handleLength));    // 第一个控制点
            Vec3 p2 = endPos.add(axis2.scale(handleLength));      // 第二个控制点
            Vec3 p3 = endPos; // 终点

            return new BezierSegment(p0, p1, p2, p3);
        }

        private static double determineHandleLength(Vec3 end1, Vec3 end2, Vec3 axis1, Vec3 axis2) {
            Vec3 cross1 = axis1.cross(new Vec3(0, 1, 0));
            Vec3 cross2 = axis2.cross(new Vec3(0, 1, 0));

            // 计算两个轴向的夹角
            double a1 = Mth.atan2(-axis2.z, -axis2.x);
            double a2 = Mth.atan2(axis1.z, axis1.x);
            double angle = a1 - a2;

            float circle = 2 * Mth.PI;
            angle = (angle + circle) % circle;
            if (Math.abs(circle - angle) < Math.abs(angle))
                angle = circle - angle;

            // 如果两个轴向平行
            if (Mth.equal(angle, 0)) {
                double[] intersect = VecHelper.intersect(end1, end2, axis1, cross2, Direction.Axis.Y);
                if (intersect != null) {
                    double t = Math.abs(intersect[0]);
                    double u = Math.abs(intersect[1]);
                    double min = Math.min(t, u);
                    double max = Math.max(t, u);

                    if (min > 1.2 && max / min > 1 && max / min < 3) {
                        return (max - min);
                    }
                }

                return end2.distanceTo(end1) / 3;
            }

            // 如果两个轴向不平行,使用圆弧公式计算
            double n = circle / angle;
            double factor = 4 / 3d * Math.tan(Math.PI / (2 * n));
            double[] intersect = VecHelper.intersect(end1, end2, cross1, cross2, Direction.Axis.Y);

            if (intersect == null) {
                return end2.distanceTo(end1) / 3;
            }

            double radius = Math.abs(intersect[1]);
            double handleLength = radius * factor;
            if (Mth.equal(handleLength, 0))
                handleLength = 1;

            return handleLength;
        }

        @Override
        public Vec3 getPointAt(double t) {
            double u = 1 - t;
            return p0.scale(u * u * u)
                    .add(p1.scale(3 * u * u * t))
                    .add(p2.scale(3 * u * t * t))
                    .add(p3.scale(t * t * t));
        }

        @Override
        public Vec3 getTangentAt(double t) {
            double u = 1 - t;
            // 一阶导数公式: 3(1-t)^2(p1-p0) + 6(1-t)t(p2-p1) + 3t^2(p3-p2)
            Vec3 tan = p1.subtract(p0).scale(3 * u * u)
                    .add(p2.subtract(p1).scale(6 * u * t))
                    .add(p3.subtract(p2).scale(3 * t * t));
            return tan.normalize();
        }

        @Override
        public double getLength() {
            // 数值积分近似长度
            double length = 0;
            int steps = 20;
            Vec3 prev = getPointAt(0);
            for (int i = 1; i <= steps; i++) {
                Vec3 curr = getPointAt((double) i / steps);
                length += prev.distanceTo(curr);
                prev = curr;
            }
            return length;
        }

        @Override
        public List<Vec3> rasterize(int n) {
            List<Vec3> points = new ArrayList<>();
            int steps = 20;
            for (int i = 0; i <= steps; i++) {
                Vec3 p = getPointAt((double) i / steps);
                points.add(new Vec3(p.x / n, 0, p.z / n));
            }
            return points;
        }
    }

    // --- KD-Tree 内部结构 ---

    private static class KDNode {
        SamplePoint point;
        KDNode left, right;
        int axis; // 0:x, 1:y, 2:z

        KDNode(SamplePoint p, int axis) {
            this.point = p;
            this.axis = axis;
        }
    }

    private KDNode buildKDTree(List<SamplePoint> points, int depth) {
        if (points.isEmpty()) return null;
        int axis = depth % 3;
        points.sort((a, b) -> Double.compare(getCoord(a.position, axis), getCoord(b.position, axis)));
        int mid = points.size() / 2;
        KDNode node = new KDNode(points.get(mid), axis);
        node.left = buildKDTree(points.subList(0, mid), depth + 1);
        node.right = buildKDTree(points.subList(mid + 1, points.size()), depth + 1);
        return node;
    }

    private void searchNearest(KDNode node, Vec3 target, int k, int depth, PriorityQueue<Neighbor> pq) {
        if (node == null) return;

        double dist = target.distanceTo(node.point.position);
        pq.add(new Neighbor(node, dist));
        if (pq.size() > k) pq.poll();

        int axis = node.axis;
        double diff = getCoord(target, axis) - getCoord(node.point.position, axis);

        KDNode near = diff < 0 ? node.left : node.right;
        KDNode far = diff < 0 ? node.right : node.left;

        searchNearest(near, target, k, depth + 1, pq);
        if (pq.size() < k || Math.abs(diff) < pq.peek().distance) {
            searchNearest(far, target, k, depth + 1, pq);
        }
    }

    private double getCoord(Vec3 v, int axis) {
        return axis == 0 ? v.x : (axis == 1 ? v.y : v.z);
    }

    private static class Neighbor {
        KDNode node;
        double distance;
        Neighbor(KDNode n, double d) { this.node = n; this.distance = d; }
    }

    //==========================

    public List<CurveSegment> getSegments(){
        return segments;
    }

    public ListTag toNBT() {
        ListTag curveTag = new ListTag();
        for (var segment : segments) {
            ListTag parameters = new ListTag();
            if (segment instanceof CurveRoute.LineSegment line) {
                parameters.add(vec2NBT(line.start));
                parameters.add(vec2NBT(line.end));
            } else if (segment instanceof CurveRoute.BezierSegment bezier) {
                parameters.add(vec2NBT(bezier.p0));
                parameters.add(vec2NBT(bezier.p1));
                parameters.add(vec2NBT(bezier.p2));
                parameters.add(vec2NBT(bezier.p3));
            }
            curveTag.add(parameters);
        }
        return curveTag;
    }

    public static CurveRoute fromNBT(ListTag curveTag) {
        CurveRoute curve = new CurveRoute();
        for (int i = 0; i < curveTag.size(); i++) {
            ListTag parameters = curveTag.getList(i);
            if (parameters.size() == 2) {
                Vec3 start = nbt2Vec((ListTag) parameters.get(0));
                Vec3 end = nbt2Vec((ListTag) parameters.get(1));
                curve.addSegment(new LineSegment(start, end));
            } else if (parameters.size() == 4) {
                Vec3 p0 = nbt2Vec((ListTag) parameters.get(0));
                Vec3 p1 = nbt2Vec((ListTag) parameters.get(1));
                Vec3 p2 = nbt2Vec((ListTag) parameters.get(2));
                Vec3 p3 = nbt2Vec((ListTag) parameters.get(3));
                curve.addSegment(new BezierSegment(p0, p1, p2, p3));
            }
        }
        return curve;
    }

    public static class Frame {
        public final Vec3 nearestPoint;
        public final Vec3 tangent;

        public final Vec3 tangent0;
        public final Vec3 normal0;
        public final Vec3 binormal0;

        public final double globalT;
        public final double localU;

        private final int segmentIndex;

        public Frame(Vec3 pos, Vec3 tangent, double globalT, double localU, int segmentIndex) {
            this.nearestPoint = pos;
            this.tangent = tangent;
            this.tangent0 = new Vec3(tangent.x, 0, tangent.z).normalize();
            this.normal0 = new Vec3(0, 1, 0);
            this.binormal0 = tangent0.cross(normal0);
            this.globalT = globalT;
            this.localU = localU;
            this.segmentIndex = segmentIndex;
        }

        @Override
        public String toString() {
            return "Frame{" +
                    "nearestPoint=" + nearestPoint +
                    ", tangent=" + tangent +
                    ", tangent0=" + tangent0 +
                    ", normal0=" + normal0 +
                    ", binormal0=" + binormal0 +
                    ", globalT=" + globalT +
                    ", localU=" + localU +
                    '}';
        }
    }

    private static ListTag vec2NBT(Vec3 point) {
        ListTag pointTag = new ListTag();
        pointTag.add(DoubleTag.valueOf(point.x));
        pointTag.add(DoubleTag.valueOf(point.y));
        pointTag.add(DoubleTag.valueOf(point.z));
        return pointTag;
    }

    private static Vec3 nbt2Vec(ListTag pointTag) {
        return new Vec3(pointTag.getDouble(0), pointTag.getDouble(1), pointTag.getDouble(2));
    }

    /*
    public static void main(String[] args) {
        CurveRoute route = new CurveRoute();

        // 1. 添加一条从 (0,0,0) 到 (10,0,0) 的直线
        route.addSegment(new LineSegment(new Vec3(0, 0, 0), new Vec3(10, 10, 10)));

        // 2. 添加一段贝塞尔曲线
        route.addSegment(new BezierSegment(
                new Vec3(10, 10, 10),
                new Vec3(15, 15, 15),
                new Vec3(25, 20, 30),
                new Vec3(30, 20, 35)
        ));

        System.out.println("Total Curve Length: " + route.getTotalLength());

        // 3. 测试寻找最近点
        System.out.println("\nTesting findNearestPoint for (5, 1, 0):");
        var test1 = new Vec3(5, 1, 0);
        var a = route.getFrame(test1);

        System.out.println(a);
        var vec1 = test1.subtract(a.nearestPoint);
        System.out.println(vec1.dot(a.tangent0));

        System.out.println("\nTesting findNearestPoint for (25, 21, 30):");
        var test2 = new Vec3(25, 19, 32);
        var b = route.getFrame(test2);

        System.out.println(b);
        var vec2 = test2.subtract(b.nearestPoint);
        System.out.println(vec2.dot(b.tangent0));

        // 4. 测试光栅化 (XZ平面)
        System.out.println("\nRasterizing segment 0 (n=2):");
        List<Vec3> raster = route.segments.get(1).rasterize(2);
        for (Vec3 p : raster) {
            System.out.printf("[%d, %d] ", (int)p.x, (int)p.z);
        }
    }
     */
}
