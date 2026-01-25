package com.hxzhitang.tongdarailway.util;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class CurveRoute {

    // 内部接口：曲线片段
    public interface CurveSegment {
        double getLength();
        Vec3 getPointAt(double u); // u 范围 [0, 1]
        Vec3 getTangentAt(double u);

        List<Vec3> rasterize(int n);
    }

    // 内部类：采样点信息
    public static class SamplePoint {
        Vec3 position;
        Vec3 tangent;
        double u;          // 在当前片段内的参数
        int segmentIndex;  // 片段索引
        double globalT;    // 在整条曲线上的参数 [0, 1]

        public SamplePoint(Vec3 pos, Vec3 tan, double u, int segmentIndex, double globalT) {
            this.position = new Vec3(pos.x, 0, pos.z); // 强制 y 为 0
            this.tangent = tan;
            this.u = u;
            this.segmentIndex = segmentIndex;
            this.globalT = globalT;
        }
    }

    // --- 成员变量 ---
    private final List<CurveSegment> segments = new ArrayList<>();
    private final List<SamplePoint> samplePoints = new ArrayList<>();
    private double totalLength = 0;
    private final int SAMPLES_PER_SEGMENT = 50;
    private KDNode kdTreeRoot;

    // --- 核心方法 ---

    public void addSegment(CurveSegment segment) {
        segments.add(segment);
        buildSamplePoints();
    }

    public CurveSegment getSegment(int index) {
        return segments.get(index);
    }

    public double getTotalLength() {
        return totalLength;
    }

    public void buildSamplePoints() {
        samplePoints.clear();
        totalLength = 0;

        // 计算总长度
        for (CurveSegment seg : segments) {
            totalLength += seg.getLength();
        }

        double accumulatedLength = 0;
        for (int i = 0; i < segments.size(); i++) {
            CurveSegment seg = segments.get(i);
            double segLen = seg.getLength();

            for (int j = 0; j <= SAMPLES_PER_SEGMENT; j++) {
                double u = (double) j / SAMPLES_PER_SEGMENT;
                Vec3 pos = seg.getPointAt(u);
                Vec3 tan = seg.getTangentAt(u);

                double currentGlobalDist = accumulatedLength + (u * segLen);
                double globalT = totalLength > 0 ? currentGlobalDist / totalLength : 0;

                samplePoints.add(new SamplePoint(pos, tan, u, i, globalT));
            }
            accumulatedLength += segLen;
        }

        // 构建用于快速查找的 KD-Tree
        kdTreeRoot = buildKDTree(new ArrayList<>(samplePoints), 0);
    }

    public Frame getFrame(Vec3 queryPoint) {
        Vec3 p = new Vec3(queryPoint.x, 0, queryPoint.z);

        // 1. 寻找最近的样本点
        SamplePoint best = findNearestNeighbor(kdTreeRoot, p, 0, null);

        // 2. 在同一个片段上寻找第二近的点（用于插值）
        SamplePoint secondBest = findSecondNearestOnSameSegment(best, p);

        if (best != null && secondBest != null) {
            // 计算投影插值比例
            Vec3 line = secondBest.position.subtract(best.position);
            double lineLenSq = line.lengthSqr();
            double factor = 0;
            if (lineLenSq > 1e-6) {
                factor = p.subtract(best.position).dot(line) / lineLenSq;
            }
            factor = Math.max(0, Math.min(1, factor));

            // 插值计算结果
            Vec3 nearestPos = best.position.add(line.scale(factor));
            Vec3 nearestTan = best.tangent.add(secondBest.tangent.subtract(best.tangent).scale(factor)).normalize();
            double finalU = best.u + (secondBest.u - best.u) * factor;
            double finalT = best.globalT + (secondBest.globalT - best.globalT) * factor;

//            System.out.println("--- Nearest Point Found ---");
//            System.out.println("Segment Index: " + best.segmentIndex);
//            System.out.println("Position: (" + nearestPos.x + ", " + nearestPos.y + ", " + nearestPos.z + ")");
//            System.out.println("Tangent: (" + nearestTan.x + ", " + nearestTan.y + ", " + nearestTan.z + ")");
//            System.out.println("Local Param u: " + finalU);
//            System.out.println("Global Param t: " + finalT);
//            System.out.println("Point In Curve: " + segments.get(best.segmentIndex).getPointAt(finalU));
//            System.out.println();

            // 这里寻找的曲线上最近点不是真正三维中的最近点
            // 是忽略y坐标，仅考虑xz平面的最近点
            // 这样得到的结果更加合理
            Vec3 resultPos = segments.get(best.segmentIndex).getPointAt(finalU);

            return new Frame(resultPos, nearestTan, finalT, finalU, best.segmentIndex);
        }

        return null;
    }

    // --- 内部实现类：LineSegment ---

    public static class LineSegment implements CurveSegment {
        public final Vec3 start, end;

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
        public final Vec3 p0, p1, p2, p3;

        public BezierSegment(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3) {
            this.p0 = p0; this.p1 = p1; this.p2 = p2; this.p3 = p3;
        }

        public static BezierSegment getCubicBezier(
                Vec3 startPos,           // 起点坐标
                Vec3 startAxis,          // 起点切线方向
                Vec3 endOffset,          // 终点相对于起点的偏移
                Vec3 endAxis             // 终点切线方向
        ) {
//            // 计算终点的绝对坐标
            Vec3 endPos = startPos.add(endOffset);
//
//            // 归一化轴向量
            Vec3 axis1 = startAxis.normalize();
            Vec3 axis2 = endAxis.normalize();
//
//            // 计算控制手柄长度
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

    // --- KD-Tree 辅助结构与算法 ---

    private static class KDNode {
        SamplePoint point;
        KDNode left, right;
        int axis; // 0 for X, 1 for Z

        KDNode(SamplePoint p, int axis) { this.point = p; this.axis = axis; }
    }

    private KDNode buildKDTree(List<SamplePoint> points, int depth) {
        if (points.isEmpty()) return null;
        int axis = depth % 2;
        points.sort((a, b) -> axis == 0 ? Double.compare(a.position.x, b.position.x) : Double.compare(a.position.z, b.position.z));
        int mid = points.size() / 2;
        KDNode node = new KDNode(points.get(mid), axis);
        node.left = buildKDTree(points.subList(0, mid), depth + 1);
        node.right = buildKDTree(points.subList(mid + 1, points.size()), depth + 1);
        return node;
    }

    private SamplePoint findNearestNeighbor(KDNode node, Vec3 target, int depth, SamplePoint best) {
        if (node == null) return best;
        double d2 = node.point.position.subtract(target).lengthSqr();
        if (best == null || d2 < best.position.subtract(target).lengthSqr()) {
            best = node.point;
        }
        int axis = depth % 2;
        double diff = (axis == 0) ? (target.x - node.point.position.x) : (target.z - node.point.position.z);
        KDNode near = diff < 0 ? node.left : node.right;
        KDNode far = diff < 0 ? node.right : node.left;
        best = findNearestNeighbor(near, target, depth + 1, best);
        if (diff * diff < best.position.subtract(target).lengthSqr()) {
            best = findNearestNeighbor(far, target, depth + 1, best);
        }
        return best;
    }

    private SamplePoint findSecondNearestOnSameSegment(SamplePoint first, Vec3 target) {
        SamplePoint second = null;
        double minDist = Double.MAX_VALUE;
        for (SamplePoint p : samplePoints) {
            if (p == first || p.segmentIndex != first.segmentIndex) continue;
            double d = p.position.distanceTo(target);
            if (d < minDist) {
                minDist = d;
                second = p;
            }
        }
        return second;
    }

    public static class Frame {
        public final Vec3 nearestPoint;
        public final Vec3 tangent;

        public final Vec3 tangent0;
        public final Vec3 normal0;
        public final Vec3 binormal0;

        public final double globalT;
        public final double localU;

        public final int segmentIndex;

        public Frame(Vec3 pos, Vec3 tangent, double globalT, double localU, int segmentIndex) {
            this.nearestPoint = pos;
            this.tangent = tangent;
            // 在铺路任务中，许多地方都要对y进行忽略处理。
            // 如寻找最近点、获取最近点上的标架
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
                    ", segmentIndex=" + segmentIndex +
                    '}';
        }
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
